package com.hamza.signalbot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Signal engine for Hamza Signal Bot — faithful Java port of the Python
 * signals_engine.py used by the Windows EXE (same brain, same rules).
 *
 * Strategies (each toggleable; PERFECT CANDLE is the core "Chinese method"):
 *   perfect   - candle body >= threshold % of its high-low range (default
 *               85%) -> signal the NEXT candle in the same direction.
 *   engulfing - bullish/bearish engulfing pair at candle close -> signal
 *               the next candle in the engulfing candle's direction.
 *   pinbar    - pin bar / hammer / shooting star: one long wick (>= 60% of
 *               range and >= 2x body) rejecting prices, tiny opposite wick
 *               -> signal the next candle AWAY from the rejection
 *               (hammer -> CALL, shooting star -> PUT).
 *
 * Filters (toggleable):
 *   trend - EMA(20/50): only allow signals in the trend direction.
 *           (With 20-49 closes, close-vs-EMA20 is used; below that the
 *           trend is "unknown" and nothing is blocked.)
 *   rsi   - RSI(14): block CALL when RSI >= 70 (overbought), block PUT
 *           when RSI <= 30 (oversold).
 *
 * Strength grade: 1 point for the pattern itself, +1 if the EMA trend
 * agrees, +1 if RSI sits on the signal's side of 50, +1 if 2+ strategies
 * fire on the same candle (confluence). >= 3 points = "STRONG".
 *
 * Results: after the signalled next candle closes, its direction is
 * compared with the signal -> WIN / LOSS / DRAW. Stats are kept overall,
 * per strategy kind, AND per (source, kind) — v1.1 adds a feed "source"
 * stamp ("quotex" vs "realmarket") so the demo record can show which
 * feed actually performs better; v1.2 adds "iqoption" the same way.
 *
 * NO orders are placed anywhere — the bot is signal-only by design.
 *
 * Feed format: {@code feed(assetKey, data)} normalises raw Quotex
 * depth-stream payloads (JSONObject / JSONArray) into price points
 * (ts_seconds, price): objects with OHLC, objects with a tick price,
 * nested objects, and arrays (including [timestamp, price] pairs) are
 * all tolerated. Candles are built by wall-clock bucket (ts - ts % tf).
 */
public class SignalsEngine {

    public static final Map<String, String> KIND_LABELS = new LinkedHashMap<>();
    static {
        KIND_LABELS.put("perfect", "Perfect Candle");
        KIND_LABELS.put("engulfing", "Engulfing");
        KIND_LABELS.put("pinbar", "Pin Bar");
    }

    public static final Map<String, String> SOURCE_LABELS = new LinkedHashMap<>();
    static {
        SOURCE_LABELS.put("quotex", "Quotex");
        SOURCE_LABELS.put("realmarket", "Real Market");
        SOURCE_LABELS.put("iqoption", "IQ Option");
    }

    // ── data classes ──────────────────────────────────────────────────

    public static class Candle {
        public final long start;   // bucket start, unix seconds
        public double open, high, low, close;

        public Candle(long start, double open, double high, double low, double close) {
            this.start = start; this.open = open; this.high = high;
            this.low = low; this.close = close;
        }

        public double range() { return high - low; }
        public double body() { return Math.abs(close - open); }

        public double bodyPct() {
            if (range() <= 0) return 0.0;
            return 100.0 * body() / range();
        }

        public double upperWick() { return high - Math.max(open, close); }
        public double lowerWick() { return Math.min(open, close) - low; }

        /** "call" / "put" / null (doji). */
        public String direction() {
            if (close > open) return "call";
            if (close < open) return "put";
            return null;
        }
    }

    public static class Ohlc {
        public final double open, high, low, close;
        public Ohlc(double open, double high, double low, double close) {
            this.open = open; this.high = high; this.low = low; this.close = close;
        }
    }

    public static class Point {
        public final long ts; public final double price; public final Ohlc ohlc;
        public Point(long ts, double price, Ohlc ohlc) {
            this.ts = ts; this.price = price; this.ohlc = ohlc;
        }
    }

    public static class Signal {
        public final String asset;      // display name, e.g. "EUR/USD OTC"
        public final String assetKey;   // stream key, e.g. "EURUSD_otc"
        public final int timeframe;     // seconds: 30 or 60
        public final String direction;  // "call" or "put"
        public final String kind;       // "perfect" / "engulfing" / "pinbar"
        public final long candleStart;  // start of the triggering candle
        public final long issuedAt;     // start of target candle == candle close
        public final int strength;      // agreement score
        public final String grade;      // "STRONG" / "NORMAL" / "LIGHT"
        public final String detail;     // e.g. "body 91%" / "wick 74%"
        public final String source;     // feed that produced it: "quotex" / "realmarket"
        public String result;           // "WIN" / "LOSS" / "DRAW" or null
        public Double resultClose;

        public Signal(String asset, String assetKey, int timeframe, String direction,
                      String kind, long candleStart, long issuedAt, int strength,
                      String grade, String detail) {
            this(asset, assetKey, timeframe, direction, kind, candleStart, issuedAt,
                    strength, grade, detail, "quotex");
        }

        public Signal(String asset, String assetKey, int timeframe, String direction,
                      String kind, long candleStart, long issuedAt, int strength,
                      String grade, String detail, String source) {
            this.asset = asset; this.assetKey = assetKey; this.timeframe = timeframe;
            this.direction = direction; this.kind = kind; this.candleStart = candleStart;
            this.issuedAt = issuedAt; this.strength = strength; this.grade = grade;
            this.detail = detail; this.source = source;
        }

        public long targetStart() { return candleStart + timeframe; }

        public String kindLabel() {
            String l = KIND_LABELS.get(kind);
            return l != null ? l : kind;
        }

        public String sourceLabel() {
            String l = SOURCE_LABELS.get(source);
            return l != null ? l : source;
        }
    }

    public static class Stats {
        public int total, wins, losses, draws;

        public int decided() { return wins + losses; }

        /** Win % over decided (non-draw) results, or null when none decided. */
        public Double winPct() {
            if (decided() == 0) return null;
            return 100.0 * wins / decided();
        }

        public void record(String result) {
            total += 1;
            if ("WIN".equals(result)) wins += 1;
            else if ("LOSS".equals(result)) losses += 1;
            else draws += 1;
        }
    }

    // ── indicators (pure) ─────────────────────────────────────────────

    /** Last EMA value, or null when there is not enough data. */
    public static Double emaLast(List<Double> values, int period) {
        if (values.size() < period) return null;
        double k = 2.0 / (period + 1);
        double avg = 0;
        for (int i = 0; i < period; i++) avg += values.get(i);
        avg /= period;
        for (int i = period; i < values.size(); i++)
            avg = values.get(i) * k + avg * (1 - k);
        return avg;
    }

    /** Last RSI (Wilder), or null when there is not enough data. */
    public static Double rsiLast(List<Double> closes, int period) {
        if (closes.size() < period + 1) return null;
        double avgGain = 0, avgLoss = 0;
        for (int i = 1; i <= period; i++) {
            double d = closes.get(i) - closes.get(i - 1);
            avgGain += Math.max(d, 0.0);
            avgLoss += Math.max(-d, 0.0);
        }
        avgGain /= period;
        avgLoss /= period;
        for (int i = period + 1; i < closes.size(); i++) {
            double d = closes.get(i) - closes.get(i - 1);
            avgGain = (avgGain * (period - 1) + Math.max(d, 0.0)) / period;
            avgLoss = (avgLoss * (period - 1) + Math.max(-d, 0.0)) / period;
        }
        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    public static Double rsiLast(List<Double> closes) { return rsiLast(closes, 14); }

    /**
     * 'call' (uptrend) / 'put' (downtrend) / null (unknown).
     * Full mode (>= 50 closes): EMA20 vs EMA50. Early mode (20-49 closes):
     * close vs EMA20. Below 20 closes the trend is unknown.
     */
    public static String trendDirection(List<Double> closes) {
        if (closes.size() >= 50) {
            Double ef = emaLast(closes, 20);
            Double es = emaLast(closes, 50);
            if (ef == null || es == null || ef.doubleValue() == es.doubleValue()) return null;
            return ef > es ? "call" : "put";
        }
        if (closes.size() >= 20) {
            Double e = emaLast(closes, 20);
            if (e == null || closes.get(closes.size() - 1).doubleValue() == e.doubleValue())
                return null;
            return closes.get(closes.size() - 1) > e ? "call" : "put";
        }
        return null;
    }

    // ── pattern detectors (pure) ──────────────────────────────────────

    /** Bullish/bearish engulfing on bodies; returns direction or null. */
    public static String engulfingDirection(Candle prev, Candle cur) {
        if (prev == null) return null;
        String pd = prev.direction(), cd = cur.direction();
        if (pd == null || cd == null) return null;
        if (pd.equals("put") && cd.equals("call")) {
            if (cur.open <= prev.close && cur.close >= prev.open) return "call";
        }
        if (pd.equals("call") && cd.equals("put")) {
            if (cur.open >= prev.close && cur.close <= prev.open) return "put";
        }
        return null;
    }

    /**
     * Hammer -> 'call', shooting star -> 'put', else null.
     * Long wick must be >= 60% of range and >= 2x the body; the opposite
     * wick must be <= 15% of range; body must be non-zero.
     */
    public static String pinbarDirection(Candle c) {
        if (c.range() <= 0 || c.body() <= 0) return null;
        double lw = c.lowerWick(), uw = c.upperWick(), b = c.body(), r = c.range();
        if (lw >= 0.60 * r && lw >= 2 * b && uw <= 0.15 * r) return "call";
        if (uw >= 0.60 * r && uw >= 2 * b && lw <= 0.15 * r) return "put";
        return null;
    }

    // ── payload normalisation ─────────────────────────────────────────

    /** Number-or-numeric-string to double, or null (like Python's float()). */
    static Double toDouble(Object v) {
        if (v == null || v == JSONObject.NULL) return null;
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try { return Double.parseDouble(((String) v).trim()); }
            catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    static Long normTs(Object v) {
        Double d = toDouble(v);
        if (d == null) return null;
        double ts = d;
        if (ts >= 1e14) ts /= 1e6;
        else if (ts >= 1e11) ts /= 1e3;
        if (ts < 1e8) return null;
        return (long) ts;
    }

    /** Extract (ts_seconds, price, ohlc_or_null) points from a payload. */
    public static List<Point> extractPoints(Object data) {
        List<Point> points = new ArrayList<>();
        walk(data, points);
        return points;
    }

    private static void walk(Object node, List<Point> points) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            Object tsVal = o.has("timestamp") ? o.opt("timestamp") : o.opt("time");
            Long ts = normTs(tsVal);
            Ohlc ohlc = null;
            Double price = null;
            if (o.has("open") && o.has("high") && o.has("low") && o.has("close")) {
                Double oo = toDouble(o.opt("open")), hh = toDouble(o.opt("high"));
                Double ll = toDouble(o.opt("low")), cc = toDouble(o.opt("close"));
                if (oo != null && hh != null && ll != null && cc != null) {
                    ohlc = new Ohlc(oo, hh, ll, cc);
                    price = cc;
                }
            }
            if (price == null && o.has("price")) price = toDouble(o.opt("price"));
            if (price == null && o.has("close")) price = toDouble(o.opt("close"));
            if (ts != null && price != null && price > 0) {
                points.add(new Point(ts, price, ohlc));
                return;
            }
            for (String key : new String[]{"data", "candle", "candles", "ticks", "depth"}) {
                if (o.has(key)) walk(o.opt(key), points);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            if (a.length() == 2 && a.opt(0) instanceof Number && a.opt(1) instanceof Number) {
                Long ts = normTs(a.opt(0));
                double p = ((Number) a.opt(1)).doubleValue();
                if (ts != null && p > 0) {
                    points.add(new Point(ts, p, null));
                    return;
                }
            }
            for (int i = 0; i < a.length(); i++) walk(a.opt(i), points);
        }
    }

    // ── candle builder ────────────────────────────────────────────────

    public static class CandleBuilder {
        public interface OnClose { void onClose(Candle c); }

        public final int tf;
        private final OnClose onClose;
        public Candle current;

        public CandleBuilder(int timeframe, OnClose onClose) {
            this.tf = timeframe;
            this.onClose = onClose;
        }

        public void add(long ts, double price, Ohlc ohlc) {
            long bucket = ts - (ts % tf);
            Candle cur = current;
            if (cur == null) {
                current = newCandle(bucket, price, ohlc);
                return;
            }
            if (bucket == cur.start) {
                cur.high = Math.max(cur.high, price);
                cur.low = Math.min(cur.low, price);
                cur.close = price;
                if (ohlc != null) {
                    cur.high = Math.max(cur.high, ohlc.high);
                    cur.low = Math.min(cur.low, ohlc.low);
                    cur.close = ohlc.close;
                }
                return;
            }
            if (bucket > cur.start) {
                onClose.onClose(cur);
                current = newCandle(bucket, price, ohlc);
            }
            // older point (late/out-of-order): ignore
        }

        private static Candle newCandle(long bucket, double price, Ohlc ohlc) {
            if (ohlc != null) return new Candle(bucket, ohlc.open, ohlc.high, ohlc.low, ohlc.close);
            return new Candle(bucket, price, price, price, price);
        }
    }

    // ── signal engine ─────────────────────────────────────────────────

    public interface OnSignal { void onSignal(Signal s); }
    public interface OnResult { void onResult(Signal s); }
    public interface OnBlocked { void onBlocked(String kind, String direction, String why); }
    public interface OnCandle { void onCandle(String assetKey, int tf, Candle c); }

    public final Map<String, String> assets;      // asset_key -> display name
    public final List<Integer> timeframes;
    public final double thresholdPct;
    public final Set<String> strategies;
    public final boolean trendFilter;
    public final boolean rsiFilter;
    public final double rsiOb, rsiOs;
    public final int maxSignals;                  // 0 = unlimited (issued count)
    public final String source;                   // feed stamp for stats/history

    public OnSignal onSignal;
    public OnResult onResult;
    public OnBlocked onBlocked;
    public OnCandle onCandle;

    private final Map<String, CandleBuilder> builders = new LinkedHashMap<>();
    private final Map<String, List<Double>> closesMap = new LinkedHashMap<>();
    private final Map<String, Candle> prevCandle = new LinkedHashMap<>();
    private final Map<String, List<Signal>> pending = new LinkedHashMap<>();
    public final List<Signal> history = new ArrayList<>();
    public final Stats stats = new Stats();
    public final Map<String, Stats> statsByKind = new LinkedHashMap<>();
    /** Per (source, kind) stats; key = source + "|" + kind. */
    public final Map<String, Stats> statsBySourceKind = new LinkedHashMap<>();
    public int issued = 0;

    public SignalsEngine(Map<String, String> assets, List<Integer> timeframes,
                         double thresholdPct, Set<String> strategies,
                         boolean trendFilter, boolean rsiFilter,
                         double rsiOverbought, double rsiOversold, int maxSignals) {
        this(assets, timeframes, thresholdPct, strategies, trendFilter, rsiFilter,
                rsiOverbought, rsiOversold, maxSignals, "quotex");
    }

    public SignalsEngine(Map<String, String> assets, List<Integer> timeframes,
                         double thresholdPct, Set<String> strategies,
                         boolean trendFilter, boolean rsiFilter,
                         double rsiOverbought, double rsiOversold, int maxSignals,
                         String source) {
        this.assets = new LinkedHashMap<>(assets);
        this.timeframes = new ArrayList<>(timeframes);
        this.thresholdPct = thresholdPct;
        this.strategies = new LinkedHashSet<>(strategies);
        this.trendFilter = trendFilter;
        this.rsiFilter = rsiFilter;
        this.rsiOb = rsiOverbought;
        this.rsiOs = rsiOversold;
        this.maxSignals = maxSignals;
        this.source = source;
        for (String key : this.assets.keySet()) {
            for (int tf : this.timeframes) {
                final String k = key;
                final int t = tf;
                builders.put(streamKey(k, t), new CandleBuilder(t, c -> candleClosed(k, t, c)));
                closesMap.put(streamKey(k, t), new ArrayList<>());
                prevCandle.put(streamKey(k, t), null);
            }
        }
        for (String kind : KIND_LABELS.keySet()) {
            statsByKind.put(kind, new Stats());
            statsBySourceKind.put(sourceKindKey(source, kind), new Stats());
        }
    }

    /** Convenience constructor with the EXE's default RSI bounds (70/30). */
    public SignalsEngine(Map<String, String> assets, List<Integer> timeframes,
                         double thresholdPct, Set<String> strategies,
                         boolean trendFilter, boolean rsiFilter, int maxSignals) {
        this(assets, timeframes, thresholdPct, strategies, trendFilter, rsiFilter,
                70.0, 30.0, maxSignals);
    }

    /** Convenience constructor with default RSI bounds + a feed source stamp. */
    public SignalsEngine(Map<String, String> assets, List<Integer> timeframes,
                         double thresholdPct, Set<String> strategies,
                         boolean trendFilter, boolean rsiFilter, int maxSignals,
                         String source) {
        this(assets, timeframes, thresholdPct, strategies, trendFilter, rsiFilter,
                70.0, 30.0, maxSignals, source);
    }

    private static String streamKey(String assetKey, int tf) { return assetKey + "@" + tf; }

    public static String sourceKindKey(String source, String kind) { return source + "|" + kind; }

    /** Per-(source, kind) stats, or null when nothing was recorded for it. */
    public Stats statsFor(String source, String kind) {
        return statsBySourceKind.get(sourceKindKey(source, kind));
    }

    // -- input ----------------------------------------------------------

    public void feed(String assetKey, Object data) {
        if (!assets.containsKey(assetKey)) return;
        for (Point p : extractPoints(data)) {
            for (int tf : timeframes) {
                builders.get(streamKey(assetKey, tf)).add(p.ts, p.price, p.ohlc);
            }
        }
    }

    // -- internals ------------------------------------------------------

    private void candleClosed(String assetKey, int tf, Candle candle) {
        String stream = streamKey(assetKey, tf);
        if (onCandle != null) onCandle.onCandle(assetKey, tf, candle);

        // 1) Resolve pending signals whose target candle just closed.
        List<Signal> pend = pending.remove(stream);
        if (pend != null) {
            for (Signal sig : pend) {
                if (candle.start < sig.targetStart()) {
                    pending.computeIfAbsent(stream, k -> new ArrayList<>()).add(sig);
                    continue;
                }
                String actual = candle.direction();
                if (actual == null) sig.result = "DRAW";
                else if (actual.equals(sig.direction)) sig.result = "WIN";
                else sig.result = "LOSS";
                sig.resultClose = candle.close;
                stats.record(sig.result);
                statsByKind.get(sig.kind).record(sig.result);
                statsBySourceKind.computeIfAbsent(
                        sourceKindKey(sig.source, sig.kind), k -> new Stats()).record(sig.result);
                if (onResult != null) onResult.onResult(sig);
            }
        }

        // 2) Update indicator state with the just-closed candle.
        List<Double> closes = closesMap.get(stream);
        closes.add(candle.close);
        if (closes.size() > 300) closes.subList(0, closes.size() - 300).clear();
        String trend = trendDirection(closes);
        Double rsi = rsiLast(closes, 14);
        Candle prev = prevCandle.get(stream);
        prevCandle.put(stream, candle);

        // 3) Detect candidate signals from enabled strategies.
        List<String[]> candidates = new ArrayList<>();  // (kind, direction, detail)
        if (strategies.contains("perfect")) {
            String d = candle.direction();
            if (d != null && candle.bodyPct() >= thresholdPct) {
                candidates.add(new String[]{"perfect", d,
                        String.format(Locale.US, "body %.0f%%", candle.bodyPct())});
            }
        }
        if (strategies.contains("engulfing")) {
            String d = engulfingDirection(prev, candle);
            if (d != null) candidates.add(new String[]{"engulfing", d, "engulfing"});
        }
        if (strategies.contains("pinbar")) {
            String d = pinbarDirection(candle);
            if (d != null) {
                double wick = "call".equals(d) ? candle.lowerWick() : candle.upperWick();
                double pct = candle.range() > 0 ? 100.0 * wick / candle.range() : 0.0;
                candidates.add(new String[]{"pinbar", d,
                        String.format(Locale.US, "wick %.0f%%", pct)});
            }
        }

        // 4) Filters.
        List<String[]> surviving = new ArrayList<>();
        for (String[] cand : candidates) {
            String kind = cand[0], direction = cand[1];
            if (trendFilter && trend != null && !trend.equals(direction)) {
                if (onBlocked != null)
                    onBlocked.onBlocked(kind, direction, "trend filter (EMA trend ulta hai)");
                continue;
            }
            if (rsiFilter && rsi != null) {
                if (direction.equals("call") && rsi >= rsiOb) {
                    if (onBlocked != null)
                        onBlocked.onBlocked(kind, direction,
                                String.format(Locale.US, "RSI filter (RSI %.0f overbought)", rsi));
                    continue;
                }
                if (direction.equals("put") && rsi <= rsiOs) {
                    if (onBlocked != null)
                        onBlocked.onBlocked(kind, direction,
                                String.format(Locale.US, "RSI filter (RSI %.0f oversold)", rsi));
                    continue;
                }
            }
            surviving.add(cand);
        }

        // 5) Strength + issue.
        int confluence = surviving.size() >= 2 ? 1 : 0;
        for (String[] cand : surviving) {
            String kind = cand[0], direction = cand[1], detail = cand[2];
            if (maxSignals > 0 && issued >= maxSignals) return;
            int strength = 1 + confluence;
            if (trend != null && trend.equals(direction)) strength += 1;
            if (rsi != null && ((direction.equals("call") && rsi > 50) ||
                    (direction.equals("put") && rsi < 50))) strength += 1;
            String grade = strength >= 3 ? "STRONG" : (strength == 2 ? "NORMAL" : "LIGHT");
            Signal sig = new Signal(
                    assets.get(assetKey), assetKey, tf, direction, kind,
                    candle.start, candle.start + tf, strength, grade, detail, source);
            pending.computeIfAbsent(stream, k -> new ArrayList<>()).add(sig);
            history.add(sig);
            issued += 1;
            if (onSignal != null) onSignal.onSignal(sig);
        }
    }

    /** Strategy set helper: all three strategies. */
    public static Set<String> allStrategies() {
        return new LinkedHashSet<>(Arrays.asList("perfect", "engulfing", "pinbar"));
    }
}
