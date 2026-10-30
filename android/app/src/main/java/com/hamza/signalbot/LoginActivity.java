package com.hamza.signalbot;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Login screen: email/password, DEMO-first verification. Credentials saved privately on device. */
public class LoginActivity extends Activity {

    public static final String PREFS = "signalbot_prefs";
    private EditText emailEt, pwEt;
    private CheckBox rememberCb;
    private Button loginBtn;
    private TextView statusTv;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0a0a14"));
        root.setPadding(48, 80, 48, 48);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("🔔 HAMZA SIGNAL BOT");
        title.setTextSize(26);
        title.setTextColor(Color.parseColor("#00d4aa"));
        title.setGravity(Gravity.CENTER);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Perfect Candle Signals v1.0 — SIGNAL ONLY, no auto trade");
        sub.setTextColor(Color.parseColor("#888888"));
        sub.setGravity(Gravity.CENTER);
        root.addView(sub);
        root.addView(spacer(30));

        emailEt = field(root, "Email:");
        pwEt = field(root, "Password:");
        pwEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        emailEt.setText(p.getString("email", ""));
        pwEt.setText(p.getString("password", ""));

        rememberCb = new CheckBox(this);
        rememberCb.setText("Login yaad rakho");
        rememberCb.setTextColor(Color.WHITE);
        rememberCb.setChecked(true);
        root.addView(rememberCb);
        root.addView(spacer(16));

        loginBtn = new Button(this);
        loginBtn.setText("🔑 LOGIN");
        loginBtn.setTextSize(18);
        loginBtn.setBackgroundColor(Color.parseColor("#00d4aa"));
        loginBtn.setTextColor(Color.BLACK);
        root.addView(loginBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(spacer(16));

        statusTv = new TextView(this);
        statusTv.setTextColor(Color.parseColor("#ffaa00"));
        statusTv.setGravity(Gravity.CENTER);
        root.addView(statusTv);
        root.addView(spacer(16));

        TextView warn = new TextView(this);
        warn.setText("⚠️ Pehle DEMO par test karo!\nSignals ki profit GUARANTEE NAHI.\nBot sirf signal deta hai — trade tum khud lagate ho.");
        warn.setTextColor(Color.parseColor("#e94560"));
        warn.setGravity(Gravity.CENTER);
        root.addView(warn);

        setContentView(new ScrollView(this) {{ addView(root); }});

        loginBtn.setOnClickListener(v -> doLogin());
    }

    private View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, h));
        return v;
    }

    private EditText field(LinearLayout root, String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        root.addView(tv);
        EditText et = new EditText(this);
        et.setBackgroundColor(Color.parseColor("#1a1a2e"));
        et.setTextColor(Color.WHITE);
        et.setPadding(24, 20, 24, 20);
        root.addView(et, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(spacer(12));
        return et;
    }

    private void setStatus(final String s) {
        runOnUiThread(() -> statusTv.setText(s));
    }

    private static boolean isDnsError(String msg) {
        if (msg == null) return false;
        String m = msg.toLowerCase();
        return m.contains("unable to resolve host") || m.contains("unknownhost")
                || m.contains("no address associated with hostname") || m.contains("dns");
    }

    private void doLogin() {
        final String email = emailEt.getText().toString().trim();
        final String pw = pwEt.getText().toString();
        if (email.isEmpty() || pw.isEmpty()) {
            Toast.makeText(this, "Email aur Password likho!", Toast.LENGTH_SHORT).show();
            return;
        }
        loginBtn.setEnabled(false);
        setStatus("⏳ Server se connect ho raha...");
        new Thread(() -> {
            QuotexClient c = new QuotexClient(this);
            try {
                c.connect();
                setStatus("⏳ Login verify ho raha...");
                c.login(email, pw, true);   // verify on DEMO
                c.disconnect();
                if (rememberCb.isChecked()) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString("email", email).putString("password", pw).apply();
                } else {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString("email", email).putString("password", "").apply();
                }
                runOnUiThread(() -> {
                    Toast.makeText(this, "✅ Login OK!", Toast.LENGTH_SHORT).show();
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                });
            } catch (Exception e) {
                String msg = e.getMessage();
                if (isDnsError(msg))
                    msg += "\n\n💡 VPN on karke dobara try karo! (Pakistan me kabhi ISP block hota hai)";
                setStatus("❌ " + msg);
                runOnUiThread(() -> {
                    loginBtn.setEnabled(true);
                    Toast.makeText(this, "Login fail — neeche dekho", Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
}
