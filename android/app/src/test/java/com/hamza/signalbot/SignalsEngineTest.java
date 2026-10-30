package com.hamza.signalbot;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
}
