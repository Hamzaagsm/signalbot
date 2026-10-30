package com.hamza.signalbot;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * Settings screen: strategies, filters, threshold, timeframes, assets,
 * max signals, sound. Defaults mirror the Windows EXE v1.
 */
public class SettingsActivity extends Activity {

    private EditText thresholdEt, maxEt;
    private CheckBox perfectCb, engulfCb, pinbarCb, trendCb, rsiCb;
    private CheckBox tf30Cb, tf60Cb, soundCb;
    private final java.util.Map<String, CheckBox> assetCbs = new java.util.LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        BotSettings s = BotSettings.load(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0a0a14"));
        root.setPadding(28, 28, 28, 28);

        TextView title = new TextView(this);
        title.setText("⚙️ Signal Bot Settings");
        title.setTextSize(20);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setTextColor(Color.parseColor("#00d4aa"));
        root.addView(title);
        root.addView(spacer(12));

        root.addView(section("Strategies & Filters — jo tareeqay on rakhne hain"));
        perfectCb = check(root, "Perfect Candle ⭐ (main)", s.stratPerfect);
        engulfCb = check(root, "Engulfing Pattern", s.stratEngulfing);
        pinbarCb = check(root, "Pin Bar / Hammer", s.stratPinbar);
        trendCb = check(root, "Trend Filter (EMA 20/50) — sirf trend ki simt signals", s.trendFilter);
        rsiCb = check(root, "RSI Filter — overbought (70+) par CALL band, oversold (30-) par PUT band", s.rsiFilter);
        root.addView(spacer(10));

        root.addView(section("Timeframes"));
        tf30Cb = check(root, "30 second", s.tf30);
        tf60Cb = check(root, "1 minute", s.tf60);
        root.addView(spacer(10));

        root.addView(section("Assets (OTC) — jo pairs dekhne hain"));
        for (String[] a : BotSettings.ASSETS) {
            assetCbs.put(a[0], check(root, a[1], s.assetCodes.contains(a[0])));
        }
        root.addView(spacer(10));

        root.addView(section("Settings"));
        root.addView(label("Perfect Candle body % (70–95):"));
        thresholdEt = num(root, String.format(Locale.US, "%.0f", s.thresholdPct));
        root.addView(label("Max signals (0 = unlimited):"));
        maxEt = num(root, String.valueOf(s.maxSignals));
        soundCb = check(root, "Sound (beep) + vibration", s.sound);
        root.addView(spacer(16));

        Button save = new Button(this);
        save.setText("💾 SAVE");
        save.setTextSize(17);
        save.setBackgroundColor(Color.parseColor("#00d4aa"));
        save.setTextColor(Color.BLACK);
        root.addView(save, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(spacer(12));

        TextView footer = new TextView(this);
        footer.setText("Signals are not a guarantee of profit. Test on demo first.");
        footer.setTextColor(Color.parseColor("#e94560"));
        footer.setTextSize(12);
        root.addView(footer);

        setContentView(new ScrollView(this) {{ addView(root); }});

        save.setOnClickListener(v -> save());
    }

    private void save() {
        BotSettings s = new BotSettings();
        try {
            s.thresholdPct = Double.parseDouble(thresholdEt.getText().toString().trim());
            s.maxSignals = Integer.parseInt(maxEt.getText().toString().trim());
        } catch (Exception e) {
            Toast.makeText(this, "Numbers theek likho!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (s.thresholdPct < 70 || s.thresholdPct > 95) {
            Toast.makeText(this, "Body % 70–95 ke darmiyan rakho!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (s.maxSignals < 0) {
            Toast.makeText(this, "Max signals 0 ya zyada!", Toast.LENGTH_SHORT).show();
            return;
        }
        s.stratPerfect = perfectCb.isChecked();
        s.stratEngulfing = engulfCb.isChecked();
        s.stratPinbar = pinbarCb.isChecked();
        if (s.strategies().isEmpty()) {
            Toast.makeText(this, "Kam az kam aik strategy on karo!", Toast.LENGTH_SHORT).show();
            return;
        }
        s.trendFilter = trendCb.isChecked();
        s.rsiFilter = rsiCb.isChecked();
        s.tf30 = tf30Cb.isChecked();
        s.tf60 = tf60Cb.isChecked();
        if (s.timeframes().isEmpty()) {
            Toast.makeText(this, "Kam az kam aik timeframe on karo!", Toast.LENGTH_SHORT).show();
            return;
        }
        s.sound = soundCb.isChecked();
        s.assetCodes.clear();
        for (java.util.Map.Entry<String, CheckBox> e : assetCbs.entrySet())
            if (e.getValue().isChecked()) s.assetCodes.add(e.getKey());
        if (s.assetCodes.isEmpty()) {
            Toast.makeText(this, "Kam az kam aik asset select karo!", Toast.LENGTH_SHORT).show();
            return;
        }
        s.save(this);
        Toast.makeText(this, "✅ Settings save ho gayi", Toast.LENGTH_SHORT).show();
        finish();
    }

    // ── UI helpers ────────────────────────────────────────────────────
    private View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, h));
        return v;
    }

    private TextView section(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(Color.parseColor("#00d4aa"));
        tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        tv.setPadding(0, 6, 0, 6);
        return tv;
    }

    private TextView label(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(Color.WHITE);
        return tv;
    }

    private CheckBox check(LinearLayout root, String text, boolean on) {
        CheckBox cb = new CheckBox(this);
        cb.setText(text);
        cb.setTextColor(Color.WHITE);
        cb.setChecked(on);
        root.addView(cb);
        return cb;
    }

    private EditText num(LinearLayout root, String def) {
        EditText e = new EditText(this);
        e.setText(def);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setTextColor(Color.WHITE);
        e.setBackgroundColor(Color.parseColor("#1a1a2e"));
        e.setPadding(16, 10, 16, 10);
        root.addView(e, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(spacer(8));
        return e;
    }
}
