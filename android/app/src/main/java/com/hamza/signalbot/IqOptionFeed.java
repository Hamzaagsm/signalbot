package com.hamza.signalbot;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import okhttp3.Dns;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * IQ Option feed for Hamza Signal Bot v1.2 (login + websocket candles).
 * Java counterpart of the EXE's iqoption_feed.py — same protocol, same
 * rules. Hamza's correction (2026-10-30): the bot must work for IQ
 * Option AND Quotex AND other binary platforms, not Quotex only.
 *
 * Protocol (from the iqoptionapi library that Hamza IQ Bot v1.0 used —
 * READ-ONLY subset):
 *   1. POST https://auth.iqoption.com/api/v2/login (form: identifier,
 *      password, token) -> "ssid" arrives as a response COOKIE.
 *   2. WSS wss://iqoption.com/echo/websocket ->
 *      {"name":"authenticate","msg":{"ssid":SSID,"protocol":3}}
 *      <- {"name":"authenticated",...}, then "balances".
 *   3. Warmup history per asset (one at a time):
 *      {"name":"sendMessage","msg":{"name":"get-candles","version":"2.0",
 *      "body":{"active_id":N,"size":60,"to":T,"count":130}}}
 *      <- {"name":"candles","msg":{"candles":[...]}} — fed to the engine
 *      as plain close points (closes warm EMA/RSI; no fake signals).
 *   4. Live: subscribeMessage "candle-generated" with size=15 (divides
 *      both engine timeframes, 30s and 60s); stream candles are
 *      normalised (min->low, max->high) and fed as OHLC points.
 *   5. Balance mode: "balances" msg (type 1 = real, type 4 = practice);
 *      chosen id set via "api_profile_changebalance". PRACTICE default.
 *
 * Assets (iqoptionapi constants.ACTIVES): regular id + OTC id (AUD/USD
 * has no OTC entry). Forex market ~closed Fri 22:00 UTC -> Sun 22:00
 * UTC; outside market hours the OTC active is used.
 *
 * Transport: OkHttp (already an app dependency) with the same
 * DNS-over-HTTPS fallback idea as the Quotex transport — if system DNS
 * cannot resolve iqoption.com (ISP block), the client retries with
 * Google/Cloudflare DoH.
 *
 * SIGNAL ONLY: login, balances, candle history, candle subscription.
 * There is deliberately NO order/buy code path anywhere in this class.
 */
public class IqOptionFeed {
    private static final String TAG = "IqOptionFeed";

    public static final String SOURCE_ID = "iqoption";
    public static final String SOURCE_LABEL = "IQ Option";

    public static final String LOGIN_URL = "https://auth.iqoption.com/api/v2/login";
    public static final String WS_URL = "wss://iqoption.com/echo/websocket";
    static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 9) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

    /** GUI asset code -> {regular active_id, OTC active_id or -1}. */
    public static final Map<String, int[]> ACTIVES = new LinkedHashMap<>();
    static {
        ACTIVES.put("EURUSD", new int[]{1, 76});
        ACTIVES.put("GBPUSD", new int[]{5, 81});
        ACTIVES.put("USDJPY", new int[]{6, 85});
        ACTIVES.put("EURGBP", new int[]{2, 77});
        ACTIVES.put("AUDUSD", new int[]{99, -1});
        ACTIVES.put("EURJPY", new int[]{4, 79});
    }

    public static final int STREAM_SIZE = 15;
    public static final int HISTORY_SIZE = 60;
    public static final int HISTORY_COUNT = 130;

    public static class IqError extends Exception {
        public IqError(String msg) { super(msg); }
    }

    public static class Balances {
        public Double real, practice;
        public Object realId, practiceId;
    }

    public static class ActiveChoice {
        public final int id;
        public final boolean otc;
        ActiveChoice(int id, boolean otc) { this.id = id; this.otc = otc; }
    }

    /** One normalised candle: engine-ready values. */
    public static class CandlePoint {
        public final long ts;
        public final double open, high, low, close;
        CandlePoint(long ts, double o, double h, double l, double c) {
            this.ts = ts; this.open = o; this.high = h; this.low = l; this.close = c;
        }
        public JSONObject toJson() throws Exception {
            JSONObject j = new JSONObject();
            j.put("timestamp", ts);
            j.put("open", open);
            j.put("high", high);
            j.put("low", low);
            j.put("close", close);
            return j;
        }
    }

    // ── pure helpers (unit-tested) ────────────────────────────────────

    /** Approximate forex market hours (UTC): Sun 22:00 -> Fri 22:00. */
    public static boolean marketOpen(Calendar utcNow) {
        int dow = utcNow.get(Calendar.DAY_OF_WEEK);   // Sun=1 .. Sat=7
        int hour = utcNow.get(Calendar.HOUR_OF_DAY);
        if (dow == Calendar.SATURDAY) return false;
        if (dow == Calendar.SUNDAY) return hour >= 22;
        if (dow == Calendar.FRIDAY) return hour < 22;
        return true;
    }

    public static Calendar utcNow() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    public static ActiveChoice activeFor(String code, Calendar utcNow) {
        int[] ids = ACTIVES.get(code);
        if (ids == null) return null;
        if (ids[1] > 0 && !marketOpen(utcNow)) return new ActiveChoice(ids[1], true);
        return new ActiveChoice(ids[0], false);
    }

    public static JSONObject buildAuthenticate(String ssid) throws Exception {
        JSONObject msg = new JSONObject();
        msg.put("ssid", ssid);
        msg.put("protocol", 3);
        JSONObject root = new JSONObject();
        root.put("name", "authenticate");
        root.put("msg", msg);
        root.put("request_id", "");
        return root;
    }

    public static JSONObject buildSubscribe(int activeId) throws Exception {
        return buildSubscribeLike("subscribeMessage", activeId);
    }

    public static JSONObject buildUnsubscribe(int activeId) throws Exception {
        return buildSubscribeLike("unsubscribeMessage", activeId);
    }

    private static JSONObject buildSubscribeLike(String outer, int activeId) throws Exception {
        JSONObject filters = new JSONObject();
        filters.put("active_id", String.valueOf(activeId));
        filters.put("size", STREAM_SIZE);
        JSONObject params = new JSONObject();
        params.put("routingFilters", filters);
        JSONObject msg = new JSONObject();
        msg.put("name", "candle-generated");
        msg.put("params", params);
        JSONObject root = new JSONObject();
        root.put("name", outer);
        root.put("msg", msg);
        root.put("request_id", "");
        return root;
    }

    public static JSONObject buildGetCandles(int activeId, long toTs) throws Exception {
        JSONObject body = new JSONObject();
        body.put("active_id", activeId);
        body.put("size", HISTORY_SIZE);
        body.put("to", toTs);
        body.put("count", HISTORY_COUNT);
        JSONObject msg = new JSONObject();
        msg.put("name", "get-candles");
        msg.put("version", "2.0");
        msg.put("body", body);
        JSONObject root = new JSONObject();
        root.put("name", "sendMessage");
        root.put("msg", msg);
        root.put("request_id", "");
        return root;
    }

    public static JSONObject buildGetBalances() throws Exception {
        JSONObject msg = new JSONObject();
        msg.put("name", "get-balances");
        msg.put("version", "1.0");
        JSONObject root = new JSONObject();
        root.put("name", "sendMessage");
        root.put("msg", msg);
        root.put("request_id", "");
        return root;
    }

    public static JSONObject buildChangeBalance(Object balanceId) throws Exception {
        JSONObject msg = new JSONObject();
        msg.put("balance_id", balanceId);
        JSONObject root = new JSONObject();
        root.put("name", "api_profile_changebalance");
        root.put("msg", msg);
        root.put("request_id", "");
        return root;
    }

    /** IQ candle (from/open/close/min/max) -> CandlePoint, or null. */
    public static CandlePoint parseCandle(JSONObject msg) {
        try {
            if (msg == null) return null;
            long ts = msg.optLong("from", 0);
            double o = msg.optDouble("open", Double.NaN);
            double h = msg.optDouble("max", Double.NaN);
            double l = msg.optDouble("min", Double.NaN);
            double c = msg.optDouble("close", Double.NaN);
            if (ts < 1_000_000_000L || Double.isNaN(o) || Double.isNaN(h)
                    || Double.isNaN(l) || Double.isNaN(c)) return null;
            if (Math.min(Math.min(o, h), Math.min(l, c)) <= 0) return null;
            return new CandlePoint(ts, o, h, l, c);
        } catch (Exception e) {
            return null;
        }
    }

    /** "balances" msg: type 1 = real, type 4 = practice. */
    public static Balances parseBalances(JSONArray msg) {
        Balances out = new Balances();
        if (msg == null) return out;
        for (int i = 0; i < msg.length(); i++) {
            JSONObject b = msg.optJSONObject(i);
            if (b == null) continue;
            int type = b.optInt("type", 0);
            double amount = b.optDouble("amount", Double.NaN);
            Object id = b.opt("id");
            if (type == 1) {
                out.real = Double.isNaN(amount) ? null : amount;
                out.realId = id;
            } else if (type == 4) {
                out.practice = Double.isNaN(amount) ? null : amount;
                out.practiceId = id;
            }
        }
        return out;
    }

    /**
     * Extract the ssid from a login response: the "ssid" cookie first
     * (what the iqoptionapi library uses), JSON body data.ssid second.
     */
    public static String ssidFromLogin(int status, List<String> setCookies,
                                       String body) throws IqError {
        if (setCookies != null) {
            for (String header : setCookies) {
                if (header == null) continue;
                for (String part : header.split(";")) {
                    part = part.trim();
                    if (part.startsWith("ssid=") && part.length() > 5)
                        return part.substring(5);
                }
            }
        }
        try {
            JSONObject payload = new JSONObject(body);
            JSONObject data = payload.optJSONObject("data");
            if (data != null) {
                String ssid = data.optString("ssid", "");
                if (!ssid.isEmpty()) return ssid;
            }
        } catch (Exception ignored) {}
        if (status != 200)
            throw new IqError("IQ Option login fail (HTTP " + status
                    + ") — email/password check karo");
        throw new IqError("IQ Option login fail — ssid nahi mila "
                + "(email/password ghalat ho sakta hai)");
    }

    // ── callbacks / seams ─────────────────────────────────────────────

    public interface OnCandle { void onCandle(String assetCode, JSONObject point); }
    public interface OnStatus { void onStatus(String text); }
    public interface OnBalances { void onBalances(Double practice, Double real); }

    /** Login seam (unit tests inject a fake; production = HTTP login). */
    public interface LoginFn { String login(String email, String password) throws Exception; }

    /** Websocket seam: production = OkHttpWsLink, tests = fake. */
    public interface WsLink {
        void connect() throws Exception;
        void send(String text);
        /** Next message, null on timeout; throws WsClosed on close/failure. */
        String recv(long timeoutMs) throws Exception;
        void close();
    }

    public static class WsClosed extends Exception {
        public WsClosed(String msg) { super(msg); }
    }

    // ── OkHttp production link ────────────────────────────────────────

    static class OkHttpWsLink implements WsLink {
        private final OkHttpClient http;
        private final BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        private final CountDownLatch openLatch = new CountDownLatch(1);
        private volatile String openError;
        private volatile boolean closed;
        private WebSocket ws;

        OkHttpWsLink(OkHttpClient http) { this.http = http; }

        @Override public void connect() throws Exception {
            Request req = new Request.Builder().url(WS_URL)
                    .header("User-Agent", USER_AGENT)
                    .header("Origin", "https://iqoption.com")
                    .build();
            ws = http.newWebSocket(req, new WebSocketListener() {
                @Override public void onOpen(WebSocket webSocket, Response response) {
                    openLatch.countDown();
                }
                @Override public void onMessage(WebSocket webSocket, String text) {
                    inbox.offer(text);
                }
                @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                    closed = true;
                    openLatch.countDown();
                    inbox.offer(CLOSED_MARKER);
                }
                @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    closed = true;
                    openError = t.getMessage();
                    openLatch.countDown();
                    inbox.offer(CLOSED_MARKER);
                }
            });
            if (!openLatch.await(25, TimeUnit.SECONDS))
                throw new IqError("IQ Option websocket connect timeout (25s)");
            if (closed || openError != null)
                throw new IqError("IQ Option websocket connect fail: " + openError);
        }

        @Override public void send(String text) {
            if (ws != null) ws.send(text);
        }

        @Override public String recv(long timeoutMs) throws Exception {
            String item = inbox.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (item == null) return null;
            if (CLOSED_MARKER.equals(item)) throw new WsClosed("websocket closed");
            return item;
        }

        @Override public void close() {
            try { if (ws != null) ws.close(1000, "bye"); } catch (Exception ignored) {}
        }

        boolean isClosed() { return closed; }
        private static final String CLOSED_MARKER = "\u0000CLOSED";
    }

    // ── HTTP clients (system DNS, then DoH fallback) ──────────────────

    static OkHttpClient plainClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build();
    }

    /** Minimal DNS-over-HTTPS resolver (Google, then Cloudflare). */
    private static class DohDns implements Dns {
        private final OkHttpClient dohHttp = plainClient();
        @Override public List<InetAddress> lookup(String host) throws UnknownHostException {
            try {
                return Dns.SYSTEM.lookup(host);
            } catch (UnknownHostException first) {
                List<InetAddress> r = query("https://dns.google/resolve?name=" + host + "&type=A");
                if (r == null || r.isEmpty())
                    r = query("https://cloudflare-dns.com/dns-query?name=" + host + "&type=A");
                if (r == null || r.isEmpty()) throw first;
                Log.i(TAG, "DoH resolved " + host);
                return r;
            }
        }
        private List<InetAddress> query(String url) {
            try {
                Request req = new Request.Builder().url(url)
                        .header("Accept", "application/dns-json")
                        .header("User-Agent", USER_AGENT).get().build();
                try (Response resp = dohHttp.newCall(req).execute()) {
                    if (resp.code() != 200 || resp.body() == null) return null;
                    JSONArray ans = new JSONObject(resp.body().string()).optJSONArray("Answer");
                    if (ans == null) return null;
                    List<InetAddress> out = new ArrayList<>();
                    for (int i = 0; i < ans.length(); i++) {
                        JSONObject a = ans.optJSONObject(i);
                        if (a == null || a.optInt("type", 0) != 1) continue;
                        String ip = a.optString("data", "");
                        if (ip.isEmpty() || ip.contains(":")) continue;
                        try { out.add(InetAddress.getByName(ip)); } catch (Exception ignored) {}
                    }
                    return out;
                }
            } catch (Exception e) {
                return null;
            }
        }
    }

    static OkHttpClient dohClient() {
        return new OkHttpClient.Builder()
                .dns(new DohDns())
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build();
    }

    /** POST the login form with the given client; return the ssid. */
    static String httpLogin(OkHttpClient http, String email, String password) throws Exception {
        FormBody form = new FormBody.Builder()
                .add("identifier", email)
                .add("password", password)
                .add("token", "")
                .build();
        Request req = new Request.Builder().url(LOGIN_URL)
                .header("User-Agent", USER_AGENT)
                .header("Origin", "https://iqoption.com")
                .header("Referer", "https://iqoption.com/")
                .post(form).build();
        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            return ssidFromLogin(resp.code(), resp.headers("Set-Cookie"), body);
        }
    }

    // ── feed instance ─────────────────────────────────────────────────

    public final List<String> assets;
    public final boolean practice;
    public Balances balances = new Balances();

    private final String email, password;
    private final OnCandle onCandle;
    private final OnStatus onStatus;
    private final OnBalances onBalances;
    private final LoginFn loginFn;
    private final java.util.function.Supplier<WsLink> wsFactory;

    private volatile boolean stopped;
    private WsLink link;
    private OkHttpClient http;
    private final Map<String, Integer> idByCode = new LinkedHashMap<>();
    private final Map<Integer, String> codeById = new LinkedHashMap<>();
    private Double serverTs;

    public IqOptionFeed(String email, String password, List<String> assets,
                        boolean practice, OnCandle onCandle, OnStatus onStatus,
                        OnBalances onBalances) {
        this(email, password, assets, practice, onCandle, onStatus, onBalances, null, null);
    }

    IqOptionFeed(String email, String password, List<String> assets, boolean practice,
                 OnCandle onCandle, OnStatus onStatus, OnBalances onBalances,
                 LoginFn loginFn, java.util.function.Supplier<WsLink> wsFactory) {
        this.email = email;
        this.password = password;
        this.assets = new ArrayList<>(assets);
        this.practice = practice;
        this.onCandle = onCandle;
        this.onStatus = onStatus;
        this.onBalances = onBalances;
        this.loginFn = loginFn != null ? loginFn : (e, p) -> loginWithFallback(e, p);
        this.wsFactory = wsFactory != null ? wsFactory
                : () -> new OkHttpWsLink(http != null ? http : plainClient());
    }

    /** Login, retrying once with the DoH client when DNS fails. */
    String loginWithFallback(String email, String password) throws Exception {
        if (http == null) http = plainClient();
        try {
            return httpLogin(http, email, password);
        } catch (UnknownHostException dns) {
            Log.w(TAG, "DNS fail for IQ login — DoH client se retry");
            http = dohClient();
            return httpLogin(http, email, password);
        }
    }

    public void stop() {
        stopped = true;
        WsLink l = link;
        if (l != null) l.close();
    }

    /** Blocking session loop with reconnect (call from a worker thread). */
    public void run() {
        int attempt = 0;
        while (!stopped) {
            attempt++;
            try {
                session();
                break;
            } catch (IqError e) {
                emitStatus("❌ IQ Option: " + e.getMessage());
                break;
            } catch (Exception e) {
                if (stopped) break;
                emitStatus("⚠️ IQ Option connection toota (" + e.getClass().getSimpleName()
                        + ") — dobara connect ho raha hai…");
                try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
                if (attempt >= 20) {
                    emitStatus("❌ IQ Option: bohat dafa reconnect fail — ruk gaya");
                    break;
                }
            }
        }
    }

    private void session() throws Exception {
        emitStatus("IQ Option login ho raha hai…");
        String ssid = loginFn.login(email, password);
        WsLink ws = wsFactory.get();
        link = ws;
        ws.connect();
        try {
            authenticate(ws, ssid, 30_000);
            emitStatus("✅ IQ Option login OK — candles subscribe ho rahe hain…");
            ws.send(buildGetBalances().toString());
            idByCode.clear();
            codeById.clear();
            Calendar now = utcNow();
            for (String code : assets) {
                ActiveChoice choice = activeFor(code, now);
                if (choice == null) continue;
                idByCode.put(code, choice.id);
                codeById.put(choice.id, code);
                emitStatus("📡 " + code + ": IQ " + (choice.otc ? "OTC" : "Regular")
                        + " active #" + choice.id + " use ho raha hai");
            }
            for (String code : assets) {
                Integer id = idByCode.get(code);
                if (id != null) ws.send(buildSubscribe(id).toString());
            }
            warmup(ws);
            emitStatus("✅ IQ Option live: candles aa rahe hain — perfect candle ka intezar…");
            readLoop(ws);
        } finally {
            try {
                for (Integer id : idByCode.values())
                    ws.send(buildUnsubscribe(id).toString());
            } catch (Exception ignored) {}
            ws.close();
            link = null;
        }
    }

    /** Send authenticate; wait for the "authenticated" answer. */
    void authenticate(WsLink ws, String ssid, long timeoutMs) throws Exception {
        ws.send(buildAuthenticate(ssid).toString());
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !stopped) {
            String text = ws.recv(3000);
            if (text == null) continue;
            JSONObject msg = parse(text);
            if (msg == null) continue;
            String name = msg.optString("name", "");
            if ("timeSync".equals(name)) { noteTimesync(msg); continue; }
            if ("authenticated".equals(name)) {
                Object m = msg.opt("msg");
                if (Boolean.TRUE.equals(m)) return;
                throw new IqError("IQ Option ne login reject kiya (ssid expire — dobara try karo)");
            }
            handleCommon(ws, msg);
        }
        throw new IqError("IQ Option auth timeout (30s)");
    }

    /** Sequential history fetch (the "candles" answer has no active id). */
    private void warmup(WsLink ws) throws Exception {
        long toTs = serverTs != null ? (long) (double) serverTs
                : System.currentTimeMillis() / 1000;
        for (String code : assets) {
            if (stopped) return;
            Integer id = idByCode.get(code);
            if (id == null) continue;
            ws.send(buildGetCandles(id, toTs).toString());
            long deadline = System.currentTimeMillis() + 12_000;
            boolean got = false;
            while (System.currentTimeMillis() < deadline && !stopped) {
                String text = ws.recv(Math.max(500, Math.min(3000, deadline - System.currentTimeMillis())));
                if (text == null) continue;
                JSONObject msg = parse(text);
                if (msg == null) continue;
                if ("candles".equals(msg.optString("name", ""))) {
                    JSONArray candles = msg.optJSONObject("msg") != null
                            ? msg.getJSONObject("msg").optJSONArray("candles") : null;
                    if (candles != null) {
                        for (int i = 0; i < candles.length(); i++) {
                            CandlePoint p = parseCandle(candles.optJSONObject(i));
                            if (p == null) continue;
                            JSONObject point = new JSONObject();
                            point.put("timestamp", p.ts);
                            point.put("price", p.close);
                            emitPoint(code, point);
                        }
                    }
                    got = true;
                    break;
                }
                handleCommon(ws, msg);
            }
            if (!got) emitStatus("⚠️ " + code + ": history (warmup) nahi mili — "
                    + "live candles se hi warmup hoga");
        }
    }

    private void readLoop(WsLink ws) throws Exception {
        while (!stopped) {
            String text = ws.recv(35_000);
            if (text == null) continue;   // idle timeout slice; loop checks stopped
            JSONObject msg = parse(text);
            if (msg != null) handleCommon(ws, msg);
        }
    }

    // ── message handling ──────────────────────────────────────────────

    private void handleCommon(WsLink ws, JSONObject msg) {
        String name = msg.optString("name", "");
        try {
            switch (name) {
                case "candle-generated":
                    handleCandle(msg.optJSONObject("msg"));
                    break;
                case "balances":
                    balances = parseBalances(msg.optJSONArray("msg"));
                    applyBalanceMode(ws);
                    if (onBalances != null)
                        onBalances.onBalances(balances.practice, balances.real);
                    break;
                case "timeSync":
                    noteTimesync(msg);
                    break;
                case "heartbeat":
                    replyHeartbeat(ws, msg);
                    break;
                default:
                    break;   // profile / initialization-data / ... ignored
            }
        } catch (Exception e) {
            Log.w(TAG, "msg handle err: " + e.getMessage());
        }
    }

    private void handleCandle(JSONObject m) {
        if (m == null) return;
        if (m.optInt("size", STREAM_SIZE) != STREAM_SIZE) return;
        String code = codeById.get(m.optInt("active_id", -1));
        if (code == null) return;
        CandlePoint p = parseCandle(m);
        if (p == null) return;
        try {
            emitPoint(code, p.toJson());
        } catch (Exception ignored) {}
    }

    private void applyBalanceMode(WsLink ws) {
        Object wantId = practice ? balances.practiceId : balances.realId;
        if (wantId == null || wantId == JSONObject.NULL) return;
        try {
            ws.send(buildChangeBalance(wantId).toString());
        } catch (Exception ignored) {}
        StringBuilder txt = new StringBuilder("💰 IQ Option mode=")
                .append(practice ? "PRACTICE (DEMO)" : "REAL");
        if (balances.practice != null)
            txt.append(String.format(java.util.Locale.US, " | Practice=$%,.2f", balances.practice));
        if (balances.real != null)
            txt.append(String.format(java.util.Locale.US, " | Real=$%,.2f", balances.real));
        txt.append(" (sirf maloomat — bot trade nahi lagata)");
        emitStatus(txt.toString());
    }

    private void noteTimesync(JSONObject msg) {
        double ts = msg.optDouble("msg", Double.NaN);
        if (!Double.isNaN(ts)) serverTs = ts > 1e11 ? ts / 1000.0 : ts;
    }

    private void replyHeartbeat(WsLink ws, JSONObject msg) {
        try {
            Object hb = msg.opt("msg");
            long hbVal;
            if (hb instanceof Number) hbVal = ((Number) hb).longValue();
            else if (hb instanceof JSONObject) hbVal = ((JSONObject) hb).optLong("heartbeatTime", 0);
            else return;
            long userMs = (long) ((serverTs != null ? serverTs
                    : System.currentTimeMillis() / 1000.0) * 1000);
            JSONObject inner = new JSONObject();
            inner.put("heartbeatTime", hbVal);
            inner.put("userTime", userMs);
            JSONObject wrap = new JSONObject();
            wrap.put("msg", inner);
            JSONObject root = new JSONObject();
            root.put("name", "heartbeat");
            root.put("msg", wrap);
            root.put("request_id", "");
            ws.send(root.toString());
        } catch (Exception ignored) {}
    }

    // ── plumbing ──────────────────────────────────────────────────────

    private static JSONObject parse(String text) {
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            return null;
        }
    }

    private void emitStatus(String text) {
        if (onStatus == null) return;
        try { onStatus.onStatus(text); } catch (Exception ignored) {}
    }

    private void emitPoint(String code, JSONObject point) {
        if (onCandle == null) return;
        try { onCandle.onCandle(code, point); } catch (Exception ignored) {}
    }

    // ── one-shot login verification (LoginActivity) ───────────────────

    /**
     * Verify an IQ Option login end-to-end (HTTP login + websocket
     * authenticate), then close. Returns the account balances.
     */
    public static Balances verifyLogin(String email, String password) throws Exception {
        IqOptionFeed feed = new IqOptionFeed(email, password,
                new ArrayList<>(), true, null, null, null);
        String ssid = feed.loginWithFallback(email, password);
        WsLink ws = new OkHttpWsLink(feed.http != null ? feed.http : plainClient());
        ws.connect();
        try {
            feed.authenticate(ws, ssid, 30_000);
            ws.send(buildGetBalances().toString());
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                String text = ws.recv(2000);
                if (text == null) continue;
                JSONObject msg = parse(text);
                if (msg == null) continue;
                if ("balances".equals(msg.optString("name", ""))) {
                    feed.balances = parseBalances(msg.optJSONArray("msg"));
                    break;
                }
                if ("timeSync".equals(msg.optString("name", ""))) feed.noteTimesync(msg);
            }
            return feed.balances;
        } finally {
            ws.close();
        }
    }
}
