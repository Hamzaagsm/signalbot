package com.hamza.signalbot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real Market feed for Hamza Signal Bot v1.1 (HTTPS polling, no login).
 * Faithful Java port of the EXE's realmarket_feed.py — same source,
 * same rules.
 *
 * Why this exists: Hamza's rule — signals should come from where the
 * market moves FIRST. Binary/OTC prices (Quotex) follow the real
 * market, so v1.1 can also watch the real forex market directly.
 *
 * Source: Yahoo Finance chart API (no account, no API key):
 *   GET https://query1.finance.yahoo.com/v8/finance/chart/<SYM>=X
 *       ?interval=1m&range=1d
 *   -> chart.result[0].meta.regularMarketPrice / regularMarketTime
 *
 * Freshness was PROVEN live before building (Fri 2026-10-30, forex
 * market open): USD/JPY + EUR/JPY are tick-fresh; EUR/USD, GBP/USD and
 * AUD/USD arrive in ~1-minute snapshots — the engine simply gets fewer
 * points on those pairs; it never invents data.
 *
 * The feed polls each selected asset every ~2 seconds in its own
 * thread, de-duplicates unchanged quotes, and hands (ts, price) points
 * to the caller's callback — the caller feeds them into the SAME
 * SignalsEngine the Quotex stream uses, so strategies / filters /
 * stats behave identically. The only difference is the source stamp.
 *
 * HTTP uses the JDK's HttpURLConnection only (the counterpart of the
 * EXE's stdlib urllib), so this feed needs no extra dependency.
 *
 * SIGNAL ONLY: nothing here (or anywhere in this bot) places orders.
 */
public class RealMarketFeed {

    public static final String SOURCE_ID = "realmarket";
    public static final String SOURCE_LABEL = "Real Market";

    public static final String CHART_URL = "https://query1.finance.yahoo.com/v8/finance/chart/";
    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko)";

    /** Asset code (as used by the UI) -> Yahoo Finance symbol. */
    public static final Map<String, String> YAHOO_SYMBOLS = new LinkedHashMap<>();
    static {
        YAHOO_SYMBOLS.put("EURUSD", "EURUSD=X");
        YAHOO_SYMBOLS.put("GBPUSD", "GBPUSD=X");
        YAHOO_SYMBOLS.put("USDJPY", "USDJPY=X");
        YAHOO_SYMBOLS.put("EURGBP", "EURGBP=X");
        YAHOO_SYMBOLS.put("AUDUSD", "AUDUSD=X");
        YAHOO_SYMBOLS.put("EURJPY", "EURJPY=X");
    }

    public static String yahooSymbol(String code) {
        return YAHOO_SYMBOLS.get(code);
    }

    // ── payload parsing ───────────────────────────────────────────────

    /** One fetched quote: price + the market's own timestamp (seconds). */
    public static class Quote {
        public final double price;
        public final long marketTs;
        public Quote(double price, long marketTs) {
            this.price = price; this.marketTs = marketTs;
        }
    }

    /**
     * Extract (price, market_ts_seconds) from a Yahoo chart response.
     * Returns null for any malformed/error payload (never throws).
     */
    public static Quote parseChartPayload(JSONObject payload) {
        try {
            if (payload == null) return null;
            JSONObject chart = payload.optJSONObject("chart");
            if (chart == null) return null;
            JSONArray result = chart.optJSONArray("result");
            if (result == null || result.length() == 0) return null;
            JSONObject first = result.optJSONObject(0);
            if (first == null) return null;
            JSONObject meta = first.optJSONObject("meta");
            if (meta == null) return null;
            double price = meta.optDouble("regularMarketPrice", Double.NaN);
            long mtime = meta.optLong("regularMarketTime", 0);
            if (Double.isNaN(price) || price <= 0 || mtime < 1_000_000_000L) return null;
            return new Quote(price, mtime);
        } catch (Exception e) {
            return null;
        }
    }

    // ── callbacks / fetcher ───────────────────────────────────────────

    public interface OnPoint { void onPoint(String assetCode, long tsSeconds, double price); }
    public interface OnStatus { void onStatus(String text); }

    /** HTTP GET -> parsed JSON. Injectable so the rules stay unit-testable. */
    public interface Fetcher { JSONObject fetch(String url, double timeoutSeconds) throws Exception; }

    static JSONObject httpFetch(String url, double timeoutSeconds) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            int timeoutMs = (int) (timeoutSeconds * 1000);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return new JSONObject(sb.toString());
        } finally {
            conn.disconnect();
        }
    }

    // ── feed state ────────────────────────────────────────────────────

    public static class AssetState {
        public Double price;
        public Long marketTs;
        public Double lag;
        public Double lastPoll;
        public Double lastPoint;
        public int errors = 0;

        AssetState copy() {
            AssetState c = new AssetState();
            c.price = price; c.marketTs = marketTs; c.lag = lag;
            c.lastPoll = lastPoll; c.lastPoint = lastPoint; c.errors = errors;
            return c;
        }
    }

    public static class Snapshot {
        public Double lastUpdate;
        public Double lastPoll;
        public boolean stale;
        public final Map<String, AssetState> assets = new LinkedHashMap<>();
    }

    public final List<String> assets;
    public final double pollInterval;
    public final double timeout;

    private final OnPoint onPoint;
    private final OnStatus onStatus;
    private final Fetcher fetcher;
    private final Object lock = new Object();
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean stopped = true;

    private Double lastUpdate;   // wall time of the last NEW quote (any asset)
    private Double lastPoll;     // wall time of the last successful poll (any asset)
    private final Map<String, AssetState> assetState = new LinkedHashMap<>();
    private final Map<String, Quote> lastEmitted = new LinkedHashMap<>();

    public RealMarketFeed(List<String> assets, OnPoint onPoint, OnStatus onStatus) {
        this(assets, onPoint, onStatus, 2.0, 6.0, null);
    }

    public RealMarketFeed(List<String> assets, OnPoint onPoint, OnStatus onStatus,
                          double pollInterval, double timeout, Fetcher fetcher) {
        this.assets = new ArrayList<>(assets);
        this.onPoint = onPoint;
        this.onStatus = onStatus;
        this.pollInterval = pollInterval;
        this.timeout = timeout;
        this.fetcher = fetcher != null ? fetcher : RealMarketFeed::httpFetch;
        for (String code : this.assets) assetState.put(code, new AssetState());
    }

    static double nowSeconds() { return System.currentTimeMillis() / 1000.0; }

    // -- lifecycle ------------------------------------------------------

    public void start() {
        stopped = false;
        for (final String code : assets) {
            Thread t = new Thread(() -> runAsset(code), "rm-feed-" + code);
            t.setDaemon(true);
            t.start();
            threads.add(t);
        }
        StringBuilder sb = new StringBuilder("Real Market feed shuru: ");
        for (int i = 0; i < assets.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(assets.get(i));
        }
        emitStatus(sb.toString());
    }

    public void stop() {
        stopped = true;
        for (Thread t : threads) t.interrupt();
    }

    // -- staleness --------------------------------------------------------

    /** Wall time (epoch seconds) of the last successful poll, or null. */
    public Double lastPoll() {
        synchronized (lock) { return lastPoll; }
    }

    /** Wall time (epoch seconds) of the last NEW quote, or null. */
    public Double lastUpdate() {
        synchronized (lock) { return lastUpdate; }
    }

    public boolean isStale() { return isStale(10.0, nowSeconds()); }

    /** True when no successful poll happened within {@code threshold}s. */
    public boolean isStale(double threshold, double now) {
        Double lp;
        synchronized (lock) { lp = lastPoll; }
        if (lp == null) return true;
        return (now - lp) > threshold;
    }

    public Double dataLag() { return dataLag(nowSeconds()); }

    /** Worst market-timestamp lag (seconds) across assets, or null. */
    public Double dataLag(double now) {
        List<Long> tsList = new ArrayList<>();
        synchronized (lock) {
            for (AssetState st : assetState.values())
                if (st.marketTs != null) tsList.add(st.marketTs);
        }
        if (tsList.isEmpty()) return null;
        double worst = Double.NEGATIVE_INFINITY;
        for (long ts : tsList) worst = Math.max(worst, now - ts);
        return worst;
    }

    public Snapshot snapshot() {
        Snapshot s = new Snapshot();
        synchronized (lock) {
            s.lastUpdate = lastUpdate;
            s.lastPoll = lastPoll;
            for (Map.Entry<String, AssetState> e : assetState.entrySet())
                s.assets.put(e.getKey(), e.getValue().copy());
        }
        s.stale = isStale();
        return s;
    }

    // -- internals --------------------------------------------------------

    private void emitStatus(String text) {
        if (onStatus == null) return;
        try { onStatus.onStatus(text); } catch (Exception ignored) {}
    }

    /** One HTTP poll for one asset; a Quote or null. */
    public Quote fetchQuote(String code) throws Exception {
        String url = CHART_URL + yahooSymbol(code) + "?interval=1m&range=1d";
        return parseChartPayload(fetcher.fetch(url, timeout));
    }

    /**
     * Record a fetched quote; emit onPoint only when it is new.
     * Returns true when a point was emitted. Split out from the
     * network loop so the aggregation rules are unit-testable.
     */
    public boolean handleQuote(String code, double price, long marketTs, double now) {
        synchronized (lock) {
            AssetState st = assetState.get(code);
            if (st == null) return false;
            st.price = price;
            st.marketTs = marketTs;
            st.lag = Math.round((now - marketTs) * 10.0) / 10.0;
            st.lastPoll = now;
            st.errors = 0;
            lastPoll = now;
            Quote last = lastEmitted.get(code);
            if (last != null && last.price == price && last.marketTs == marketTs)
                return false;
            lastEmitted.put(code, new Quote(price, marketTs));
            st.lastPoint = now;
            lastUpdate = now;
        }
        if (onPoint != null) onPoint.onPoint(code, marketTs, price);
        return true;
    }

    private void runAsset(String code) {
        double backoff = pollInterval;
        while (!stopped) {
            long t0 = System.currentTimeMillis();
            try {
                Quote got = fetchQuote(code);
                if (got == null) throw new IOException("empty/bad payload");
                handleQuote(code, got.price, got.marketTs, t0 / 1000.0);
                backoff = pollInterval;
            } catch (Exception e) {
                int errs;
                synchronized (lock) {
                    AssetState st = assetState.get(code);
                    st.errors += 1;
                    errs = st.errors;
                }
                if (errs == 1 || errs % 10 == 0) {
                    emitStatus("⚠️ Real Market " + code + ": " + e.getClass().getSimpleName()
                            + " — dobara koshish jaari hai…");
                }
                if (!sleepMs((long) (backoff * 1000))) return;
                backoff = Math.min(backoff * 2, 30.0);
                continue;
            }
            long elapsed = System.currentTimeMillis() - t0;
            if (!sleepMs(Math.max(0, (long) (pollInterval * 1000) - elapsed))) return;
        }
    }

    /** Sleep; returns false when the feed was stopped meanwhile. */
    private boolean sleepMs(long ms) {
        if (ms <= 0) return !stopped;
        try {
            Thread.sleep(ms);
            return !stopped;
        } catch (InterruptedException e) {
            return false;
        }
    }
}
