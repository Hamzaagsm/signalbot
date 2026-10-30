package com.hamza.signalbot;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Quotex Socket.IO (EIO=3) client over HTTP long-polling.
 * Hamza Signal Bot fork of the Qx Bot client — SIGNAL-ONLY build:
 * the order-placement API was removed on purpose; this client can
 * only connect, log in, read balances/instruments and follow depth.
 *
 * Connection strategy (sab se pehle jo kaam kare):
    // (Cronet removed — OkHttp+DoH only for reliability)
 *      x hosts [ws2.qxbroker.com, ws.qxbroker.com]
 *   2. OkHttp + DNS-over-HTTPS transport (ISP DNS block ka tor)
 *      x hosts [ws2.qxbroker.com, ws.qxbroker.com]
 *
 * Har attempt par exponential backoff. Sab fail hon to Urdu me VPN mashwara.
 *
 * Protocol:
 *   GET  https://HOST/socket.io/?EIO=3&transport=polling   -> 0{"sid":"..."}
 *   GET  ...&sid=SID          (long-poll, ~25s)  -> <len>:<msg>...
 *   POST ...&sid=SID          body: 42["event",data]  / "3" (pong)
 *
 * Messages: "2"=ping, "40"=connected, 42["event",data], 451-[data]=ACK.
 */
public class QuotexClient {
    private static final String TAG = "QuotexClient";
    private static final String[] WS_HOSTS = {"ws2.qxbroker.com", "ws.qxbroker.com"};

    /** Browser-identical request headers (Chrome 126 on Android). */
    private static Map<String, String> browserHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 9 Build/AP2A.240605.024) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36");
        h.put("Origin", "https://qxbroker.com");
        h.put("Referer", "https://qxbroker.com/en/trade/");
        h.put("Accept", "*/*");
        h.put("Accept-Language", "en-US,en;q=0.9");
        h.put("Cache-Control", "no-cache");
        h.put("Pragma", "no-cache");
        h.put("Sec-Ch-Ua", "\"Chromium\";v=\"126\", \"Not-A.Brand\";v=\"24\", \"Google Chrome\";v=\"126\"");
        h.put("Sec-Ch-Ua-Mobile", "?1");
        h.put("Sec-Ch-Ua-Platform", "\"Android\"");
        h.put("Sec-Fetch-Dest", "empty");
        h.put("Sec-Fetch-Mode", "cors");
        h.put("Sec-Fetch-Site", "same-site");
        return h;
    }

    public interface EventHandler { void onEvent(JSONObject data); }
    public interface RawHandler { void onRaw(JSONArray arr); }
    public interface LogSink { void log(String msg); }

    public static class LoginResult {
        public final String ssid;
        public final double demoBalance;
        public final double liveBalance;
        LoginResult(String ssid, double demoBalance, double liveBalance) {
            this.ssid = ssid; this.demoBalance = demoBalance; this.liveBalance = liveBalance;
        }
    }

    /** Ek asset: display name + server key. */
    public static class AssetInfo {
        public final String display; // "EURUSD (OTC)"
        public final String key;     // "EURUSD_otc"
        AssetInfo(String d, String k) { display = d; key = k; }
        @Override public String toString() { return display; }
    }

    private final Context appCtx;
    private HttpTransport transport;
    private final Map<String, String> headers = browserHeaders();
    private LogSink sink;
    private volatile String sid;
    private volatile String pollBase;
    private volatile boolean running;
    private Thread pollThread;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<EventHandler>> handlers = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<RawHandler> rawHandlers = new CopyOnWriteArrayList<>();

    public QuotexClient(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    public void setLogSink(LogSink s) { this.sink = s; }
    private void slog(String m) {
        Log.d(TAG, m);
        if (sink != null) sink.log(m);
    }

    // ---------------- event bus ----------------
    public void subscribe(String event, EventHandler h) {
        handlers.computeIfAbsent(event, k -> new CopyOnWriteArrayList<>()).add(h);
    }
    public void unsubscribe(String event, EventHandler h) {
        List<EventHandler> l = handlers.get(event);
        if (l != null) l.remove(h);
    }
    public void subscribeRaw(RawHandler h) { rawHandlers.add(h); }
    public void unsubscribeRaw(RawHandler h) { rawHandlers.remove(h); }

    private void dispatch(String event, JSONObject data) {
        List<EventHandler> l = handlers.get(event);
        if (l == null) return;
        for (EventHandler h : l) {
            try { h.onEvent(data); } catch (Exception e) { Log.e(TAG, "handler err", e); }
        }
    }
    private void dispatchRaw(JSONArray arr) {
        for (RawHandler h : rawHandlers) {
            try { h.onRaw(arr); } catch (Exception e) { Log.e(TAG, "raw handler err", e); }
        }
    }

    /** Parse Socket.IO polling payload: <len>:<msg><len>:<msg>... */
    static List<String> parsePayload(String payload) {
        ArrayList<String> out = new ArrayList<>();
        int i = 0;
        while (i < payload.length()) {
            int colon = payload.indexOf(':', i);
            if (colon < 0) break;
            int len;
            try { len = Integer.parseInt(payload.substring(i, colon)); }
            catch (NumberFormatException e) { break; }
            int start = colon + 1;
            int end = start + len;
            if (end > payload.length()) break;
            out.add(payload.substring(start, end));
            i = end;
        }
        return out;
    }

    private static boolean isDnsError(Throwable e) {
        String m = String.valueOf(e.getMessage()).toLowerCase();
        return m.contains("unable to resolve host") || m.contains("unknownhost")
                || m.contains("no address associated with hostname")
                || m.contains("dns");
    }

    // ---------------- connection ----------------
    public void connect() throws Exception {
        HttpTransport[] transports = new HttpTransport[]{
                new OkHttpDohTransport(),       // DNS-over-HTTPS (ISP block bypass)
                new OkHttpDohTransport(),       // retry with DoH
        };
        Exception lastErr = null;
        boolean sawDnsError = false;
        for (HttpTransport t : transports) {
            for (String host : WS_HOSTS) {
                for (int attempt = 1; attempt <= 2; attempt++) {
                    try {
                        String url = "https://" + host + "/socket.io/?EIO=3&transport=polling";
                        slog("Connect: " + host + " via " + t.name()
                                + (attempt > 1 ? " (retry)" : "") + " ...");
                        String body = t.get(url, headers);
                        String foundSid = null;
                        for (String m : parsePayload(body)) {
                            if (m.startsWith("0{")) {
                                foundSid = new JSONObject(m.substring(1)).optString("sid", null);
                                break;
                            }
                        }
                        if (foundSid == null || foundSid.isEmpty())
                            throw new IOException("No SID in handshake");
                        // success: keep this transport, shut the others
                        this.transport = t;
                        for (HttpTransport other : transports)
                            if (other != t) other.shutdown();
                        this.sid = foundSid;
                        this.pollBase = url;
                        slog("✅ Connected (" + t.name() + " / " + host + ")");
                        startPoller();
                        return;
                    } catch (Exception e) {
                        lastErr = e;
                        if (isDnsError(e)) sawDnsError = true;
                        slog("  ✗ " + host + ": " + e.getMessage());
                        try { Thread.sleep(1200L * attempt); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new Exception("cancelled");
                        }
                    }
                }
            }
        }
        for (HttpTransport t : transports) {
            if (t != transport) { try { t.shutdown(); } catch (Exception ignored) {} }
        }
        String hint = sawDnsError
                ? "\n💡 DNS block lag raha hai — VPN on karke dobara try karo! (Pakistan me kabhi ISP block hota hai)"
                : "";
        throw new Exception("Connect fail: Quotex server tak rasta nahi mil raha"
                + (lastErr != null ? " (" + lastErr.getMessage() + ")" : "") + hint);
    }

    private void startPoller() {
        running = true;
        pollThread = new Thread(this::pollLoop, "QxPoller");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void pollLoop() {
        // poll interval me halki randomness (bot-pattern se bachne ke liye)
        java.util.Random rnd = new java.util.Random();
        while (running && sid != null) {
            try {
                String url = pollBase + "&sid=" + sid;
                String body = transport.get(url, headers);
                if (body == null || body.isEmpty()) continue;
                for (String m : parsePayload(body)) handleMessage(m);
                // tiny jitter between polls
                Thread.sleep(rnd.nextInt(400));
            } catch (Exception e) {
                if (running) {
                    Log.w(TAG, "poll err: " + e.getMessage());
                    try { Thread.sleep(1500); } catch (InterruptedException ie) { break; }
                }
            }
        }
    }

    private void handleMessage(String m) {
        try {
            if (m.equals("2")) { sendRaw("3"); return; }      // ping -> pong
            if (m.equals("40") || m.startsWith("0{") || m.startsWith("3")) return;
            if (m.startsWith("45") && m.contains("-")) {       // ACK: 451-[...]
                int dash = m.indexOf('-');
                JSONArray arr = new JSONArray(m.substring(dash + 1));
                dispatchRaw(arr);
                if (arr.length() > 0 && arr.get(0) instanceof JSONObject)
                    dispatch("_ack_response", arr.getJSONObject(0));
                return;
            }
            if (!m.startsWith("42")) return;
            JSONArray payload = new JSONArray(m.substring(2));
            if (payload.length() < 1) return;
            String event = payload.getString(0);
            JSONObject data = (payload.length() > 1 && payload.get(1) instanceof JSONObject)
                    ? payload.getJSONObject(1) : new JSONObject();
            dispatch(event, data);
        } catch (Exception e) {
            Log.w(TAG, "msg parse err: " + e.getMessage());
        }
    }

    public void sendRaw(String msg) throws IOException {
        if (sid == null || transport == null) throw new IOException("not connected");
        transport.post(pollBase + "&sid=" + sid, msg, headers);
    }

    public void sendEvent(String event, Object data) throws Exception {
        JSONArray arr = new JSONArray();
        arr.put(event);
        if (data instanceof JSONObject) arr.put((JSONObject) data);
        else if (data instanceof String) arr.put((String) data);
        else arr.put(JSONObject.wrap(data));
        sendRaw("42" + arr.toString());
    }

    // ---------------- auth ----------------
    public LoginResult login(String email, String password, boolean isDemo) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final JSONObject[] box = new JSONObject[1];
        EventHandler h = data -> { box[0] = data; latch.countDown(); };
        subscribe("login", h);
        try {
            JSONObject d = new JSONObject();
            d.put("email", email);
            d.put("password", password);
            d.put("isDemo", isDemo ? 1 : 0);
            slog("Login bhej raha...");
            sendEvent("login", d);
            if (!latch.await(30, TimeUnit.SECONDS))
                throw new Exception("Login timeout (30s) — server ne jawab nahi diya");
            JSONObject r = box[0];
            if (r == null) throw new Exception("Login: koi jawab nahi");
            if (!r.optBoolean("isSuccessful", false))
                throw new Exception("Login failed: " + r.optString("message", "ghalat email/password?"));
            String ssid = r.optString("ssid", r.optString("session", ""));
            double demo = r.optDouble("demo_balance", r.optDouble("demoBalance", 10000));
            double live = r.optDouble("real_balance", r.optDouble("liveBalance", 0));
            slog("Login OK");
            return new LoginResult(ssid, demo, live);
        } finally {
            unsubscribe("login", h);
        }
    }

    /** Returns {demoBalance, liveBalance} ya null (timeout par). */
    public double[] getBalances() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final JSONObject[] box = new JSONObject[1];
        EventHandler h = data -> { box[0] = data; latch.countDown(); };
        subscribe("_ack_response", h);
        try {
            JSONObject d = new JSONObject();
            d.put("_placeholder", true);
            d.put("num", 0);
            sendEvent("s_balance/list", d);
            if (!latch.await(12, TimeUnit.SECONDS)) {
                slog("Balance timeout — purana balance use kar raha");
                return null;
            }
            JSONObject r = box[0];
            return new double[]{r.optDouble("demoBalance", r.optDouble("demo_balance", -1)),
                    r.optDouble("liveBalance", r.optDouble("live_balance", r.optDouble("real_balance", -1)))};
        } finally {
            unsubscribe("_ack_response", h);
        }
    }

    // ---------------- instruments (live asset list) ----------------
    /**
     * Quotex se live asset list lao. Har asset: display name + server key.
     * Timeout ya error par null (caller static fallback use kare).
     */
    public List<AssetInfo> fetchInstruments() {
        final CountDownLatch latch = new CountDownLatch(1);
        final JSONArray[] box = new JSONArray[1];
        RawHandler h = arr -> { box[0] = arr; latch.countDown(); };
        subscribeRaw(h);
        try {
            JSONObject d = new JSONObject();
            d.put("_placeholder", true);
            d.put("num", 0);
            sendEvent("instruments/list", d);
            if (!latch.await(15, TimeUnit.SECONDS)) {
                slog("Instruments timeout — default list use hogi");
                return null;
            }
            JSONArray arr = box[0];
            if (arr == null || arr.length() == 0) return null;
            List<AssetInfo> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                Object o = arr.opt(i);
                if (o instanceof JSONObject) collectAssets((JSONObject) o, out);
                else if (o instanceof JSONArray) {
                    JSONArray inner = (JSONArray) o;
                    for (int j = 0; j < inner.length(); j++) {
                        Object io = inner.opt(j);
                        if (io instanceof JSONObject) collectAssets((JSONObject) io, out);
                    }
                }
            }
            if (out.isEmpty()) return null;
            slog("📋 " + out.size() + " assets live mile");
            return out;
        } catch (Exception e) {
            slog("Instruments error: " + e.getMessage());
            return null;
        } finally {
            unsubscribeRaw(h);
        }
    }

    private void collectAssets(JSONObject o, List<AssetInfo> out) {
        String[] containers = {"instruments", "assets", "data", "list"};
        for (String c : containers) {
            JSONArray ja = o.optJSONArray(c);
            if (ja != null) {
                for (int i = 0; i < ja.length(); i++) {
                    JSONObject a = ja.optJSONObject(i);
                    if (a != null) addAsset(a, out);
                }
                return;
            }
        }
        addAsset(o, out);
    }

    private void addAsset(JSONObject a, List<AssetInfo> out) {
        String name = a.optString("name", a.optString("id", a.optString("asset", "")));
        if (name.isEmpty()) return;
        if (!a.optBoolean("enabled", true)) return;
        if (a.has("isEnabled") && !a.optBoolean("isEnabled", true)) return;
        String display = name.endsWith("_otc")
                ? name.substring(0, name.length() - 4) + " (OTC)"
                : name;
        for (AssetInfo e : out) if (e.key.equals(name)) return;
        out.add(new AssetInfo(display, name));
    }

    // ---------------- market data ----------------
    public void followDepth(String assetKey) throws Exception {
        sendEvent("depth/follow", assetKey);
        slog("Candles subscribe: " + assetKey);
    }
    public void unfollowDepth(String assetKey) {
        try { sendEvent("depth/unfollow", assetKey); } catch (Exception ignored) {}
    }

    public void disconnect() {
        running = false;
        if (pollThread != null) { pollThread.interrupt(); pollThread = null; }
        sid = null;
        if (transport != null) { try { transport.shutdown(); } catch (Exception ignored) {} transport = null; }
        slog("Disconnected");
    }
    public boolean isConnected() { return running && sid != null; }
}
