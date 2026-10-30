package com.hamza.signalbot;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * JVM unit tests for the ported engine — a faithful mirror of the
 * Python test_signals.py cases (same candles, same expectations), so
 * the APK's brain is verified against the EXE's brain case by case.
 */
public class SignalsEngineTest {

    static final long B = 1700000040L;  // divisible by 30 and 60

    static class Harness {
        SignalsEngine eng;
        final List<SignalsEngine.Signal> sigs = new ArrayList<>();
        final List<SignalsEngine.Signal> res = new ArrayList<>();
        final List<String[]> blocked = new ArrayList<>();
        final List<SignalsEngine.Candle> candles = new ArrayList<>();
    }

    static Harness makeEngine(Set<String> strategies, List<Integer> tfs, double threshold,
                              boolean trendFilter, boolean rsiFilter, int maxSignals) throws Exception {
        Map<String, String> assets = new LinkedHashMap<>();
        assets.put("EURUSD_otc", "EUR/USD OTC");
        Harness h = new Harness();
        h.eng = new SignalsEngine(assets, tfs, threshold, strategies,
                trendFilter, rsiFilter, maxSignals);
        h.eng.onSignal = s -> h.sigs.add(s);
        h.eng.onResult = s -> h.res.add(s);
        h.eng.onBlocked = (k, d, w) -> h.blocked.add(new String[]{k, d, w});
        return h;
    }

    static Harness makeEngine() throws Exception {
        return makeEngine(strat("perfect"), tfs(60), 85.0, false, false, 0);
    }

    /** v1.1: engine with an explicit feed source stamp + assets map. */
    static Harness makeEngineSource(Set<String> strategies, List<Integer> tfs, double threshold,
                                    boolean trendFilter, boolean rsiFilter, int maxSignals,
                                    String source, Map<String, String> assets) throws Exception {
        Harness h = new Harness();
        h.eng = new SignalsEngine(assets, tfs, threshold, strategies,
                trendFilter, rsiFilter, 70.0, 30.0, maxSignals, source);
        h.eng.onSignal = s -> h.sigs.add(s);
        h.eng.onResult = s -> h.res.add(s);
        h.eng.onBlocked = (k, d, w) -> h.blocked.add(new String[]{k, d, w});
        h.eng.onCandle = (k, t, c) -> h.candles.add(c);
        return h;
    }

    /** tick() without the checked exception, for use inside feed lambdas. */
    static JSONObject tickQ(long ts, double price) {
        try {
            return tick(ts, price);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static Set<String> strat(String... kinds) throws Exception {
        return new LinkedHashSet<>(Arrays.asList(kinds));
    }

    static List<Integer> tfs(int... tfs) throws Exception {
        List<Integer> l = new ArrayList<>();
        for (int t : tfs) l.add(t);
        return l;
    }

    static JSONObject ohlc(long ts, double o, double h, double l, double c) throws Exception {
        JSONObject j = new JSONObject();
        j.put("timestamp", ts);
        j.put("open", o); j.put("high", h); j.put("low", l); j.put("close", c);
        return j;
    }

    static JSONObject tick(long ts, double price) throws Exception {
        JSONObject j = new JSONObject();
        j.put("timestamp", ts);
        j.put("price", price);
        return j;
    }

    static void feedCandle(SignalsEngine eng, long start, double o, double h, double l, double c) throws Exception {
        feedCandle(eng, start, o, h, l, c, 60);
    }

    static void feedCandle(SignalsEngine eng, long start, double o, double h, double l,
                           double c, int tf) throws Exception {
        eng.feed("EURUSD_otc", ohlc(start, o, h, l, c));
        eng.feed("EURUSD_otc", tick(start + tf, c));  // closes the candle
    }

    // ── PERFECT CANDLE ────────────────────────────────────────────────

    @Test public void perfectGreenCall() throws Exception {
        Harness h = makeEngine();
        feedCandle(h.eng, B, 1.0000, 1.00100, 0.99990, 1.00095);  // body 86.4%
        assertEquals(1, h.sigs.size());
        assertEquals("call", h.sigs.get(0).direction);
        assertEquals("perfect", h.sigs.get(0).kind);
    }

    @Test public void perfectDetailShowsBodyPct() throws Exception {
        Harness h = makeEngine();
        feedCandle(h.eng, B, 1.0000, 1.00100, 0.99990, 1.00095);
        assertTrue(h.sigs.get(0).detail.contains("86%"));
    }

    @Test public void perfectRedPut() throws Exception {
        Harness h = makeEngine();
        feedCandle(h.eng, B, 1.00100, 1.00105, 0.99995, 1.00005);
        assertEquals(1, h.sigs.size());
        assertEquals("put", h.sigs.get(0).direction);
    }

    @Test public void smallBodyNoSignal() throws Exception {
        Harness h = makeEngine();
        feedCandle(h.eng, B, 1.0000, 1.00110, 0.99990, 1.00060);  // body 50%
        assertEquals(0, h.sigs.size());
    }

    @Test public void dojiNoSignal() throws Exception {
        Harness h = makeEngine();
        feedCandle(h.eng, B, 1.00050, 1.00110, 0.99990, 1.00050);
        assertEquals(0, h.sigs.size());
    }

    @Test public void threshold70Passes76() throws Exception {
        Harness h = makeEngine(strat("perfect"), tfs(60), 70.0, false, false, 0);
        feedCandle(h.eng, B, 1.0000, 1.00100, 0.99995, 1.00080);  // body 76.2%
        assertEquals(1, h.sigs.size());
    }

    // ── RESULT TRACKING ───────────────────────────────────────────────

    @Test public void nextCandleUpWin() throws Exception {
        Harness h = makeEngine();
        feedCandle(h.eng, B, 1.0000, 1.00100, 0.99990, 1.00095);
        feedCandle(h.eng, B + 60, 1.00095, 1.00150, 1.00090, 1.00120);
        assertEquals(1, h.res.size());
        assertEquals("WIN", h.res.get(0).result);
        assertEquals(1, h.eng.stats.wins);
        assertEquals(100.0, h.eng.stats.winPct(), 1e-9);
        assertEquals(1, h.eng.statsByKind.get("perfect").wins);
    }

    @Test public void nextCandleDownLoss() throws Exception {
        Harness h = makeEngine();
        feedCandle(h.eng, B, 1.0000, 1.00100, 0.99990, 1.00095);
        feedCandle(h.eng, B + 60, 1.00095, 1.00100, 0.99980, 0.99990);
        assertEquals(1, h.res.size());
        assertEquals("LOSS", h.res.get(0).result);
        assertEquals(0.0, h.eng.stats.winPct(), 1e-9);
    }

    // ── TICK-BUILT 30s CANDLES ────────────────────────────────────────

    @Test public void tickBuilt30sCandles() throws Exception {
        Harness h = makeEngine(strat("perfect"), tfs(30), 85.0, false, false, 0);
        long[][] ticks = {{B, 0}, {B + 5, 0}, {B + 10, 0}, {B + 20, 0}, {B + 29, 0},
                {B + 30, 0}, {B + 35, 0}, {B + 50, 0}, {B + 59, 0}, {B + 60, 0}};
        double[] prices = {1.0000, 1.0004, 1.0008, 1.0010, 1.00105,
                1.00105, 1.0012, 1.0014, 1.0013, 1.0013};
        for (int i = 0; i < ticks.length; i++) {
            JSONObject j = new JSONObject();
            j.put("time", ticks[i][0]);
            j.put("price", prices[i]);
            h.eng.feed("EURUSD_otc", j);
        }
        assertEquals(1, h.sigs.size());
        assertEquals("call", h.sigs.get(0).direction);
        assertEquals(30, h.sigs.get(0).timeframe);
        assertEquals(1, h.res.size());
        assertEquals("WIN", h.res.get(0).result);
    }

    // ── MAX SIGNALS ───────────────────────────────────────────────────

    @Test public void maxSignalsCaps() throws Exception {
        Harness h = makeEngine(strat("perfect"), tfs(60), 85.0, false, false, 1);
        feedCandle(h.eng, B, 1.0000, 1.00100, 0.99990, 1.00095);
        feedCandle(h.eng, B + 60, 1.0000, 1.00100, 0.99990, 1.00095);
        assertEquals(1, h.sigs.size());
        assertEquals(1, h.eng.issued);
    }

    // ── ENGULFING ─────────────────────────────────────────────────────

    @Test public void bullishEngulfing() throws Exception {
        Harness h = makeEngine(strat("engulfing"), tfs(60), 85.0, false, false, 0);
        feedCandle(h.eng, B, 1.0010, 1.0012, 1.0000, 1.0002);        // red
        feedCandle(h.eng, B + 60, 1.0001, 1.0013, 0.9999, 1.0011);   // green engulfs
        assertEquals(1, h.sigs.size());
        assertEquals("engulfing", h.sigs.get(0).kind);
        assertEquals("call", h.sigs.get(0).direction);
    }

    @Test public void bearishEngulfing() throws Exception {
        Harness h = makeEngine(strat("engulfing"), tfs(60), 85.0, false, false, 0);
        feedCandle(h.eng, B, 1.0002, 1.0012, 1.0000, 1.0010);        // green
        feedCandle(h.eng, B + 60, 1.0011, 1.0013, 0.9999, 1.0001);   // red engulfs
        assertEquals(1, h.sigs.size());
        assertEquals("put", h.sigs.get(0).direction);
    }

    @Test public void partialCoverNoEngulfing() throws Exception {
        Harness h = makeEngine(strat("engulfing"), tfs(60), 85.0, false, false, 0);
        feedCandle(h.eng, B, 1.0010, 1.0012, 1.0000, 1.0002);        // red
        feedCandle(h.eng, B + 60, 1.0003, 1.0011, 0.9999, 1.0009);   // close < prev open
        assertEquals(0, h.sigs.size());
    }

    // ── PIN BAR ───────────────────────────────────────────────────────

    @Test public void hammerPinbar() throws Exception {
        Harness h = makeEngine(strat("pinbar"), tfs(60), 85.0, false, false, 0);
        feedCandle(h.eng, B, 1.00080, 1.00090, 0.99900, 1.00085);    // long lower wick
        assertEquals(1, h.sigs.size());
        assertEquals("pinbar", h.sigs.get(0).kind);
        assertEquals("call", h.sigs.get(0).direction);
    }

    @Test public void shootingStarPinbar() throws Exception {
        Harness h = makeEngine(strat("pinbar"), tfs(60), 85.0, false, false, 0);
        feedCandle(h.eng, B, 1.00020, 1.00190, 1.00010, 1.00015);    // long upper wick
        assertEquals(1, h.sigs.size());
        assertEquals("put", h.sigs.get(0).direction);
    }

    @Test public void shortWickNoPinbar() throws Exception {
        Harness h = makeEngine(strat("pinbar"), tfs(60), 85.0, false, false, 0);
        feedCandle(h.eng, B, 1.0000, 1.00100, 0.99950, 1.00060);     // wick ~33%
        assertEquals(0, h.sigs.size());
    }

    // ── TREND FILTER ──────────────────────────────────────────────────

    static class TrendRun { Harness h; double p; }

    static TrendRun uptrend(boolean trendFilter) throws Exception {
        TrendRun r = new TrendRun();
        r.h = makeEngine(strat("perfect"), tfs(60), 85.0, trendFilter, false, 0);
        double p = 1.0000;
        for (int i = 0; i < 55; i++) {  // steadily rising closes -> uptrend
            double o = p, c = p + 0.0002;
            feedCandle(r.h.eng, B + i * 60L, o, c + 0.00005, o - 0.00005, c);
            p = c;
        }
        r.p = p;
        return r;
    }

    @Test public void trendFilterBlocksCounterTrend() throws Exception {
        TrendRun r = uptrend(true);
        int n0 = r.h.sigs.size();
        feedCandle(r.h.eng, B + 55 * 60L, r.p, r.p + 0.00005, r.p - 0.00105, r.p - 0.0010);
        assertEquals(n0, r.h.sigs.size());
        boolean blockedPerfect = false;
        for (String[] b : r.h.blocked) if (b[0].equals("perfect")) blockedPerfect = true;
        assertTrue(blockedPerfect);
    }

    @Test public void trendFilterOffAllowsCounterTrend() throws Exception {
        TrendRun r = uptrend(false);
        int n0 = r.h.sigs.size();
        feedCandle(r.h.eng, B + 55 * 60L, r.p, r.p + 0.00005, r.p - 0.00105, r.p - 0.0010);
        assertEquals(n0 + 1, r.h.sigs.size());
        assertEquals("put", r.h.sigs.get(r.h.sigs.size() - 1).direction);
    }

    // ── RSI FILTER ────────────────────────────────────────────────────

    static TrendRun rising(boolean rsiFilter) throws Exception {
        TrendRun r = new TrendRun();
        r.h = makeEngine(strat("perfect"), tfs(60), 85.0, false, rsiFilter, 0);
        double p = 1.0000;
        for (int i = 0; i < 20; i++) {  // all gains -> RSI = 100
            double o = p, c = p + 0.0002;
            feedCandle(r.h.eng, B + i * 60L, o, c + 0.00005, o - 0.00005, c);
            p = c;
        }
        r.p = p;
        return r;
    }

    @Test public void rsiFilterBlocksCallAt100() throws Exception {
        TrendRun r = rising(true);
        int n0 = r.h.sigs.size();
        feedCandle(r.h.eng, B + 20 * 60L, r.p, r.p + 0.0010, r.p - 0.0001, r.p + 0.00095);
        assertEquals(n0, r.h.sigs.size());
        assertTrue(r.h.blocked.size() >= 1);
    }

    @Test public void rsiFilterOffAllows() throws Exception {
        TrendRun r = rising(false);
        int n0 = r.h.sigs.size();
        feedCandle(r.h.eng, B + 20 * 60L, r.p, r.p + 0.0010, r.p - 0.0001, r.p + 0.00095);
        assertEquals(n0 + 1, r.h.sigs.size());
    }

    // ── STRENGTH GRADE ────────────────────────────────────────────────

    @Test public void withTrendPerfectIsStrong() throws Exception {
        TrendRun r = uptrend(true);
        feedCandle(r.h.eng, B + 55 * 60L, r.p, r.p + 0.0010, r.p - 0.0001, r.p + 0.00095);
        SignalsEngine.Signal s = r.h.sigs.get(r.h.sigs.size() - 1);
        assertEquals("STRONG", s.grade);
        assertTrue(s.strength >= 3);
    }

    @Test public void confluenceFiresBothStrategies() throws Exception {
        Harness h = makeEngine(strat("perfect", "engulfing"), tfs(60), 85.0, false, false, 0);
        h.eng.feed("EURUSD_otc", ohlc(B, 1.0010, 1.00115, 1.00090, 1.00095));       // red
        h.eng.feed("EURUSD_otc", ohlc(B + 60, 1.00090, 1.00122, 1.00088, 1.00120));  // engulfs + body 88%
        h.eng.feed("EURUSD_otc", tick(B + 120, 1.00120));                            // close candle 2
        assertEquals(2, h.sigs.size());
        List<String> kinds = new ArrayList<>();
        for (SignalsEngine.Signal s : h.sigs) {
            kinds.add(s.kind);
            assertTrue(s.strength >= 2);
        }
        assertTrue(kinds.contains("engulfing") && kinds.contains("perfect"));
    }

    // ── PER-KIND RESULT STATS ─────────────────────────────────────────

    @Test public void perKindLossStats() throws Exception {
        Harness h = makeEngine(strat("perfect", "engulfing"), tfs(60), 85.0, false, false, 0);
        h.eng.feed("EURUSD_otc", ohlc(B, 1.0010, 1.00115, 1.00090, 1.00095));        // red
        h.eng.feed("EURUSD_otc", ohlc(B + 60, 1.00090, 1.00122, 1.00088, 1.00120));  // perfect+engulfing CALL
        h.eng.feed("EURUSD_otc", ohlc(B + 120, 1.00120, 1.00125, 1.00090, 1.00100)); // target DOWN
        h.eng.feed("EURUSD_otc", tick(B + 180, 1.00100));                            // close target
        assertEquals(1, h.eng.statsByKind.get("perfect").losses);
        assertEquals(1, h.eng.statsByKind.get("engulfing").losses);
        assertEquals(2, h.eng.stats.losses);
    }

    // ── PAYLOAD SHAPES ────────────────────────────────────────────────

    @Test public void payloadShapes() throws Exception {
        List<SignalsEngine.Point> ms = SignalsEngine.extractPoints(
                tick(1700000000000L, 1.5));
        assertEquals(1, ms.size());
        assertEquals(1700000000L, ms.get(0).ts);
        assertEquals(1.5, ms.get(0).price, 1e-12);
        assertNull(ms.get(0).ohlc);

        JSONArray arr = new JSONArray();
        JSONObject d1 = new JSONObject();
        d1.put("time", 1700000000L); d1.put("close", 2.5);
        arr.put(d1);
        JSONArray pair = new JSONArray();
        pair.put(1700000060L); pair.put(2.6);
        arr.put(pair);
        assertEquals(2, SignalsEngine.extractPoints(arr).size());

        JSONObject nested = new JSONObject();
        nested.put("data", ohlc(1700000000L, 1, 2, 0.5, 1.5));
        List<SignalsEngine.Point> np = SignalsEngine.extractPoints(nested);
        assertEquals(1, np.size());
        assertEquals(2.0, np.get(0).ohlc.high, 1e-12);
    }

    // ── v1.1 REAL MARKET FEED (Python test_signals.py ka mirror) ─────

    @Test public void yahooSymbolMappingAllPairs() throws Exception {
        String[] codes = {"EURUSD", "GBPUSD", "USDJPY", "EURGBP", "AUDUSD", "EURJPY"};
        String[] want = {"EURUSD=X", "GBPUSD=X", "USDJPY=X", "EURGBP=X", "AUDUSD=X", "EURJPY=X"};
        for (int i = 0; i < codes.length; i++)
            assertEquals(want[i], RealMarketFeed.yahooSymbol(codes[i]));
    }

    static JSONObject chartPayload(double price, long mtime) throws Exception {
        JSONObject meta = new JSONObject();
        meta.put("regularMarketPrice", price);
        meta.put("regularMarketTime", mtime);
        JSONObject first = new JSONObject();
        first.put("meta", meta);
        JSONArray result = new JSONArray();
        result.put(first);
        JSONObject chart = new JSONObject();
        chart.put("result", result);
        JSONObject payload = new JSONObject();
        payload.put("chart", chart);
        return payload;
    }

    @Test public void chartPayloadParsed() throws Exception {
        RealMarketFeed.Quote q = RealMarketFeed.parseChartPayload(chartPayload(1.1193, 1793334045L));
        assertTrue(q != null);
        assertEquals(1.1193, q.price, 1e-12);
        assertEquals(1793334045L, q.marketTs);
    }

    @Test public void chartErrorPayloadsRejected() throws Exception {
        JSONObject finance = new JSONObject();
        finance.put("result", JSONObject.NULL);
        JSONObject err = new JSONObject();
        err.put("code", "Unauthorized");
        finance.put("error", err);
        JSONObject financePayload = new JSONObject();
        financePayload.put("finance", finance);
        assertNull(RealMarketFeed.parseChartPayload(financePayload));

        JSONObject chartNull = new JSONObject();
        chartNull.put("result", JSONObject.NULL);
        JSONObject chartPayloadNull = new JSONObject();
        chartPayloadNull.put("chart", chartNull);
        assertNull(RealMarketFeed.parseChartPayload(chartPayloadNull));

        assertNull(RealMarketFeed.parseChartPayload(chartPayload(0, 1793334045L)));
        assertNull(RealMarketFeed.parseChartPayload(null));
    }

    // staleness flag logic (no network: quotes injected via handleQuote)
    @Test public void feedStaleBeforeAnyPoll() throws Exception {
        RealMarketFeed feed = new RealMarketFeed(
                Arrays.asList("EURUSD"), (c, t, p) -> {}, null);
        assertTrue(feed.isStale(10.0, 1793334000.0));
    }

    @Test public void feedFreshThenStaleAndLag() throws Exception {
        RealMarketFeed feed = new RealMarketFeed(
                Arrays.asList("EURUSD"), (c, t, p) -> {}, null);
        double now = 1793334000.0;
        feed.handleQuote("EURUSD", 1.1193, 1793333995L, now);
        assertTrue(!feed.isStale(10.0, now + 5));
        assertTrue(feed.isStale(10.0, now + 11));
        assertEquals(5.0, feed.dataLag(now), 1e-9);
    }

    // duplicate quotes are not re-emitted (polling the same snapshot twice)
    @Test public void unchangedQuoteDeduplicated() throws Exception {
        final List<Double> pts = new ArrayList<>();
        RealMarketFeed feed = new RealMarketFeed(
                Arrays.asList("EURUSD"), (c, t, p) -> pts.add(p), null);
        feed.handleQuote("EURUSD", 1.1000, 1793334000L, 1793334001.0);
        feed.handleQuote("EURUSD", 1.1000, 1793334000L, 1793334002.0);
        feed.handleQuote("EURUSD", 1.1001, 1793334003L, 1793334004.0);
        assertEquals(2, pts.size());
        assertEquals(1.1001, pts.get(1), 1e-12);
    }

    // points -> engine candles: same aggregation path as the Quotex feed,
    // driven through RealMarketFeed.handleQuote exactly like the UI does
    @Test public void rmPointsAggregateStampAndStats() throws Exception {
        Map<String, String> assets = new LinkedHashMap<>();
        assets.put("USDJPY", "USD/JPY");
        final Harness h = makeEngineSource(strat("perfect"), tfs(30), 85.0, false, false, 0,
                "realmarket", assets);
        RealMarketFeed feed = new RealMarketFeed(Arrays.asList("USDJPY"),
                (code, ts, p) -> h.eng.feed(code, tickQ(ts, p)), null);
        long[][] pts = {{B, 0}, {B + 5, 0}, {B + 10, 0}, {B + 20, 0}, {B + 29, 0},
                {B + 30, 0}, {B + 35, 0}, {B + 59, 0}, {B + 60, 0}};
        double[] prices = {100.00, 100.10, 100.20, 100.28, 100.30,
                100.30, 100.40, 100.45, 100.45};
        for (int i = 0; i < pts.length; i++)
            feed.handleQuote("USDJPY", prices[i], pts[i][0], (double) pts[i][0]);

        SignalsEngine.Candle c0 = h.candles.get(0);
        assertEquals(100.00, c0.open, 1e-9);
        assertEquals(100.30, c0.high, 1e-9);
        assertEquals(100.00, c0.low, 1e-9);
        assertEquals(100.30, c0.close, 1e-9);

        assertEquals(2, h.sigs.size());   // target candle is itself 100%-body
        for (SignalsEngine.Signal s : h.sigs) assertEquals("call", s.direction);
        assertEquals("realmarket", h.sigs.get(0).source);
        assertEquals("Real Market", h.sigs.get(0).sourceLabel());

        assertEquals(1, h.res.size());
        assertEquals("WIN", h.res.get(0).result);

        SignalsEngine.Stats rmStats = h.eng.statsFor("realmarket", "perfect");
        assertTrue(rmStats != null);
        assertEquals(1, rmStats.wins);
        assertEquals(1, rmStats.total);
        SignalsEngine.Stats qxStats = h.eng.statsFor("quotex", "perfect");
        assertTrue(qxStats == null || qxStats.total == 0);
    }

    // default source stays "quotex" (v1.0 behaviour unchanged)
    @Test public void defaultEngineStampsQuotexSource() throws Exception {
        Harness h = makeEngine();
        feedCandle(h.eng, B, 1.0000, 1.00100, 0.99990, 1.00095);
        feedCandle(h.eng, B + 60, 1.00095, 1.00150, 1.00090, 1.00120);
        assertEquals("quotex", h.sigs.get(0).source);
        assertEquals("Quotex", h.sigs.get(0).sourceLabel());
        assertEquals(1, h.eng.statsFor("quotex", "perfect").wins);
    }

    // per-source stats isolation: same winning pattern, two engines,
    // each records ONLY under its own source stamp
    @Test public void perSourceStatsIsolation() throws Exception {
        Map<String, String> assets = new LinkedHashMap<>();
        assets.put("EURUSD_otc", "EUR/USD OTC");
        Harness qx = makeEngineSource(strat("perfect"), tfs(60), 85.0, false, false, 0,
                "quotex", assets);
        Harness rm = makeEngineSource(strat("perfect"), tfs(60), 85.0, false, false, 0,
                "realmarket", assets);
        for (Harness h : new Harness[]{qx, rm}) {
            feedCandle(h.eng, B, 1.0000, 1.00100, 0.99990, 1.00095);
            feedCandle(h.eng, B + 60, 1.00095, 1.00150, 1.00090, 1.00120);
        }
        assertEquals(1, qx.eng.statsFor("quotex", "perfect").wins);
        SignalsEngine.Stats qxOther = qx.eng.statsFor("realmarket", "perfect");
        assertTrue(qxOther == null || qxOther.total == 0);
        assertEquals(1, rm.eng.statsFor("realmarket", "perfect").wins);
        SignalsEngine.Stats rmOther = rm.eng.statsFor("quotex", "perfect");
        assertTrue(rmOther == null || rmOther.total == 0);
    }

    // ── v1.2 IQ OPTION FEED ─────────────────────────────────────────

    private static Calendar utc(int year, int month1to12, int day, int hour) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.set(year, month1to12 - 1, day, hour, 0, 0);
        return c;
    }

    @Test public void iqActivesMappingAllPairs() {
        assertEquals(6, IqOptionFeed.ACTIVES.size());
        assertTrue(Arrays.equals(new int[]{1, 76}, IqOptionFeed.ACTIVES.get("EURUSD")));
        assertTrue(Arrays.equals(new int[]{5, 81}, IqOptionFeed.ACTIVES.get("GBPUSD")));
        assertTrue(Arrays.equals(new int[]{6, 85}, IqOptionFeed.ACTIVES.get("USDJPY")));
        assertTrue(Arrays.equals(new int[]{2, 77}, IqOptionFeed.ACTIVES.get("EURGBP")));
        assertTrue(Arrays.equals(new int[]{99, -1}, IqOptionFeed.ACTIVES.get("AUDUSD")));
        assertTrue(Arrays.equals(new int[]{4, 79}, IqOptionFeed.ACTIVES.get("EURJPY")));
    }

    @Test public void iqMarketOpenHours() {
        assertTrue(IqOptionFeed.marketOpen(utc(2026, 10, 28, 12)));    // Wed noon
        assertTrue(!IqOptionFeed.marketOpen(utc(2026, 10, 31, 12)));   // Sat noon
        assertTrue(!IqOptionFeed.marketOpen(utc(2026, 11, 1, 21)));   // Sun before 22:00
        assertTrue(IqOptionFeed.marketOpen(utc(2026, 11, 1, 23)));    // Sun after 22:00
        assertTrue(!IqOptionFeed.marketOpen(utc(2026, 10, 30, 23)));  // Fri after 22:00
    }

    @Test public void iqActiveForWeekdayWeekend() {
        IqOptionFeed.ActiveChoice wed = IqOptionFeed.activeFor("EURUSD", utc(2026, 10, 28, 12));
        assertEquals(1, wed.id); assertTrue(!wed.otc);
        IqOptionFeed.ActiveChoice sat = IqOptionFeed.activeFor("EURUSD", utc(2026, 10, 31, 12));
        assertEquals(76, sat.id); assertTrue(sat.otc);
        IqOptionFeed.ActiveChoice audSat = IqOptionFeed.activeFor("AUDUSD", utc(2026, 10, 31, 12));
        assertEquals(99, audSat.id); assertTrue(!audSat.otc);   // no OTC entry -> regular
    }

    @Test public void iqSubscribeEnvelope() throws Exception {
        JSONObject sub = IqOptionFeed.buildSubscribe(76);
        assertEquals("subscribeMessage", sub.getString("name"));
        JSONObject msg = sub.getJSONObject("msg");
        assertEquals("candle-generated", msg.getString("name"));
        JSONObject filters = msg.getJSONObject("params").getJSONObject("routingFilters");
        assertEquals("76", filters.getString("active_id"));
        assertEquals(15, filters.getInt("size"));
    }

    @Test public void iqGetCandlesEnvelope() throws Exception {
        JSONObject gc = IqOptionFeed.buildGetCandles(1, 1793334000L);
        assertEquals("sendMessage", gc.getString("name"));
        JSONObject msg = gc.getJSONObject("msg");
        assertEquals("get-candles", msg.getString("name"));
        assertEquals("2.0", msg.getString("version"));
        JSONObject body = msg.getJSONObject("body");
        assertEquals(1, body.getInt("active_id"));
        assertEquals(60, body.getInt("size"));
        assertEquals(1793334000L, body.getLong("to"));
        assertEquals(130, body.getInt("count"));
    }

    @Test public void iqAuthenticateEnvelope() throws Exception {
        JSONObject auth = IqOptionFeed.buildAuthenticate("SSID123");
        assertEquals("authenticate", auth.getString("name"));
        assertEquals("SSID123", auth.getJSONObject("msg").getString("ssid"));
        assertEquals(3, auth.getJSONObject("msg").getInt("protocol"));
    }

    @Test public void iqParseCandleNormalises() throws Exception {
        JSONObject raw = new JSONObject();
        raw.put("from", 1793334000L); raw.put("to", 1793334015L);
        raw.put("open", 1.1000); raw.put("close", 1.1010);
        raw.put("min", 0.9990); raw.put("max", 1.1015);
        IqOptionFeed.CandlePoint p = IqOptionFeed.parseCandle(raw);
        assertTrue(p != null);
        assertEquals(1793334000L, p.ts);
        assertEquals(1.1015, p.high, 1e-9);   // max -> high
        assertEquals(0.9990, p.low, 1e-9);    // min -> low
        assertEquals(1.1010, p.close, 1e-9);
        JSONObject bad = new JSONObject();
        bad.put("from", "x");
        assertNull(IqOptionFeed.parseCandle(bad));
        assertNull(IqOptionFeed.parseCandle(null));
    }

    @Test public void iqParseBalances() throws Exception {
        JSONArray arr = new JSONArray();
        JSONObject real = new JSONObject();
        real.put("id", 111); real.put("type", 1); real.put("amount", 250.5);
        JSONObject practice = new JSONObject();
        practice.put("id", 222); practice.put("type", 4); practice.put("amount", 10000);
        arr.put(real); arr.put(practice);
        IqOptionFeed.Balances b = IqOptionFeed.parseBalances(arr);
        assertEquals(250.5, b.real, 1e-9);
        assertEquals(10000.0, b.practice, 1e-9);
        assertEquals(222, ((Number) b.practiceId).intValue());
    }

    @Test public void iqSsidFromLogin() throws Exception {
        assertEquals("ABC123xyz", IqOptionFeed.ssidFromLogin(200,
                Arrays.asList("ssid=ABC123xyz; Path=/; HttpOnly"), ""));
        assertEquals("JSONSSID", IqOptionFeed.ssidFromLogin(200,
                new ArrayList<>(), "{\"data\": {\"ssid\": \"JSONSSID\"}}"));
        boolean threw = false;
        try {
            IqOptionFeed.ssidFromLogin(401, new ArrayList<>(), "{\"message\":\"bad\"}");
        } catch (IqOptionFeed.IqError e) {
            threw = true;
        }
        assertTrue(threw);
    }

    /** Scripted fake websocket link (mirrors the Python session test). */
    static class FakeWsLink implements IqOptionFeed.WsLink {
        final java.util.Queue<String> incoming = new java.util.ArrayDeque<>();
        final List<JSONObject> sent = new ArrayList<>();
        IqOptionFeed feed;
        @Override public void connect() {}
        @Override public void send(String text) {
            try { sent.add(new JSONObject(text)); } catch (Exception e) { throw new RuntimeException(e); }
        }
        @Override public String recv(long timeoutMs) {
            String s = incoming.poll();
            if (s != null) return s;
            if (feed != null) feed.stop();
            return null;
        }
        @Override public void close() {}
    }

    @Test public void iqSessionWithFakeLink() throws Exception {
        // history candles (size-60 shape, like the "candles" answer)
        JSONArray hist = new JSONArray();
        for (int i = 0; i < 5; i++) {
            JSONObject c = new JSONObject();
            c.put("from", B + i * 60L); c.put("to", B + (i + 1) * 60L);
            c.put("open", 1.0); c.put("close", 1.0 + i * 0.001);
            c.put("min", 0.999); c.put("max", 1.002);
            hist.put(c);
        }
        int eurActive = IqOptionFeed.activeFor("EURUSD", IqOptionFeed.utcNow()).id;
        JSONObject live = new JSONObject();
        live.put("active_id", eurActive); live.put("size", 15);
        live.put("from", B + 7200); live.put("to", B + 7215);
        live.put("open", 1.1000); live.put("close", 1.1010);
        live.put("min", 0.9990); live.put("max", 1.1015);
        JSONObject liveWrap = new JSONObject();
        liveWrap.put("name", "candle-generated"); liveWrap.put("msg", live);

        JSONArray bals = new JSONArray();
        JSONObject br = new JSONObject(); br.put("id", 111); br.put("type", 1); br.put("amount", 250.5);
        JSONObject bp = new JSONObject(); bp.put("id", 222); bp.put("type", 4); bp.put("amount", 10000);
        bals.put(br); bals.put(bp);
        JSONObject balWrap = new JSONObject(); balWrap.put("name", "balances"); balWrap.put("msg", bals);
        JSONObject histWrap = new JSONObject();
        histWrap.put("name", "candles");
        JSONObject histMsg = new JSONObject(); histMsg.put("candles", hist);
        histWrap.put("msg", histMsg);
        JSONObject authWrap = new JSONObject();
        authWrap.put("name", "authenticated"); authWrap.put("msg", true);
        JSONObject syncWrap = new JSONObject();
        syncWrap.put("name", "timeSync"); syncWrap.put("msg", (B + 3600) * 1000L);

        FakeWsLink fake = new FakeWsLink();
        fake.incoming.add(syncWrap.toString());
        fake.incoming.add(authWrap.toString());
        fake.incoming.add(balWrap.toString());
        fake.incoming.add(histWrap.toString());   // EURUSD warmup
        fake.incoming.add(histWrap.toString());   // GBPUSD warmup
        fake.incoming.add(liveWrap.toString());

        final List<String> gotCodes = new ArrayList<>();
        final List<JSONObject> gotPoints = new ArrayList<>();
        final List<Double[]> gotBals = new ArrayList<>();
        IqOptionFeed feed = new IqOptionFeed("a@b.c", "pw",
                Arrays.asList("EURUSD", "GBPUSD"), true,
                (code, point) -> { gotCodes.add(code); gotPoints.add(point); },
                text -> {},
                (p, r) -> gotBals.add(new Double[]{p, r}),
                (e, p) -> "TESTSSID",
                () -> fake);
        fake.feed = feed;
        feed.run();

        assertEquals("authenticate", fake.sent.get(0).getString("name"));
        assertEquals("TESTSSID", fake.sent.get(0).getJSONObject("msg").getString("ssid"));
        int subscribes = 0, unsubscribes = 0;
        boolean changeBal = false;
        for (JSONObject m : fake.sent) {
            String n = m.getString("name");
            if (n.equals("subscribeMessage")) subscribes++;
            if (n.equals("unsubscribeMessage")) unsubscribes++;
            if (n.equals("api_profile_changebalance")
                    && m.getJSONObject("msg").getInt("balance_id") == 222) changeBal = true;
        }
        assertEquals(2, subscribes);
        assertEquals(2, unsubscribes);
        assertTrue(changeBal);   // PRACTICE balance (id 222) selected
        assertEquals(1, gotBals.size());
        assertEquals(10000.0, gotBals.get(0)[0], 1e-9);
        assertEquals(250.5, gotBals.get(0)[1], 1e-9);
        int warmupPoints = 0, livePoints = 0;
        for (int i = 0; i < gotPoints.size(); i++) {
            if (gotPoints.get(i).has("price")) warmupPoints++;
            if (gotPoints.get(i).has("open")) {
                livePoints++;
                assertEquals("EURUSD", gotCodes.get(i));
                assertEquals(1.1015, gotPoints.get(i).getDouble("high"), 1e-9);
            }
        }
        assertEquals(10, warmupPoints);   // 5 history candles x 2 assets
        assertEquals(1, livePoints);
    }

    @Test public void iqEngineIntegrationStampsSource() throws Exception {
        Map<String, String> assets = new LinkedHashMap<>();
        assets.put("EURUSD", "EUR/USD");
        Harness h = makeEngineSource(strat("perfect"), tfs(60), 85.0, false, false, 0,
                "iqoption", assets);
        JSONObject c1 = new JSONObject();
        c1.put("from", B); c1.put("open", 1.0000); c1.put("close", 1.00095);
        c1.put("min", 0.99990); c1.put("max", 1.00100);
        IqOptionFeed.CandlePoint p1 = IqOptionFeed.parseCandle(c1);
        h.eng.feed("EURUSD", p1.toJson());
        JSONObject c2 = new JSONObject();
        c2.put("from", B + 60); c2.put("open", 1.00095); c2.put("close", 1.00120);
        c2.put("min", 1.00090); c2.put("max", 1.00150);
        h.eng.feed("EURUSD", IqOptionFeed.parseCandle(c2).toJson());
        h.eng.feed("EURUSD", tick(B + 120, 1.00120));
        assertTrue(h.sigs.size() >= 1);
        assertEquals("iqoption", h.sigs.get(0).source);
        assertEquals("IQ Option", h.sigs.get(0).sourceLabel());
        assertTrue(h.eng.statsFor("iqoption", "perfect").total >= 1);
    }
}
