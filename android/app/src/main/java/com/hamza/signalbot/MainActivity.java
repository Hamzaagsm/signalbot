package com.hamza.signalbot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Html;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Main screen: big signal banner, WIN/LOSS stats (overall + per
 * strategy), signal history, log. SIGNAL-ONLY — the app never trades.
 */
public class MainActivity extends Activity implements SignalBotEngine.Ui {

    private Spinner modeSp, sourceSp;
    private Button startBtn, stopBtn, settingsBtn;
    private TextView balDemoTv, balRealTv;
    private TextView bannerTv, statsTv, kindStatsTv, statusTv, logTv, histTv;
    private ScrollView logScroll;
    private SignalBotEngine engine;
    private boolean realConfirmed = false;
    private String currentSource = "quotex";
    private double lastDemoBal = -1, lastRealBal = -1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0a0a14"));
        root.setPadding(28, 28, 28, 28);

        TextView title = new TextView(this);
        title.setText("🔔 HAMZA SIGNAL BOT");
        title.setTextSize(22);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setTextColor(Color.parseColor("#00d4aa"));
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        TextView ver = new TextView(this);
        ver.setText("Perfect Candle Signals v1.2 (30s / 1m) — SIGNAL ONLY, no auto trade");
        ver.setTextColor(Color.parseColor("#666677"));
        ver.setTextSize(11);
        ver.setGravity(Gravity.CENTER);
        root.addView(ver);
        root.addView(spacer(8));

        // ---- signal source (v1.1) ----
        LinearLayout srcRow = new LinearLayout(this);
        srcRow.setOrientation(LinearLayout.HORIZONTAL);
        srcRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView srcLbl = new TextView(this);
        srcLbl.setText("Signal Source:");
        srcLbl.setTextColor(Color.WHITE);
        srcRow.addView(srcLbl, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        sourceSp = new Spinner(this);
        ArrayAdapter<String> sa = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"Quotex Feed", "IQ Option Feed", "Real Market Feed"});
        sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sourceSp.setAdapter(sa);
        srcRow.addView(sourceSp, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 2));
        root.addView(srcRow);
        TextView srcNote = new TextView(this);
        srcNote.setText("Real Market = asal forex data (Yahoo) — login NAHI chahiye • IQ Option = apna IQ login (PRACTICE default)");
        srcNote.setTextColor(Color.parseColor("#666677"));
        srcNote.setTextSize(11);
        srcNote.setGravity(Gravity.CENTER);
        root.addView(srcNote);
        root.addView(spacer(8));

        // ---- balance card ----
        LinearLayout balCard = new LinearLayout(this);
        balCard.setOrientation(LinearLayout.HORIZONTAL);
        balCard.setBackgroundColor(Color.parseColor("#111122"));
        balCard.setPadding(20, 14, 20, 14);
        balDemoTv = bigBal("DEMO\n$—");
        balRealTv = bigBal("REAL\n$—");
        balCard.addView(balDemoTv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        balCard.addView(balRealTv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(balCard);
        root.addView(spacer(10));

        // ---- signal banner ----
        bannerTv = new TextView(this);
        bannerTv.setText("— signal ka intezar… —");
        bannerTv.setTextSize(17);
        bannerTv.setTypeface(bannerTv.getTypeface(), Typeface.BOLD);
        bannerTv.setTextColor(Color.parseColor("#888888"));
        bannerTv.setBackgroundColor(Color.parseColor("#1a1a2e"));
        bannerTv.setGravity(Gravity.CENTER);
        bannerTv.setPadding(20, 22, 20, 22);
        root.addView(bannerTv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(spacer(8));

        // ---- stats ----
        statsTv = new TextView(this);
        statsTv.setText("Signals: 0 | WIN: 0 | LOSS: 0 | Win%: —");
        statsTv.setTextSize(14);
        statsTv.setTypeface(statsTv.getTypeface(), Typeface.BOLD);
        statsTv.setTextColor(Color.parseColor("#00d4aa"));
        statsTv.setGravity(Gravity.CENTER);
        root.addView(statsTv);
        kindStatsTv = new TextView(this);
        kindStatsTv.setText("");
        kindStatsTv.setTextSize(11);
        kindStatsTv.setTextColor(Color.parseColor("#aaaaaa"));
        kindStatsTv.setGravity(Gravity.CENTER);
        root.addView(kindStatsTv);
        root.addView(spacer(10));

        // ---- mode + settings ----
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView modeLbl = new TextView(this);
        modeLbl.setText("Mode:");
        modeLbl.setTextColor(Color.WHITE);
        modeRow.addView(modeLbl, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        modeSp = new Spinner(this);
        ArrayAdapter<String> ma = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"DEMO", "REAL (Asli paisa!)"});
        ma.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSp.setAdapter(ma);
        modeRow.addView(modeSp, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 2));
        settingsBtn = new Button(this);
        settingsBtn.setText("⚙️ SETTINGS");
        modeRow.addView(settingsBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 2));
        root.addView(modeRow);
        root.addView(spacer(8));

        // ---- buttons ----
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER);
        startBtn = new Button(this);
        startBtn.setText("▶ START SIGNALS");
        startBtn.setTextSize(17);
        startBtn.setBackgroundColor(Color.parseColor("#00d4aa"));
        startBtn.setTextColor(Color.BLACK);
        stopBtn = new Button(this);
        stopBtn.setText("⏹ STOP");
        stopBtn.setTextSize(17);
        stopBtn.setBackgroundColor(Color.parseColor("#e94560"));
        stopBtn.setTextColor(Color.WHITE);
        stopBtn.setEnabled(false);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        bp.setMargins(8, 0, 8, 0);
        btns.addView(startBtn, bp);
        btns.addView(stopBtn, bp);
        root.addView(btns);
        root.addView(spacer(8));

        statusTv = new TextView(this);
        statusTv.setTextColor(Color.parseColor("#ffaa00"));
        statusTv.setGravity(Gravity.CENTER);
        root.addView(statusTv);
        root.addView(spacer(6));

        root.addView(sectionLabel("📜 Log:"));
        logTv = new TextView(this);
        logTv.setTextColor(Color.parseColor("#00ff88"));
        logTv.setBackgroundColor(Color.parseColor("#111122"));
        logTv.setPadding(16, 16, 16, 16);
        logTv.setTextSize(12);
        logScroll = new ScrollView(this);
        logScroll.addView(logTv);
        root.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 2f));
        root.addView(spacer(6));

        root.addView(sectionLabel("🔔 Signal History:"));
        histTv = new TextView(this);
        histTv.setTextColor(Color.WHITE);
        histTv.setTextSize(12);
        histTv.setText("—");
        ScrollView hs = new ScrollView(this);
        hs.addView(histTv);
        root.addView(hs, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(spacer(6));

        TextView footer = new TextView(this);
        footer.setText("Signals are not a guarantee of profit. Test on demo first.");
        footer.setTextColor(Color.parseColor("#e94560"));
        footer.setTextSize(11);
        footer.setGravity(Gravity.CENTER);
        root.addView(footer);

        setContentView(new ScrollView(this) {{ addView(root); }});

        log("Signal Bot v1.2 tayyar! Pehle DEMO par test karo. ⚠️");
        log("Perfect candle = body candle ke kul range ka diya gaya % ya zyada. Engulfing aur Pin Bar bhi on hain (Settings).");
        log("Source upar se chuno: Quotex Feed (OTC), IQ Option Feed (login, PRACTICE default) ya Real Market Feed (asal market, login nahi).");

        // Login screen se koi source chun kar aaye to wahi pehle se select ho.
        String intentSource = getIntent().getStringExtra("source");
        if ("realmarket".equals(intentSource) || "iqoption".equals(intentSource)) {
            currentSource = intentSource;
            sourceSp.setSelection("iqoption".equals(intentSource) ? 1 : 2);
            applySourceUi();
        }
        sourceSp.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                String src = pos == 1 ? "iqoption" : (pos == 2 ? "realmarket" : "quotex");
                if (!src.equals(currentSource)) {
                    currentSource = src;
                    applySourceUi();
                    if (src.equals("realmarket")) {
                        log("Real Market mode: kisi broker ka login NAHI chahiye — signals asal market data (Yahoo Finance) se ayenge.");
                    } else if (src.equals("iqoption")) {
                        log("IQ Option mode: signals IQ Option candles se ayenge — IQ Option login (neeche wali screen) lazmi hai, Balance DEMO = PRACTICE.");
                    } else {
                        log("Quotex mode: signals Quotex OTC candles se ayenge — login lazmi hai.");
                    }
                }
            }
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        modeSp.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (pos == 1 && !realConfirmed) askRealConfirm();
            }
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        settingsBtn.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        startBtn.setOnClickListener(v -> startBot());
        stopBtn.setOnClickListener(v -> stopBot());

        loadBalances();
    }

    private boolean isRealMarket() { return "realmarket".equals(currentSource); }
    private boolean isIqOption() { return "iqoption".equals(currentSource); }

    /** EXE _apply_source_ui ka mirror: Real Market me login/mode band. */
    private void applySourceUi() {
        boolean rm = isRealMarket();
        if (modeSp != null) modeSp.setEnabled(!rm);
    }

    /** Balance card ke liye halki si live check (DEMO login se). */
    private void loadBalances() {
        SharedPreferences p = getSharedPreferences(LoginActivity.PREFS, MODE_PRIVATE);
        final String email = p.getString("email", "");
        final String pw = p.getString("password", "");
        if (email.isEmpty() || pw.isEmpty()) return;
        new Thread(() -> {
            try {
                QuotexClient c = new QuotexClient(this);
                c.connect();
                QuotexClient.LoginResult lr = c.login(email, pw, true);
                lastDemoBal = lr.demoBalance;
                lastRealBal = lr.liveBalance;
                double[] bal = c.getBalances();
                if (bal != null) {
                    if (bal[0] >= 0) lastDemoBal = bal[0];
                    if (bal[1] >= 0) lastRealBal = bal[1];
                }
                c.disconnect();
                runOnUiThread(this::updateBalanceCard);
            } catch (Exception e) {
                final String msg = e.getMessage();
                runOnUiThread(() -> log("⚠️ Balance load fail: " + msg));
            }
        }).start();
    }

    private void updateBalanceCard() {
        balDemoTv.setText("DEMO\n" + (lastDemoBal >= 0
                ? String.format(Locale.US, "$%.2f", lastDemoBal) : "$—"));
        balRealTv.setText("REAL\n" + (lastRealBal >= 0
                ? String.format(Locale.US, "$%.2f", lastRealBal) : "$—"));
    }

    // ── UI helpers ────────────────────────────────────────────────────
    private View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, h));
        return v;
    }

    private TextView sectionLabel(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(Color.parseColor("#00d4aa"));
        tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        return tv;
    }

    private TextView bigBal(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(17);
        tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        tv.setTextColor(Color.parseColor("#00d4aa"));
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private static String tfLabel(int tf) { return tf == 30 ? "30s" : (tf / 60) + "m"; }

    private static String timeStr(long unixSec) {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(unixSec * 1000));
    }

    private static String gradeText(SignalsEngine.Signal s) {
        return "STRONG".equals(s.grade) ? "⭐ STRONG" : s.grade;
    }

    private void askRealConfirm() {
        final CheckBox cb = new CheckBox(this);
        cb.setText("Main samajhta hoon: signals par khud trade lagana mera apna faisla hai, paise DOOB sakte hain!");
        cb.setTextColor(Color.WHITE);
        new AlertDialog.Builder(this)
                .setTitle("⚠️ REAL ACCOUNT!")
                .setMessage("REAL mode me tum apne ASLI account ki candles dekhoge.\n\n• App khud koi trade NAHI lagati — sirf signal degi\n• Signal par trade lagana tumhara apna faisla hai\n• Paise poore DOOB sakte hain — profit ki GUARANTEE NAHI\n\nPehle DEMO par achhi tarah test kar lo!")
                .setView(cb)
                .setPositiveButton("Main samajh gaya, REAL chalao", (d, w) -> {
                    if (cb.isChecked()) {
                        realConfirmed = true;
                    } else {
                        modeSp.setSelection(0);
                        Toast.makeText(this, "Pehle tick karo!", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Nahi, DEMO hi rakho", (d, w) -> {
                    modeSp.setSelection(0);
                    realConfirmed = false;
                })
                .setCancelable(false)
                .show();
    }

    // ── bot control ───────────────────────────────────────────────────
    private void startBot() {
        boolean rm = isRealMarket();
        boolean iq = isIqOption();
        SharedPreferences p = getSharedPreferences(LoginActivity.PREFS, MODE_PRIVATE);
        // Har source ke apne credentials: Quotex wale alag, IQ Option wale alag.
        String email = iq ? p.getString("iq_email", "") : p.getString("email", "");
        String pw = iq ? p.getString("iq_password", "") : p.getString("password", "");
        // Real Market mode: kisi broker ka login NAHI chahiye (EXE v1.1 jaisa).
        if (!rm && (email.isEmpty() || pw.isEmpty())) {
            Toast.makeText(this, iq ? "Pehle IQ Option login karo!" : "Pehle login karo!",
                    Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        boolean isDemo = rm || modeSp.getSelectedItemPosition() == 0;
        if (!isDemo && !realConfirmed) { askRealConfirm(); return; }

        BotSettings s = BotSettings.load(this);
        if (s.assetMap().isEmpty() || s.timeframes().isEmpty() || s.strategies().isEmpty()) {
            Toast.makeText(this, "Settings adhuri hain — Settings me ja kar check karo!",
                    Toast.LENGTH_LONG).show();
            return;
        }

        SignalBotEngine.Config cfg = new SignalBotEngine.Config();
        cfg.context = this;
        cfg.email = email;
        cfg.password = pw;
        cfg.isDemo = isDemo;
        cfg.source = currentSource;
        cfg.assetMap = rm ? s.assetMapRealMarket()
                : (iq ? s.assetMapIqOption() : s.assetMap());
        cfg.timeframes = s.timeframes();
        cfg.thresholdPct = s.thresholdPct;
        cfg.strategies = s.strategies();
        cfg.trendFilter = s.trendFilter;
        cfg.rsiFilter = s.rsiFilter;
        cfg.maxSignals = s.maxSignals;

        log("🚀 Signals start: " + cfg.assetMap.values()
                + " | TF " + cfg.timeframes + "s | Strategies: " + cfg.strategies
                + " | Trend: " + (cfg.trendFilter ? "ON" : "OFF")
                + " | RSI: " + (cfg.rsiFilter ? "ON" : "OFF")
                + String.format(Locale.US, " | Body %.0f%%", cfg.thresholdPct)
                + " | " + (rm ? "REAL MARKET" : (iq ? "IQ OPTION " : "") + (isDemo ? "DEMO" : "⚠️ REAL")));
        bannerTv.setText("— signal ka intezar… —");
        bannerTv.setBackgroundColor(Color.parseColor("#1a1a2e"));
        bannerTv.setTextColor(Color.parseColor("#888888"));
        startBtn.setEnabled(false);
        settingsBtn.setEnabled(false);
        sourceSp.setEnabled(false);
        stopBtn.setEnabled(true);
        engine = new SignalBotEngine(cfg, this);
        engine.start();
    }

    private void stopBot() {
        if (engine != null) engine.stop();
        runOnUiThread(() -> {
            startBtn.setEnabled(true);
            settingsBtn.setEnabled(true);
            sourceSp.setEnabled(true);
            applySourceUi();
            stopBtn.setEnabled(false);
            statusTv.setText("");
        });
        log("⏹ Bot roka gaya.");
    }

    // ── alert (sound + vibration) ─────────────────────────────────────
    private void alert() {
        BotSettings s = BotSettings.load(this);
        if (!s.sound) return;
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            Ringtone r = RingtoneManager.getRingtone(getApplicationContext(), uri);
            if (r != null) r.play();
        } catch (Exception ignored) {}
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createOneShot(450,
                    VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {}
    }

    // ── SignalBotEngine.Ui ────────────────────────────────────────────
    @Override
    public void log(final String msg) {
        runOnUiThread(() -> {
            String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            logTv.append("[" + t + "] " + msg + "\n");
            String all = logTv.getText().toString();
            int lines = 0, cut = -1;
            for (int i = 0; i < all.length(); i++)
                if (all.charAt(i) == '\n' && ++lines > 400) { cut = i + 1; break; }
            if (cut > 0) logTv.setText(all.substring(cut));
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    public void status(final String msg) {
        runOnUiThread(() -> {
            statusTv.setText(msg);
            // EXE jaisa: STALE par status laal, warna narangi.
            statusTv.setTextColor(Color.parseColor(
                    msg.contains("STALE") ? "#e94560" : "#ffaa00"));
        });
    }

    @Override
    public void signal(final SignalsEngine.Signal sig) {
        String arrow = sig.direction.equals("call") ? "⬆ CALL" : "⬇ PUT";
        log("🔔 SIGNAL [" + sig.sourceLabel() + "] [" + sig.kindLabel() + "] " + arrow + " " + sig.asset
                + " " + tfLabel(sig.timeframe) + " — " + gradeText(sig)
                + " (" + sig.detail + ") @ " + timeStr(sig.issuedAt));
        runOnUiThread(() -> {
            bannerTv.setText("🔔 " + arrow + " — " + sig.asset + " (" + tfLabel(sig.timeframe) + ")\n"
                    + sig.kindLabel() + " — " + gradeText(sig) + " — " + sig.detail
                    + " — " + timeStr(sig.issuedAt) + " — " + sig.sourceLabel());
            bannerTv.setBackgroundColor(Color.parseColor(
                    sig.direction.equals("call") ? "#14532d" : "#7f1d1d"));
            bannerTv.setTextColor(Color.WHITE);
            renderHistory();
        });
        alert();
    }

    @Override
    public void result(final SignalsEngine.Signal sig) {
        String arrow = sig.direction.equals("call") ? "⬆ CALL" : "⬇ PUT";
        String mark = "WIN".equals(sig.result) ? "✅ WIN" : ("LOSS".equals(sig.result) ? "❌ LOSS" : "➖ DRAW");
        log(mark + ": [" + sig.kindLabel() + "] " + arrow + " " + sig.asset
                + " " + tfLabel(sig.timeframe) + " @ " + timeStr(sig.issuedAt));
        runOnUiThread(this::renderHistory);
    }

    @Override
    public void blocked(String kind, String direction, String why) {
        String label = SignalsEngine.KIND_LABELS.get(kind);
        log("⛔ Blocked [" + (label != null ? label : kind) + "] "
                + direction.toUpperCase(Locale.US) + " — " + why);
    }

    @Override
    public void stats(final SignalsEngine engine) {
        if (engine == null) return;
        final SignalsEngine.Stats st;
        final String srcLabel;
        final Map<String, SignalsEngine.Stats> byKindSrc = new LinkedHashMap<>();
        synchronized (engine) {
            st = engine.stats;
            srcLabel = SignalsEngine.SOURCE_LABELS.get(engine.source);
            // EXE jaisa: sirf ACTIVE source ke per-(source, kind) stats.
            for (String kind : SignalsEngine.KIND_LABELS.keySet()) {
                SignalsEngine.Stats ks = engine.statsFor(engine.source, kind);
                byKindSrc.put(kind, ks != null ? ks : engine.statsByKind.get(kind));
            }
        }
        runOnUiThread(() -> {
            Double wp = st.winPct();
            statsTv.setText("Signals: " + st.total
                    + " | WIN: " + st.wins + " | LOSS: " + st.losses + " | DRAW: " + st.draws
                    + " | Win%: " + (wp == null ? "—" : String.format(Locale.US, "%.0f%%", wp)));
            StringBuilder sb = new StringBuilder();
            sb.append(srcLabel != null ? srcLabel : engine.source).append(" — ");
            boolean first = true;
            for (Map.Entry<String, SignalsEngine.Stats> e : byKindSrc.entrySet()) {
                if (!first) sb.append("  •  ");
                first = false;
                SignalsEngine.Stats ks = e.getValue();
                Double kw = ks.winPct();
                sb.append(SignalsEngine.KIND_LABELS.get(e.getKey()))
                        .append(": ").append(ks.total).append(" signals, W").append(ks.wins)
                        .append("/L").append(ks.losses)
                        .append(kw == null ? " (—)" : String.format(Locale.US, " (%.0f%%)", kw));
            }
            kindStatsTv.setText(sb.toString());
        });
    }

    @Override
    public void balances(final double demo, final double live) {
        lastDemoBal = demo;
        lastRealBal = live;
        runOnUiThread(this::updateBalanceCard);
    }

    @Override
    public void finished(final String summary) {
        log(summary);
        runOnUiThread(() -> {
            startBtn.setEnabled(true);
            settingsBtn.setEnabled(true);
            sourceSp.setEnabled(true);
            applySourceUi();
            stopBtn.setEnabled(false);
            statusTv.setText("");
        });
    }

    private void renderHistory() {
        SignalBotEngine e = engine;
        if (e == null || e.engine() == null) { histTv.setText("—"); return; }
        SignalsEngine eng = e.engine();
        final List<SignalsEngine.Signal> hist;
        synchronized (eng) { hist = new ArrayList<>(eng.history); }
        if (hist.isEmpty()) { histTv.setText("—"); return; }
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, hist.size() - 100);
        for (int i = hist.size() - 1; i >= from; i--) {
            SignalsEngine.Signal s = hist.get(i);
            String arrow = s.direction.equals("call") ? "⬆ CALL" : "⬇ PUT";
            String mark, color;
            if (s.result == null) { mark = "⏳"; color = "#ffaa00"; }
            else if (s.result.equals("WIN")) { mark = "✅ WIN"; color = "#00ff88"; }
            else if (s.result.equals("LOSS")) { mark = "❌ LOSS"; color = "#ff5566"; }
            else { mark = "➖ DRAW"; color = "#aaaaaa"; }
            sb.append("<font color=\"#aaaaaa\">").append(timeStr(s.issuedAt)).append("</font> ")
                    .append("[").append(s.sourceLabel()).append("] ")
                    .append(arrow).append(" <b>").append(s.asset).append("</b> ")
                    .append(tfLabel(s.timeframe)).append(" ")
                    .append(s.kindLabel()).append(" ")
                    .append(gradeText(s)).append(" ")
                    .append("<font color=\"").append(color).append("\"><b>").append(mark)
                    .append("</b></font><br>");
        }
        histTv.setText(Html.fromHtml(sb.toString(), Html.FROM_HTML_MODE_LEGACY));
    }

    @Override
    protected void onDestroy() {
        if (engine != null && engine.isRunning()) engine.stop();
        super.onDestroy();
    }
}
