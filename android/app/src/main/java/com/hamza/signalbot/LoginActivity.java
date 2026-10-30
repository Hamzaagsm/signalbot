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
    private EditText iqEmailEt, iqPwEt;
    private CheckBox rememberCb;
    private Button loginBtn, iqLoginBtn;
    private TextView statusTv, iqStatusTv;

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
        sub.setText("Perfect Candle Signals v1.2 — SIGNAL ONLY, no auto trade");
        sub.setTextColor(Color.parseColor("#888888"));
        sub.setGravity(Gravity.CENTER);
        root.addView(sub);
        root.addView(spacer(30));

        TextView qxLbl = new TextView(this);
        qxLbl.setText("— QUOTEX —");
        qxLbl.setTextColor(Color.parseColor("#00d4aa"));
        qxLbl.setGravity(Gravity.CENTER);
        root.addView(qxLbl);
        root.addView(spacer(8));

        emailEt = field(root, "Quotex Email:");
        pwEt = field(root, "Quotex Password:");
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
        loginBtn.setText("🔑 QUOTEX LOGIN");
        loginBtn.setTextSize(18);
        loginBtn.setBackgroundColor(Color.parseColor("#00d4aa"));
        loginBtn.setTextColor(Color.BLACK);
        root.addView(loginBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(spacer(12));

        // v1.1: Real Market mode — Quotex login ke baghair, asal market data se signals.
        Button rmBtn = new Button(this);
        rmBtn.setText("📡 REAL MARKET — BINA LOGIN SIGNALS");
        rmBtn.setTextSize(15);
        rmBtn.setBackgroundColor(Color.parseColor("#1a1a2e"));
        rmBtn.setTextColor(Color.parseColor("#00d4aa"));
        root.addView(rmBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(spacer(16));

        statusTv = new TextView(this);
        statusTv.setTextColor(Color.parseColor("#ffaa00"));
        statusTv.setGravity(Gravity.CENTER);
        root.addView(statusTv);
        root.addView(spacer(24));

        // v1.2: IQ Option login — apna IQ account, PRACTICE (DEMO) par verify.
        TextView iqLbl = new TextView(this);
        iqLbl.setText("— IQ OPTION —");
        iqLbl.setTextColor(Color.parseColor("#00d4aa"));
        iqLbl.setGravity(Gravity.CENTER);
        root.addView(iqLbl);
        root.addView(spacer(8));

        iqEmailEt = field(root, "IQ Option Email:");
        iqPwEt = field(root, "IQ Option Password:");
        iqPwEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        iqEmailEt.setText(p.getString("iq_email", ""));
        iqPwEt.setText(p.getString("iq_password", ""));

        iqLoginBtn = new Button(this);
        iqLoginBtn.setText("🔑 IQ OPTION LOGIN");
        iqLoginBtn.setTextSize(18);
        iqLoginBtn.setBackgroundColor(Color.parseColor("#00d4aa"));
        iqLoginBtn.setTextColor(Color.BLACK);
        root.addView(iqLoginBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(spacer(12));

        iqStatusTv = new TextView(this);
        iqStatusTv.setTextColor(Color.parseColor("#ffaa00"));
        iqStatusTv.setGravity(Gravity.CENTER);
        root.addView(iqStatusTv);
        root.addView(spacer(16));

        TextView warn = new TextView(this);
        warn.setText("⚠️ Pehle DEMO par test karo!\nSignals ki profit GUARANTEE NAHI.\nBot sirf signal deta hai — trade tum khud lagate ho.");
        warn.setTextColor(Color.parseColor("#e94560"));
        warn.setGravity(Gravity.CENTER);
        root.addView(warn);

        setContentView(new ScrollView(this) {{ addView(root); }});

        loginBtn.setOnClickListener(v -> doLogin());
        iqLoginBtn.setOnClickListener(v -> doIqLogin());
        rmBtn.setOnClickListener(v -> {
            Intent i = new Intent(this, MainActivity.class);
            i.putExtra("source", "realmarket");
            startActivity(i);
            finish();
        });
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

    private void setIqStatus(final String s) {
        runOnUiThread(() -> iqStatusTv.setText(s));
    }

    /** v1.2: IQ Option login verify (HTTP login + websocket auth, PRACTICE). */
    private void doIqLogin() {
        final String email = iqEmailEt.getText().toString().trim();
        final String pw = iqPwEt.getText().toString();
        if (email.isEmpty() || pw.isEmpty()) {
            Toast.makeText(this, "IQ Option Email aur Password likho!", Toast.LENGTH_SHORT).show();
            return;
        }
        iqLoginBtn.setEnabled(false);
        setIqStatus("⏳ IQ Option se connect ho raha...");
        new Thread(() -> {
            try {
                IqOptionFeed.Balances bal = IqOptionFeed.verifyLogin(email, pw);
                if (rememberCb.isChecked()) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString("iq_email", email).putString("iq_password", pw).apply();
                } else {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString("iq_email", email).putString("iq_password", "").apply();
                }
                String balTxt = bal.practice != null
                        ? String.format(java.util.Locale.US, " | PRACTICE $%,.2f", bal.practice) : "";
                setIqStatus("✅ IQ Option login OK!" + balTxt);
                runOnUiThread(() -> {
                    Toast.makeText(this, "✅ IQ Option Login OK!", Toast.LENGTH_SHORT).show();
                    Intent i = new Intent(this, MainActivity.class);
                    i.putExtra("source", "iqoption");
                    startActivity(i);
                    finish();
                });
            } catch (Exception e) {
                String msg = e.getMessage();
                if (isDnsError(msg))
                    msg += "\n\n💡 VPN on karke dobara try karo! (Pakistan me kabhi ISP block hota hai)";
                setIqStatus("❌ " + msg);
                runOnUiThread(() -> {
                    iqLoginBtn.setEnabled(true);
                    Toast.makeText(this, "IQ login fail — neeche dekho", Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
}
