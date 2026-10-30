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
 * Flow: connect -> login -> balances -> depth/follow (all selected
 * assets) -> depth events feed the SignalsEngine -> signals / results /
 * blocked notes stream back to the UI. The engine builds BOTH the 30s
 * and the 1m candles from the same tick stream, like the Windows EXE.
 */
public class SignalBotEngine {

    public static class Config {
        public Context context;
        public String email, password;
        public boolean isDemo = true;
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
        if (worker != null) worker.interrupt();
        for (String key : cfg.assetMap.keySet()) client.unfollowDepth(key);
        client.disconnect();
    }

    public boolean isRunning() { return running; }

    /** The live engine (for stats reads); null before the session starts. */
    public SignalsEngine engine() { return engine; }

    private void loop() {
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
}
