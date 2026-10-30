"""IQ Option feed for Hamza Signal Bot v1.2 (login + websocket candles).

Hamza's correction (2026-10-30): the signal bot must work for IQ Option
AND Quotex AND any other binary platform — not Quotex only. v1.2 adds
IQ Option as a third feed source next to Quotex and Real Market.

Protocol (taken from the iqoptionapi library that Hamza IQ Bot v1.0
used successfully — same login, same websocket, READ-ONLY subset):
  1. HTTP login: POST https://auth.iqoption.com/api/v2/login
     form fields {identifier, password, token} -> session id ("ssid")
     arrives as a response COOKIE.
  2. Websocket: wss://iqoption.com/echo/websocket
     -> {"name":"authenticate","msg":{"ssid":SSID,"protocol":3}}
     <- {"name":"authenticated", ...} then "profile" / "balances".
  3. History (warmup): {"name":"sendMessage","msg":{"name":"get-candles",
     "version":"2.0","body":{"active_id":N,"size":60,"to":T,"count":K}}}
     <- {"name":"candles","msg":{"candles":[...]}} (asked per asset,
     one at a time, so each answer can be matched to its asset).
  4. Live candles: {"name":"subscribeMessage","msg":{"name":
     "candle-generated","params":{"routingFilters":{"active_id":"N",
     "size":15}}}} <- {"name":"candle-generated","msg":{...}} stream.
     Size 15s divides both engine timeframes (30s and 60s), so the
     engine composes both from this one stream, exactly like it
     composes them from Quotex ticks / Yahoo points.
  5. Balance mode: balances arrive in the "balances" message
     (type 1 = real, type 4 = practice); the chosen balance id is set
     with {"name":"api_profile_changebalance","msg":{"balance_id":N}}
     — PRACTICE by default. This only selects which account's candles
     context is used; it places NOTHING.

Assets (iqoptionapi constants.ACTIVES): each pair has a regular
active id and — except AUD/USD — an OTC active id. The forex market
is closed roughly Fri 22:00 UTC -> Sun 22:00 UTC; outside market
hours the OTC active is used (IQ OTC trades 24/7), inside them the
regular active. The GUI shows which variant is live in the log.

Everything network-facing is Python standard library ONLY — the HTTP
login uses urllib and the websocket is a small in-module RFC 6455
client — so the frozen EXE needs no new dependency (same lesson as
realmarket_feed.py in v1.1: CI has no `requests`).

SIGNAL ONLY: this module can log in, read balances, fetch candle
history and subscribe to live candles. There is deliberately NO
order/buy code path anywhere in it.
"""

import base64
import http.cookiejar
import json
import os
import socket
import ssl
import struct
import threading
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from typing import Callable, Dict, List, Optional, Tuple

SOURCE_ID = "iqoption"
SOURCE_LABEL = "IQ Option"

LOGIN_URL = "https://auth.iqoption.com/api/v2/login"
WS_HOST = "iqoption.com"
WS_PATH = "/echo/websocket"
USER_AGENT = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
              "AppleWebKit/537.36 (KHTML, like Gecko) "
              "Chrome/126.0.0.0 Safari/537.36")

# GUI asset code -> (regular active_id, OTC active_id or None).
# IDs copied from iqoptionapi.constants.ACTIVES (the library Hamza
# IQ Bot v1.0 ran on). AUD/USD has no OTC entry in those constants.
ACTIVES: Dict[str, Tuple[int, Optional[int]]] = {
    "EURUSD": (1, 76),
    "GBPUSD": (5, 81),
    "USDJPY": (6, 85),
    "EURGBP": (2, 77),
    "AUDUSD": (99, None),
    "EURJPY": (4, 79),
}

STREAM_SIZE = 15          # seconds per streamed candle (divides 30 & 60)
HISTORY_SIZE = 60         # warmup candle size
HISTORY_COUNT = 130       # warmup candles per asset (~2h of closes)


class IqError(Exception):
    """Any IQ Option login/connection failure (message is user-safe)."""


# ── pure helpers (unit-tested) ────────────────────────────────────────

def market_open(now: Optional[datetime] = None) -> bool:
    """Approximate forex market hours (UTC): open Sun 22:00 -> Fri 22:00.

    Only used to pick regular vs OTC actives; being an hour off at the
    edges is harmless (worst case a quiet stream, never wrong data).
    """
    now = now or datetime.now(timezone.utc)
    wd = now.weekday()  # Mon=0 .. Sun=6
    if wd == 5:                     # Saturday: closed all day
        return False
    if wd == 6:                     # Sunday: opens 22:00 UTC
        return now.hour >= 22
    if wd == 4 and now.hour >= 22:  # Friday: closes 22:00 UTC
        return False
    return True


def active_for(code: str, now: Optional[datetime] = None) -> Tuple[int, bool]:
    """(active_id, is_otc) for a GUI asset code at the given UTC time."""
    regular, otc = ACTIVES[code]
    if otc is not None and not market_open(now):
        return otc, True
    return regular, False


def build_authenticate(ssid: str) -> dict:
    return {"name": "authenticate",
            "msg": {"ssid": ssid, "protocol": 3}, "request_id": ""}


def build_subscribe(active_id: int, size: int = STREAM_SIZE) -> dict:
    return {"name": "subscribeMessage",
            "msg": {"name": "candle-generated",
                    "params": {"routingFilters": {"active_id": str(active_id),
                                                  "size": int(size)}}},
            "request_id": ""}


def build_unsubscribe(active_id: int, size: int = STREAM_SIZE) -> dict:
    msg = build_subscribe(active_id, size)
    msg["name"] = "unsubscribeMessage"
    return msg


def build_get_candles(active_id: int, to_ts: int, count: int = HISTORY_COUNT,
                      size: int = HISTORY_SIZE) -> dict:
    return {"name": "sendMessage",
            "msg": {"name": "get-candles", "version": "2.0",
                    "body": {"active_id": int(active_id), "size": int(size),
                             "to": int(to_ts), "count": int(count)}},
            "request_id": ""}


def build_get_balances() -> dict:
    return {"name": "sendMessage",
            "msg": {"name": "get-balances", "version": "1.0"},
            "request_id": ""}


def build_change_balance(balance_id) -> dict:
    return {"name": "api_profile_changebalance",
            "msg": {"balance_id": balance_id}, "request_id": ""}


def parse_candle_msg(msg) -> Optional[dict]:
    """Normalise one IQ candle dict to an engine point, or None.

    IQ candle fields: from (bucket start, s), open, close, min, max.
    Engine points use open/high/low/close, so min->low and max->high.
    """
    try:
        if not isinstance(msg, dict):
            return None
        ts = int(msg["from"])
        o = float(msg["open"]); h = float(msg["max"])
        l = float(msg["min"]); c = float(msg["close"])
        if ts < 1_000_000_000 or min(o, h, l, c) <= 0:
            return None
        return {"timestamp": ts, "open": o, "high": h, "low": l, "close": c}
    except (KeyError, TypeError, ValueError):
        return None


def parse_balances(msg) -> dict:
    """Parse a "balances" msg -> amounts/ids by kind.

    Balance types in the IQ protocol: 1 = real, 4 = practice.
    Returns {"real": amount|None, "practice": amount|None,
             "real_id": id|None, "practice_id": id|None}.
    """
    out = {"real": None, "practice": None, "real_id": None, "practice_id": None}
    if not isinstance(msg, list):
        return out
    for b in msg:
        if not isinstance(b, dict):
            continue
        try:
            amount = float(b.get("amount"))
        except (TypeError, ValueError):
            amount = None
        if b.get("type") == 1:
            out["real"] = amount
            out["real_id"] = b.get("id")
        elif b.get("type") == 4:
            out["practice"] = amount
            out["practice_id"] = b.get("id")
    return out


def ssid_from_login(status: int, set_cookies: List[str], body: bytes) -> str:
    """Extract the ssid from a login response, or raise IqError.

    Primary source (what the iqoptionapi library uses): the "ssid"
    cookie. Fallback: a JSON body carrying data.ssid.
    """
    for header in set_cookies or []:
        for part in header.split(";"):
            part = part.strip()
            if part.startswith("ssid=") and len(part) > 5:
                return part[5:]
    try:
        payload = json.loads(body.decode("utf-8", "replace"))
        ssid = payload["data"]["ssid"]
        if ssid:
            return str(ssid)
    except Exception:
        pass
    if status != 200:
        raise IqError(f"IQ Option login fail (HTTP {status}) — "
                      "email/password check karo")
    raise IqError("IQ Option login fail — ssid nahi mila "
                  "(email/password ghalat ho sakta hai)")


# ── HTTP login (stdlib urllib) ────────────────────────────────────────

def http_login(email: str, password: str, timeout: float = 25.0) -> str:
    """POST the login form; return the ssid. Raises IqError."""
    form = urllib.parse.urlencode(
        {"identifier": email, "password": password, "token": ""}).encode()
    req = urllib.request.Request(LOGIN_URL, data=form, method="POST")
    req.add_header("User-Agent", USER_AGENT)
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    req.add_header("Origin", "https://iqoption.com")
    req.add_header("Referer", "https://iqoption.com/")
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(jar))
    try:
        with opener.open(req, timeout=timeout) as resp:
            status = resp.status
            body = resp.read()
            cookies = [c.name + "=" + c.value for c in jar]
    except urllib.error.HTTPError as e:
        status = e.code
        try:
            body = e.read()
        except Exception:
            body = b""
        cookies = []
        if e.headers is not None:
            cookies = e.headers.get_all("Set-Cookie") or []
        # A 4xx with an ssid cookie still counts as logged in; without
        # one, ssid_from_login raises the proper IqError below.
        try:
            return ssid_from_login(status, cookies, body)
        except IqError:
            raise IqError(f"IQ Option login fail (HTTP {status}) — "
                          "email/password check karo")
    except Exception as e:
        raise IqError(f"IQ Option login fail: {type(e).__name__} — "
                      "internet connection check karo")
    return ssid_from_login(status, cookies, body)


# ── minimal RFC 6455 websocket client (stdlib only) ───────────────────

class _WsClient:
    """Blocking text-frame websocket client (client-masked frames).

    recv_text() returns the next complete text message, answers
    ping frames with pong itself, and returns None on close/EOF.
    """

    def __init__(self, host: str, path: str, timeout: float = 35.0):
        self.host = host
        self.path = path
        self.timeout = timeout
        self.sock: Optional[socket.socket] = None
        self._buf = b""
        self._frag = bytearray()
        self._frag_opcode: Optional[int] = None

    def _open_tcp(self) -> socket.socket:
        """TCP connection to the host, honouring https_proxy env vars
        (HTTP CONNECT tunnel) when present — Hamza's PC normally has
        no proxy and connects directly; proxied networks still work.
        """
        proxy = None
        no_proxy = (os.environ.get("no_proxy") or os.environ.get("NO_PROXY") or "")
        if self.host not in [h.strip() for h in no_proxy.split(",")]:
            for var in ("https_proxy", "HTTPS_PROXY", "all_proxy", "ALL_PROXY"):
                if os.environ.get(var):
                    proxy = os.environ[var]
                    break
        if not proxy:
            return socket.create_connection((self.host, 443), timeout=self.timeout)
        parsed = urllib.parse.urlparse(proxy)
        if parsed.scheme not in ("http", "") or not parsed.hostname:
            return socket.create_connection((self.host, 443), timeout=self.timeout)
        proxy_host = parsed.hostname
        proxy_port = parsed.port or 3128
        sock = socket.create_connection((proxy_host, proxy_port),
                                        timeout=self.timeout)
        lines = [f"CONNECT {self.host}:443 HTTP/1.1",
                 f"Host: {self.host}:443"]
        if parsed.username:
            creds = f"{urllib.parse.unquote(parsed.username)}:" \
                    f"{urllib.parse.unquote(parsed.password or '')}"
            lines.append("Proxy-Authorization: Basic " +
                         base64.b64encode(creds.encode()).decode())
        sock.sendall(("\r\n".join(lines) + "\r\n\r\n").encode())
        resp = b""
        while b"\r\n\r\n" not in resp:
            chunk = sock.recv(4096)
            if not chunk:
                sock.close()
                raise IqError("IQ Option websocket: proxy ne jawab nahi diya")
            resp += chunk
            if len(resp) > 65536:
                sock.close()
                raise IqError("IQ Option websocket: proxy jawab bohat bara")
        status_line = resp.split(b"\r\n", 1)[0].decode("latin1")
        if " 200" not in status_line:
            sock.close()
            raise IqError(f"IQ Option websocket: proxy CONNECT fail: {status_line}")
        return sock

    def connect(self) -> None:
        ctx = ssl.create_default_context()
        raw = self._open_tcp()
        self.sock = ctx.wrap_socket(raw, server_hostname=self.host)
        self.sock.settimeout(self.timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        req = (
            f"GET {self.path} HTTP/1.1\r\n"
            f"Host: {self.host}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            f"Origin: https://{self.host}\r\n"
            f"User-Agent: {USER_AGENT}\r\n"
            "\r\n"
        )
        self.sock.sendall(req.encode())
        resp = b""
        while b"\r\n\r\n" not in resp:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise IqError("IQ Option websocket: handshake par jawab nahi")
            resp += chunk
            if len(resp) > 65536:
                raise IqError("IQ Option websocket: handshake jawab bohat bara")
        head, _, rest = resp.partition(b"\r\n\r\n")
        status_line = head.split(b"\r\n", 1)[0].decode("latin1")
        if " 101" not in status_line:
            raise IqError(f"IQ Option websocket connect fail: {status_line}")
        self._buf = rest

    def _read_exact(self, n: int) -> bytes:
        while len(self._buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise ConnectionError("websocket closed by server")
            self._buf += chunk
        out, self._buf = self._buf[:n], self._buf[n:]
        return out

    def _send_frame(self, opcode: int, payload: bytes) -> None:
        mask = os.urandom(4)
        header = bytearray([0x80 | opcode])
        ln = len(payload)
        if ln < 126:
            header.append(0x80 | ln)
        elif ln < 65536:
            header.append(0x80 | 126)
            header += struct.pack(">H", ln)
        else:
            header.append(0x80 | 127)
            header += struct.pack(">Q", ln)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(header) + mask + masked)

    def send_text(self, text: str) -> None:
        self._send_frame(0x1, text.encode("utf-8"))

    def recv_text(self) -> Optional[str]:
        while True:
            b1, b2 = self._read_exact(2)
            fin = bool(b1 & 0x80)
            opcode = b1 & 0x0F
            masked = bool(b2 & 0x80)
            ln = b2 & 0x7F
            if ln == 126:
                ln = struct.unpack(">H", self._read_exact(2))[0]
            elif ln == 127:
                ln = struct.unpack(">Q", self._read_exact(8))[0]
            mask = self._read_exact(4) if masked else None
            payload = self._read_exact(ln) if ln else b""
            if mask:
                payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
            if opcode == 0x9:            # ping -> pong
                self._send_frame(0xA, payload)
                continue
            if opcode == 0xA:            # pong
                continue
            if opcode == 0x8:            # close
                try:
                    self._send_frame(0x8, b"")
                except Exception:
                    pass
                return None
            if opcode in (0x1, 0x2):     # new text/binary message
                self._frag = bytearray(payload)
                self._frag_opcode = opcode
            elif opcode == 0x0:          # continuation
                self._frag += payload
            else:
                continue
            if fin:
                data = bytes(self._frag)
                self._frag = bytearray()
                return data.decode("utf-8", "replace")

    def close(self) -> None:
        try:
            if self.sock:
                self._send_frame(0x8, b"")
        except Exception:
            pass
        try:
            if self.sock:
                self.sock.close()
        except Exception:
            pass
        self.sock = None


# ── the feed ──────────────────────────────────────────────────────────

class IqOptionFeed:
    """Login + candle feed. Mirrors RealMarketFeed's callback shape.

    Callbacks:
      on_candle(asset_code, point) - point is an engine-ready dict
        {"timestamp","open","high","low","close"} for live candles,
        or {"timestamp","price"} for history warmup closes.
      on_status(text)              - human-readable status (optional).
      on_balances(practice, real)  - account balances for display
        (amounts may be None when the server did not send them).

    Lifecycle: run() blocks (call it in a thread) and reconnects with
    backoff until stop() is called. login_fn / ws_factory are
    injectable so the session logic is unit-testable offline.
    """

    def __init__(self,
                 email: str,
                 password: str,
                 assets: List[str],
                 practice: bool = True,
                 on_candle: Optional[Callable[[str, dict], None]] = None,
                 on_status: Optional[Callable[[str], None]] = None,
                 on_balances: Optional[Callable[[Optional[float],
                                                 Optional[float]], None]] = None,
                 login_fn: Optional[Callable[[str, str], str]] = None,
                 ws_factory: Optional[Callable[[], _WsClient]] = None):
        self.email = email
        self.password = password
        self.assets = list(assets)
        self.practice = bool(practice)
        self.on_candle = on_candle
        self.on_status = on_status
        self.on_balances = on_balances
        self._login_fn = login_fn or (lambda e, p: http_login(e, p))
        self._ws_factory = ws_factory or (lambda: _WsClient(WS_HOST, WS_PATH))
        self._stop = threading.Event()
        self._ws: Optional[_WsClient] = None
        self._send_lock = threading.Lock()
        self._id_by_code: Dict[str, int] = {}
        self._code_by_id: Dict[int, str] = {}
        self.server_ts: Optional[float] = None
        self.balances: dict = {"real": None, "practice": None,
                               "real_id": None, "practice_id": None}

    # -- lifecycle ------------------------------------------------------

    def stop(self) -> None:
        self._stop.set()
        ws = self._ws
        if ws is not None:
            ws.close()

    def run(self) -> None:
        attempt = 0
        while not self._stop.is_set():
            attempt += 1
            try:
                self._session()
                break  # clean stop requested inside the session
            except IqError as e:
                self._emit_status(f"❌ IQ Option: {e}")
                break  # login/auth problems: retrying will not help
            except Exception as e:
                if self._stop.is_set():
                    break
                self._emit_status(
                    f"⚠️ IQ Option connection toota ({type(e).__name__}) — "
                    "dobara connect ho raha hai…")
                if self._stop.wait(5.0):
                    break
                if attempt >= 20:
                    self._emit_status("❌ IQ Option: bohat dafa reconnect fail — ruk gaya")
                    break

    # -- session --------------------------------------------------------

    def _session(self) -> None:
        self._emit_status("IQ Option login ho raha hai…")
        ssid = self._login_fn(self.email, self.password)
        ws = self._ws_factory()
        self._ws = ws
        ws.connect()
        try:
            self._send(ws, build_authenticate(ssid))
            self._wait_authenticated(ws)
            self._emit_status("✅ IQ Option login OK — candles subscribe ho rahe hain…")
            self._send(ws, build_get_balances())
            # actives: regular in market hours, OTC when the market is shut
            self._id_by_code = {}
            self._code_by_id = {}
            for code in self.assets:
                active_id, is_otc = active_for(code)
                self._id_by_code[code] = active_id
                self._code_by_id[active_id] = code
                self._emit_status(
                    f"📡 {code}: IQ {'OTC' if is_otc else 'Regular'} active "
                    f"#{active_id} use ho raha hai")
            for code in self.assets:
                self._send(ws, build_subscribe(self._id_by_code[code]))
            self._warmup(ws)
            self._emit_status("✅ IQ Option live: candles aa rahe hain — "
                              "perfect candle ka intezar…")
            self._read_loop(ws)
        finally:
            try:
                for code in self.assets:
                    if code in self._id_by_code:
                        self._send(ws, build_unsubscribe(self._id_by_code[code]))
            except Exception:
                pass
            ws.close()
            self._ws = None

    def _wait_authenticated(self, ws: _WsClient) -> None:
        deadline = time.time() + 30
        while time.time() < deadline and not self._stop.is_set():
            msg = self._recv_json(ws)
            if msg is None:
                raise IqError("websocket band ho gaya (auth se pehle)")
            name = msg.get("name")
            if name == "timeSync":
                self._note_timesync(msg)
                continue
            if name == "authenticated":
                if msg.get("msg"):
                    return
                raise IqError("IQ Option ne login reject kiya "
                              "(ssid expire — dobara try karo)")
            # anything else (profile/balances...) may arrive first; the
            # balances message is handled here so it is not lost.
            self._handle_common(msg)
        raise IqError("IQ Option auth timeout (30s)")

    def _warmup(self, ws: _WsClient) -> None:
        """Fetch HISTORY_SIZE minutes of past closes per asset.

        Asked one asset at a time (the "candles" answer carries no
        active id), each with its own timeout. History candles are fed
        as plain close points: the engine builds doji warmup candles —
        closes are right (EMA/RSI warm up) but no fake signals fire.
        Candle stream messages arriving meanwhile are processed too.
        """
        to_ts = int(self.server_ts or time.time())
        for code in self.assets:
            if self._stop.is_set():
                return
            active_id = self._id_by_code[code]
            self._send(ws, build_get_candles(active_id, to_ts))
            deadline = time.time() + 12
            got = False
            while time.time() < deadline and not self._stop.is_set():
                # short-poll via socket timeout juggling: recv_text is
                # blocking with the client timeout; use a small slice
                # by temporarily shrinking it.
                msg = self._recv_json(ws, soft_timeout=min(3.0, max(0.5, deadline - time.time())))
                if msg is None:
                    return
                if msg.get("name") == "candles":
                    candles = (msg.get("msg") or {}).get("candles") or []
                    for c in candles:
                        point = parse_candle_msg(c)
                        if point:
                            self._emit_point(code, {"timestamp": point["timestamp"],
                                                    "price": point["close"]})
                    got = True
                    break
                self._handle_common(msg)
            if not got:
                self._emit_status(f"⚠️ {code}: history (warmup) nahi mili — "
                                  "live candles se hi warmup hoga")

    def _read_loop(self, ws: _WsClient) -> None:
        while not self._stop.is_set():
            msg = self._recv_json(ws)
            if msg is None:
                if self._stop.is_set():
                    return
                raise ConnectionError("websocket closed")
            self._handle_common(msg)

    # -- message handling -------------------------------------------------

    def _handle_common(self, msg: dict) -> None:
        name = msg.get("name")
        if name == "candle-generated":
            self._handle_candle(msg.get("msg") or {})
        elif name == "balances":
            self.balances = parse_balances(msg.get("msg"))
            self._apply_balance_mode()
            if self.on_balances:
                try:
                    self.on_balances(self.balances["practice"],
                                     self.balances["real"])
                except Exception:
                    pass
        elif name == "timeSync":
            self._note_timesync(msg)
        elif name == "heartbeat":
            self._reply_heartbeat(msg)
        # "profile", "initialization-data", ... : not needed, ignored.

    def _handle_candle(self, m: dict) -> None:
        try:
            if int(m.get("size", STREAM_SIZE)) != STREAM_SIZE:
                return
            code = self._code_by_id.get(int(m.get("active_id")))
        except (TypeError, ValueError):
            return
        if code is None:
            return
        point = parse_candle_msg(m)
        if point:
            self._emit_point(code, point)

    def _apply_balance_mode(self) -> None:
        want = "practice_id" if self.practice else "real_id"
        balance_id = self.balances.get(want)
        if balance_id is None:
            return
        ws = self._ws
        if ws is not None:
            try:
                self._send(ws, build_change_balance(balance_id))
            except Exception:
                pass
        mode = "PRACTICE (DEMO)" if self.practice else "REAL"
        p, r = self.balances["practice"], self.balances["real"]
        txt = f"💰 IQ Option mode={mode}"
        if p is not None:
            txt += f" | Practice=${p:,.2f}"
        if r is not None:
            txt += f" | Real=${r:,.2f}"
        txt += " (sirf maloomat — bot trade nahi lagata)"
        self._emit_status(txt)

    def _note_timesync(self, msg: dict) -> None:
        try:
            ts = float(msg.get("msg"))
            self.server_ts = ts / 1000.0 if ts > 1e11 else ts
        except (TypeError, ValueError):
            pass

    def _reply_heartbeat(self, msg: dict) -> None:
        ws = self._ws
        if ws is None:
            return
        try:
            hb = msg.get("msg")
            hb_val = int(hb) if not isinstance(hb, dict) else int(hb.get("heartbeatTime", 0))
            user_ms = int((self.server_ts or time.time()) * 1000)
            # Mirrors the iqoptionapi library's heartbeat reply exactly.
            self._send(ws, {"name": "heartbeat",
                            "msg": {"msg": {"heartbeatTime": hb_val,
                                            "userTime": user_ms}},
                            "request_id": ""})
        except Exception:
            pass

    # -- plumbing ---------------------------------------------------------

    def _emit_status(self, text: str) -> None:
        if self.on_status:
            try:
                self.on_status(text)
            except Exception:
                pass

    def _emit_point(self, code: str, point: dict) -> None:
        if self.on_candle:
            try:
                self.on_candle(code, point)
            except Exception:
                pass

    def _send(self, ws: _WsClient, payload: dict) -> None:
        with self._send_lock:
            ws.send_text(json.dumps(payload))

    def _recv_json(self, ws: _WsClient, soft_timeout: Optional[float] = None) -> Optional[dict]:
        if soft_timeout is not None and ws.sock is not None:
            old = ws.sock.gettimeout()
            ws.sock.settimeout(soft_timeout)
            try:
                text = ws.recv_text()
            except (socket.timeout, TimeoutError):
                return {}
            finally:
                try:
                    ws.sock.settimeout(old)
                except Exception:
                    pass
        else:
            text = ws.recv_text()
        if text is None:
            return None
        try:
            data = json.loads(text)
        except (TypeError, ValueError):
            return {}
        return data if isinstance(data, dict) else {}
