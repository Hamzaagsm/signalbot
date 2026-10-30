package com.hamza.signalbot;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Signal Bot session runner (counterpart of the Qx Bot's BotEngine,
 * but SIGNAL-ONLY: no stakes, no martingale, no orders — ever).
 *
 * Flow (Quotex source): connect -> login -> balances -> depth/follow
 * (all selected assets) -> depth events feed the SignalsEngine ->
 * signals / results / blocked notes stream back to the UI.
 *
 * v1.1 adds the Real Market source (EXE jaisa hi): Yahoo Finance
 * polling via RealMarketFeed — NO Quotex login at all; the same engine
 * gets the points, stamped source="realmarket". The engine builds BOTH
 * the 30s and the 1m candles from the same tick stream, like the EXE.
 *
 * v1.2 adds the IQ Option source: IqOptionFeed logs in with the user's
 * own IQ Option account (PRACTICE by default) and streams its candles
 * into the same engine, stamped source="iqoption" — Hamza's rule: the
 * bot works for IQ Option AND Quotex, not Quotex only.
 */
public class SignalBotEngine {

    public static class Config {
        public Context context;
        public String email, password;
        public boolean isDemo = true;
        /** "quotex" (default, v1.0 behaviour), "realmarket" (v1.1)
         *  or "iqoption" (v1.2 — email/password = IQ Option account). */
        public String source = "quotex";
        /** asset_key -> display name, in selection order. */
        public LinkedHashMap<String, String> assetMap = new LinkedHashMap<>();
        public List<Integer> timeframes = new ArrayList<>();
        public double thresholdPct = 85.0;
        public Set<String> strategies = SignalsEngine.allStrategies();
        public boolean trendFilter = true;
        public boolean rsiFilter = false;
        public int maxSignals = 0;   // 0 = unlimited
    }

    public interface Ui {
        void log(String msg);
        void status(String msg);
        void signal(SignalsEngine.Signal s);
        void result(SignalsEngine.Signal s);
        void blocked(String kind, String direction, String why);
        void stats(SignalsEngine engine);
        void balances(double demo, double live);
        void finished(String summary);
    }

    private final Config cfg;
    private final Ui ui;
    private final QuotexClient client;
    private volatile boolean running;
    private Thread worker;
    private SignalsEngine engine;
    private RealMarketFeed feed;
    private IqOptionFeed iqFeed;
    private final AtomicLong points = new AtomicLong();

    public SignalBotEngine(Config cfg, Ui ui) {
        this.cfg = cfg;
        this.ui = ui;
        this.client = new QuotexClient(cfg.context);
        client.setLogSink(ui::log);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        worker = new Thread(this::loop, "SignalBot");
        worker.start();
    }

    public synchronized void stop() {
        running = false;
        if (feed != null) feed.stop();
        if (iqFeed != null) iqFeed.stop();
        if (worker != null) worker.interrupt();
        if ("quotex".equals(cfg.source)) {
            for (String key : cfg.assetMap.keySet()) client.unfollowDepth(key);
            client.disconnect();
        }
    }

    public boolean isRunning() { return running; }

    /** The live engine (for stats reads); null before the session starts. */
    public SignalsEngine engine() { return engine; }

    private void loop() {
        if ("realmarket".equals(cfg.source)) {
            loopRealMarket();
            return;
        }
        if ("iqoption".equals(cfg.source)) {
            loopIqOption();
            return;
        }
        try {
            ui.log("Quotex se connect ho raha...");
            client.connect();

            QuotexClient.LoginResult lr = client.login(cfg.email, cfg.password, cfg.isDemo);
            ui.log("✅ Login OK (" + (cfg.isDemo ? "DEMO" : "REAL") + ")");
            ui.balances(lr.demoBalance, lr.liveBalance);
            ui.log(String.format(Locale.US, "Balance: DEMO $%.2f | REAL $%.2f (sirf maloomat — bot trade nahi lagata)",
                    lr.demoBalance, lr.liveBalance));

            engine = new SignalsEngine(cfg.assetMap, cfg.timeframes, cfg.thresholdPct,
                    cfg.strategies, cfg.trendFilter, cfg.rsiFilter, cfg.maxSignals);
            engine.onSignal = s -> { ui.signal(s); ui.stats(engine); };
            engine.onResult = s -> { ui.result(s); ui.stats(engine); };
            engine.onBlocked = (kind, dir, why) -> ui.blocked(kind, dir, why);

            client.subscribe("depth", this::onDepth);
            for (String key : cfg.assetMap.keySet()) client.followDepth(key);

            ui.status("⏳ Warmup: candles jama ho rahe hain — trend/RSI ko history chahiye…");
            ui.log("📡 Live: " + cfg.assetMap.size() + " assets x "
                    + cfg.timeframes + "s candles subscribe ho gaye.");
            ui.log("Note: Trend/RSI ko candles ki history chahiye — shuru me kuch waqt warmup lagega.");

            while (running) {
                Thread.sleep(500);
            }
            ui.log("Session khatam.");
        } catch (InterruptedException e) {
            ui.log("Session roka gaya.");
        } catch (Exception e) {
            ui.log("❌ Fatal: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            running = false;
            try { client.disconnect(); } catch (Exception ignored) {}
            SignalsEngine e = engine;
            String summary;
            if (e != null) {
                SignalsEngine.Stats st = e.stats;
                Double wp = st.winPct();
                summary = String.format(Locale.US,
                        "Session khatam: %d signals | WIN %d | LOSS %d | DRAW %d | Win%% %s",
                        st.total, st.wins, st.losses, st.draws,
                        wp == null ? "—" : String.format(Locale.US, "%.0f%%", wp));
            } else {
                summary = "Session khatam (engine start nahi hua).";
            }
            ui.finished(summary);
        }
    }

    private void onDepth(JSONObject d) {
        if (!running || engine == null) return;
        try {
            String asset = d.optString("asset", "");
            if (asset.isEmpty() && cfg.assetMap.size() == 1)
                asset = cfg.assetMap.keySet().iterator().next();
            if (!cfg.assetMap.containsKey(asset)) return;
            long n;
            synchronized (engine) {
                engine.feed(asset, d);
                n = points.incrementAndGet();
            }
            if (n % 50 == 1) {
                ui.status("✅ Live: stream chal raha hai (" + n
                        + " updates) — perfect candle ka intezar…");
            }
        } catch (Exception ignored) {}
    }

    // ── IQ Option mode (v1.2) ─────────────────────────────────────

    private void loopIqOption() {
        try {
            engine = new SignalsEngine(cfg.assetMap, cfg.timeframes, cfg.thresholdPct,
                    cfg.strategies, cfg.trendFilter, cfg.rsiFilter, cfg.maxSignals,
                    "iqoption");
            engine.onSignal = s -> { ui.signal(s); ui.stats(engine); };
            engine.onResult = s -> { ui.result(s); ui.stats(engine); };
            engine.onBlocked = (kind, dir, why) -> ui.blocked(kind, dir, why);

            iqFeed = new IqOptionFeed(cfg.email, cfg.password,
                    new ArrayList<>(cfg.assetMap.keySet()), cfg.isDemo,
                    (code, point) -> {
                        if (!running || engine == null) return;
                        try {
                            long n;
                            synchronized (engine) {
                                engine.feed(code, point);
                                n = points.incrementAndGet();
                            }
                            if (n % 50 == 1) {
                                ui.status("✅ IQ Option live: stream chal raha hai (" + n
                                        + " updates) — perfect candle ka intezar…");
                            }
                        } catch (Exception e) {
                            ui.log("⚠️ Data process error: " + e.getMessage());
                        }
                    },
                    text -> ui.log(text),
                    (practiceBal, realBal) -> {
                        if (practiceBal != null && realBal != null)
                            ui.balances(practiceBal, realBal);
                    });

            ui.status("📡 IQ Option: login ho raha hai…");
            ui.log("Note: Trend/RSI ko candles ki history chahiye — IQ history (warmup) pehle aati hai, phir live candles.");
            iqFeed.run();   // blocks until stop() / fatal login error
            ui.log("Session khatam.");
        } catch (Exception e) {
            ui.log("❌ Fatal: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            running = false;
            if (iqFeed != null) iqFeed.stop();
            ui.finished(sessionSummary());
        }
    }

    private String sessionSummary() {
        SignalsEngine e = engine;
        if (e == null) return "Session khatam (engine start nahi hua).";
        SignalsEngine.Stats st = e.stats;
        Double wp = st.winPct();
        return String.format(Locale.US,
                "Session khatam: %d signals | WIN %d | LOSS %d | DRAW %d | Win%% %s",
                st.total, st.wins, st.losses, st.draws,
                wp == null ? "—" : String.format(Locale.US, "%.0f%%", wp));
    }

    // ── Real Market mode (v1.1) ───────────────────────────────────────

    private void loopRealMarket() {
        try {
            engine = new SignalsEngine(cfg.assetMap, cfg.timeframes, cfg.thresholdPct,
                    cfg.strategies, cfg.trendFilter, cfg.rsiFilter, cfg.maxSignals,
                    "realmarket");
            engine.onSignal = s -> { ui.signal(s); ui.stats(engine); };
            engine.onResult = s -> { ui.result(s); ui.stats(engine); };
            engine.onBlocked = (kind, dir, why) -> ui.blocked(kind, dir, why);

            feed = new RealMarketFeed(new ArrayList<>(cfg.assetMap.keySet()),
                    (code, ts, price) -> {
                        if (!running || engine == null) return;
                        try {
                            JSONObject j = new JSONObject();
                            j.put("timestamp", ts);
                            j.put("price", price);
                            synchronized (engine) {
                                engine.feed(code, j);
                            }
                            points.incrementAndGet();
                        } catch (Exception e) {
                            ui.log("⚠️ Data process error: " + e.getMessage());
                        }
                    },
                    text -> ui.log(text));

            ui.log("📡 Real Market (Yahoo Finance) polling shuru — koi login nahi, koi account nahi, sirf signals.");
            ui.status("📡 Real Market: pehle data ka intezar…");
            ui.log("Note: Trend/RSI ko candles ki history chahiye — shuru me kuch waqt warmup lagega.");
            feed.start();

            long lastTick = 0;
            while (running) {
                Thread.sleep(250);
                long now = System.currentTimeMillis();
                if (now - lastTick >= 2000) {   // EXE ka status tick: har ~2s
                    lastTick = now;
                    rmStatusTick();
                }
            }
            ui.log("Session khatam.");
        } catch (InterruptedException e) {
            ui.log("Session roka gaya.");
        } catch (Exception e) {
            ui.log("❌ Fatal: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            running = false;
            if (feed != null) feed.stop();
            SignalsEngine e = engine;
            String summary;
            if (e != null) {
                SignalsEngine.Stats st = e.stats;
                Double wp = st.winPct();
                summary = String.format(Locale.US,
                        "Session khatam: %d signals | WIN %d | LOSS %d | DRAW %d | Win%% %s",
                        st.total, st.wins, st.losses, st.draws,
                        wp == null ? "—" : String.format(Locale.US, "%.0f%%", wp));
            } else {
                summary = "Session khatam (engine start nahi hua).";
            }
            ui.finished(summary);
        }
    }

    /** EXE _rm_status_tick ka mirror: source + last update + STALE + lag. */
    private void rmStatusTick() {
        RealMarketFeed f = feed;
        if (f == null || !running) return;
        Double lp = f.lastPoll();
        if (lp == null) {
            ui.status("📡 Real Market: pehle data ka intezar…");
        } else if (f.isStale(10.0, RealMarketFeed.nowSeconds())) {
            ui.status("⚠️ STALE — Real Market data ruk gaya hai! Last update "
                    + timeStr(lp.longValue()) + " — connection check karo");
        } else {
            Double lag = f.dataLag();
            String lagTxt = lag != null
                    ? String.format(Locale.US, " • data lag ~%.0fs", lag) : "";
            ui.status("📡 Real Market (Yahoo) • last update " + timeStr(lp.longValue())
                    + lagTxt + " • " + points.get() + " points — perfect candle ka intezar…");
        }
    }

    private static String timeStr(long unixSec) {
        return new java.text.SimpleDateFormat("HH:mm:ss", Locale.US)
                .format(new java.util.Date(unixSec * 1000));
    }
}
