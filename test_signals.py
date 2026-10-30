"""Offline unit tests for signals_engine.py — run: python test_signals.py"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from signals_engine import SignalEngine

FAILED = []
B = 1700000040  # realistic unix base, divisible by 30 and 60

def check(name, cond):
    print(("PASS" if cond else "FAIL"), "-", name)
    if not cond:
        FAILED.append(name)

def make_engine(strategies=("perfect",), tfs=(60,), threshold=85.0,
                trend_filter=False, rsi_filter=False, max_signals=0):
    sigs, res, blocked = [], [], []
    eng = SignalEngine({"EURUSD_otc": "EUR/USD OTC"}, list(tfs),
                       threshold_pct=threshold, strategies=strategies,
                       trend_filter=trend_filter, rsi_filter=rsi_filter,
                       max_signals=max_signals,
                       on_signal=sigs.append, on_result=res.append,
                       on_blocked=lambda k, d, w: blocked.append((k, d, w)))
    return eng, sigs, res, blocked

def feed_candle(eng, start, o, h, l, c, tf=60):
    eng.feed("EURUSD_otc", {"timestamp": start, "open": o, "high": h, "low": l, "close": c})
    eng.feed("EURUSD_otc", {"timestamp": start + tf, "price": c})  # closes the candle

# ── PERFECT CANDLE ────────────────────────────────────────────────────
eng, sigs, _, _ = make_engine()
feed_candle(eng, B, 1.0000, 1.00100, 0.99990, 1.00095)  # body 86.4% of range
check("perfect green -> CALL", len(sigs) == 1 and sigs[0].direction == "call" and sigs[0].kind == "perfect")
check("detail shows body 86%", sigs and "86%" in sigs[0].detail)

eng, sigs, _, _ = make_engine()
feed_candle(eng, B, 1.00100, 1.00105, 0.99995, 1.00005)
check("perfect red -> PUT", len(sigs) == 1 and sigs[0].direction == "put")

eng, sigs, _, _ = make_engine()
feed_candle(eng, B, 1.0000, 1.00110, 0.99990, 1.00060)  # body 50%
check("small body -> no signal", len(sigs) == 0)

eng, sigs, _, _ = make_engine()
feed_candle(eng, B, 1.00050, 1.00110, 0.99990, 1.00050)  # doji
check("doji -> no signal", len(sigs) == 0)

eng, sigs, _, _ = make_engine(threshold=70.0)
feed_candle(eng, B, 1.0000, 1.00100, 0.99995, 1.00080)  # body 76.2%
check("76% body passes at 70% threshold", len(sigs) == 1)

# ── RESULT TRACKING ───────────────────────────────────────────────────
eng, sigs, res, _ = make_engine()
feed_candle(eng, B, 1.0000, 1.00100, 0.99990, 1.00095)
feed_candle(eng, B + 60, 1.00095, 1.00150, 1.00090, 1.00120)
check("next candle up -> WIN", len(res) == 1 and res[0].result == "WIN")
check("stats: 1 win, 100%", eng.stats.wins == 1 and eng.stats.win_pct == 100.0)
check("per-kind stats: perfect 1 win", eng.stats_by_kind["perfect"].wins == 1)

eng, sigs, res, _ = make_engine()
feed_candle(eng, B, 1.0000, 1.00100, 0.99990, 1.00095)
feed_candle(eng, B + 60, 1.00095, 1.00100, 0.99980, 0.99990)
check("next candle down -> LOSS", len(res) == 1 and res[0].result == "LOSS")
check("win% 0 after LOSS", eng.stats.win_pct == 0.0)

# ── TICK-BUILT 30s CANDLES ────────────────────────────────────────────
eng, sigs, res, _ = make_engine(tfs=(30,))
ticks = [(B, 1.0000), (B + 5, 1.0004), (B + 10, 1.0008), (B + 20, 1.0010),
         (B + 29, 1.00105), (B + 30, 1.00105),
         (B + 35, 1.0012), (B + 50, 1.0014), (B + 59, 1.0013), (B + 60, 1.0013)]
for ts, p in ticks:
    eng.feed("EURUSD_otc", {"time": ts, "price": p})
check("tick-built 30s perfect -> CALL", len(sigs) == 1 and sigs[0].timeframe == 30)
check("tick-built target up -> WIN", len(res) == 1 and res[0].result == "WIN")

# ── MAX SIGNALS ───────────────────────────────────────────────────────
eng, sigs, _, _ = make_engine(max_signals=1)
feed_candle(eng, B, 1.0000, 1.00100, 0.99990, 1.00095)
feed_candle(eng, B + 60, 1.0000, 1.00100, 0.99990, 1.00095)
check("max_signals=1 caps at 1", len(sigs) == 1 and eng.issued == 1)

# ── ENGULFING ─────────────────────────────────────────────────────────
eng, sigs, _, _ = make_engine(strategies=("engulfing",))
feed_candle(eng, B, 1.0010, 1.0012, 1.0000, 1.0002)        # red candle
feed_candle(eng, B + 60, 1.0001, 1.0013, 0.9999, 1.0011)   # green engulfs body
check("bullish engulfing -> CALL", len(sigs) == 1 and sigs[0].kind == "engulfing" and sigs[0].direction == "call")

eng, sigs, _, _ = make_engine(strategies=("engulfing",))
feed_candle(eng, B, 1.0002, 1.0012, 1.0000, 1.0010)        # green candle
feed_candle(eng, B + 60, 1.0011, 1.0013, 0.9999, 1.0001)   # red engulfs body
check("bearish engulfing -> PUT", len(sigs) == 1 and sigs[0].direction == "put")

eng, sigs, _, _ = make_engine(strategies=("engulfing",))
feed_candle(eng, B, 1.0010, 1.0012, 1.0000, 1.0002)        # red candle
feed_candle(eng, B + 60, 1.0003, 1.0011, 0.9999, 1.0009)   # green but close < prev open
check("partial cover -> no engulfing", len(sigs) == 0)

# ── PIN BAR ───────────────────────────────────────────────────────────
eng, sigs, _, _ = make_engine(strategies=("pinbar",))
feed_candle(eng, B, 1.00080, 1.00090, 0.99900, 1.00085)    # hammer: long lower wick
check("hammer -> CALL pinbar", len(sigs) == 1 and sigs[0].kind == "pinbar" and sigs[0].direction == "call")

eng, sigs, _, _ = make_engine(strategies=("pinbar",))
feed_candle(eng, B, 1.00020, 1.00190, 1.00010, 1.00015)    # shooting star: long upper wick
check("shooting star -> PUT pinbar", len(sigs) == 1 and sigs[0].direction == "put")

eng, sigs, _, _ = make_engine(strategies=("pinbar",))
feed_candle(eng, B, 1.0000, 1.00100, 0.99950, 1.00060)     # wick only ~33% of range
check("short wick -> no pinbar", len(sigs) == 0)

# ── TREND FILTER ──────────────────────────────────────────────────────
def uptrend_engine(**kw):
    eng, sigs, res, blocked = make_engine(**kw)
    p = 1.0000
    for i in range(55):  # steadily rising closes -> uptrend
        o = p; c = p + 0.0002
        feed_candle(eng, B + i * 60, o, c + 0.00005, o - 0.00005, c)
        p = c
    return eng, sigs, res, blocked, p

eng, sigs, res, blocked, p = uptrend_engine(strategies=("perfect",), trend_filter=True)
n0 = len(sigs)
feed_candle(eng, B + 55 * 60, p, p + 0.00005, p - 0.00105, p - 0.0010)  # perfect RED vs trend
check("trend filter blocks counter-trend PUT", len(sigs) == n0 and any(b[0] == "perfect" for b in blocked))

eng, sigs, res, blocked, p = uptrend_engine(strategies=("perfect",), trend_filter=False)
n0 = len(sigs)
feed_candle(eng, B + 55 * 60, p, p + 0.00005, p - 0.00105, p - 0.0010)
check("trend filter OFF -> counter-trend PUT issued", len(sigs) == n0 + 1 and sigs[-1].direction == "put")

# ── RSI FILTER ────────────────────────────────────────────────────────
def rising_engine(**kw):
    eng, sigs, res, blocked = make_engine(**kw)
    p = 1.0000
    for i in range(20):  # all gains -> RSI = 100
        o = p; c = p + 0.0002
        feed_candle(eng, B + i * 60, o, c + 0.00005, o - 0.00005, c)
        p = c
    return eng, sigs, res, blocked, p

eng, sigs, res, blocked, p = rising_engine(strategies=("perfect",), rsi_filter=True)
n0 = len(sigs)
feed_candle(eng, B + 20 * 60, p, p + 0.0010, p - 0.0001, p + 0.00095)  # perfect green, RSI 100
check("RSI filter blocks CALL at RSI 100", len(sigs) == n0 and len(blocked) >= 1)

eng, sigs, res, blocked, p = rising_engine(strategies=("perfect",), rsi_filter=False)
n0 = len(sigs)
feed_candle(eng, B + 20 * 60, p, p + 0.0010, p - 0.0001, p + 0.00095)
check("RSI filter OFF -> CALL issued", len(sigs) == n0 + 1)

# ── STRENGTH GRADE ────────────────────────────────────────────────────
eng, sigs, res, blocked, p = uptrend_engine(strategies=("perfect",), trend_filter=True)
feed_candle(eng, B + 55 * 60, p, p + 0.0010, p - 0.0001, p + 0.00095)  # perfect green WITH trend, RSI high side
s = sigs[-1]
check("with-trend perfect candle is STRONG", s.grade == "STRONG" and s.strength >= 3)

# confluence: perfect + engulfing on the same candle (raw feed = exact opens)
def feed_ohlc(eng, start, o, h, l, c):
    eng.feed("EURUSD_otc", {"timestamp": start, "open": o, "high": h, "low": l, "close": c})

eng, sigs, _, _ = make_engine(strategies=("perfect", "engulfing"))
feed_ohlc(eng, B, 1.0010, 1.00115, 1.00090, 1.00095)             # red candle
feed_ohlc(eng, B + 60, 1.00090, 1.00122, 1.00088, 1.00120)      # green: engulfs AND body 88%
eng.feed("EURUSD_otc", {"timestamp": B + 120, "price": 1.00120})  # close candle 2
kinds = sorted(s.kind for s in sigs)
check("confluence fires both strategies", kinds == ["engulfing", "perfect"])
check("confluence adds strength", sigs and all(s.strength >= 2 for s in sigs))

# ── PER-KIND RESULT STATS ─────────────────────────────────────────────
eng, sigs, res, _ = make_engine(strategies=("perfect", "engulfing"))
feed_ohlc(eng, B, 1.0010, 1.00115, 1.00090, 1.00095)             # red
feed_ohlc(eng, B + 60, 1.00090, 1.00122, 1.00088, 1.00120)      # perfect+engulfing CALL
feed_ohlc(eng, B + 120, 1.00120, 1.00125, 1.00090, 1.00100)     # target candle DOWN
eng.feed("EURUSD_otc", {"timestamp": B + 180, "price": 1.00100})  # close target -> both LOSS
check("both kinds recorded LOSS", eng.stats_by_kind["perfect"].losses == 1
      and eng.stats_by_kind["engulfing"].losses == 1 and eng.stats.losses == 2)

# ── PAYLOAD SHAPES ────────────────────────────────────────────────────
from signals_engine import extract_points
check("ms ts normalised", extract_points({"timestamp": 1700000000000, "price": 1.5}) == [(1700000000, 1.5, None)])
check("list payload", len(extract_points([{"time": 1700000000, "close": 2.5}, [1700000060, 2.6]])) == 2)
check("nested OHLC", extract_points({"data": {"timestamp": 1700000000, "open": 1, "high": 2, "low": 0.5, "close": 1.5}})[0][2]["high"] == 2)

# ── v1.1 REAL MARKET FEED ─────────────────────────────────────────────
from realmarket_feed import RealMarketFeed, yahoo_symbol, parse_chart_payload

check("yahoo symbol mapping (all 6 pairs)",
      [yahoo_symbol(c) for c in ("EURUSD", "GBPUSD", "USDJPY", "EURGBP", "AUDUSD", "EURJPY")]
      == ["EURUSD=X", "GBPUSD=X", "USDJPY=X", "EURGBP=X", "AUDUSD=X", "EURJPY=X"])
check("chart payload parsed",
      parse_chart_payload({"chart": {"result": [{"meta": {"regularMarketPrice": 1.1193,
      "regularMarketTime": 1793334045}}]}}) == (1.1193, 1793334045))
check("chart error payload -> None",
      parse_chart_payload({"finance": {"result": None, "error": {"code": "Unauthorized"}}}) is None
      and parse_chart_payload({"chart": {"result": None}}) is None
      and parse_chart_payload({"chart": {"result": [{"meta": {"regularMarketPrice": 0,
      "regularMarketTime": 1793334045}}]}}) is None)

# staleness flag logic (no network: quotes injected via handle_quote)
_feed = RealMarketFeed(["EURUSD"], on_point=lambda *a: None)
check("feed stale before any poll", _feed.is_stale(10) is True)
_now = 1793334000.0
_feed.handle_quote("EURUSD", 1.1193, 1793333995, now=_now)
check("feed fresh within 10s of a poll", _feed.is_stale(10, now=_now + 5) is False)
check("feed stale 11s after last poll", _feed.is_stale(10, now=_now + 11) is True)
check("data lag uses market timestamp", _feed.data_lag(now=_now) == 5.0)

# duplicate quotes are not re-emitted (polling the same snapshot twice)
_pts = []
_feed2 = RealMarketFeed(["EURUSD"], on_point=lambda c, t, p: _pts.append((c, t, p)))
_feed2.handle_quote("EURUSD", 1.1000, 1793334000, now=1793334001.0)
_feed2.handle_quote("EURUSD", 1.1000, 1793334000, now=1793334002.0)
_feed2.handle_quote("EURUSD", 1.1001, 1793334003, now=1793334004.0)
check("unchanged quote de-duplicated", len(_pts) == 2 and _pts[1][2] == 1.1001)

# points -> engine candles: same aggregation path as the Quotex feed,
# driven through RealMarketFeed.handle_quote exactly like the GUI does
_rm_sigs, _rm_res, _rm_candles = [], [], []
_rm_eng = SignalEngine({"USDJPY": "USD/JPY"}, [30], threshold_pct=85.0,
                       strategies=("perfect",), trend_filter=False, rsi_filter=False,
                       source="realmarket",
                       on_signal=_rm_sigs.append, on_result=_rm_res.append,
                       on_candle=lambda k, t, c: _rm_candles.append(c))
_rm_feed = RealMarketFeed(["USDJPY"],
                          on_point=lambda code, ts, p: _rm_eng.feed(
                              code, {"timestamp": ts, "price": p}))
for _ts, _p in [(B, 100.00), (B + 5, 100.10), (B + 10, 100.20), (B + 20, 100.28),
                (B + 29, 100.30), (B + 30, 100.30), (B + 35, 100.40),
                (B + 59, 100.45), (B + 60, 100.45)]:
    _rm_feed.handle_quote("USDJPY", _p, _ts, now=float(_ts))
_c0 = _rm_candles[0]
check("RM points aggregate into 30s candle",
      (_c0.open, _c0.high, _c0.low, _c0.close) == (100.00, 100.30, 100.00, 100.30))
check("RM perfect candle -> CALL signal", len(_rm_sigs) == 2
      and all(s.direction == "call" for s in _rm_sigs))  # target candle is itself 100%-body
check("signal stamped with its source",
      _rm_sigs and _rm_sigs[0].source == "realmarket" and _rm_sigs[0].source_label == "Real Market")
check("RM target up -> WIN", len(_rm_res) == 1 and _rm_res[0].result == "WIN")
check("per-(source, kind) stats recorded",
      _rm_eng.stats_by_source_kind[("realmarket", "perfect")].wins == 1
      and _rm_eng.stats_by_source_kind[("realmarket", "perfect")].total == 1)
check("other source untouched in same engine",
      ("quotex", "perfect") not in _rm_eng.stats_by_source_kind
      or _rm_eng.stats_by_source_kind[("quotex", "perfect")].total == 0)

# default source stays "quotex" (v1.0 behaviour unchanged)
eng, sigs, res, _ = make_engine()
feed_candle(eng, B, 1.0000, 1.00100, 0.99990, 1.00095)
feed_candle(eng, B + 60, 1.00095, 1.00150, 1.00090, 1.00120)
check("default engine stamps quotex source",
      sigs and sigs[0].source == "quotex"
      and eng.stats_by_source_kind[("quotex", "perfect")].wins == 1)

# default source stays "quotex" (v1.0 behaviour unchanged)
eng, sigs, res, _ = make_engine()
feed_candle(eng, B, 1.0000, 1.00100, 0.99990, 1.00095)
feed_candle(eng, B + 60, 1.00095, 1.00150, 1.00090, 1.00120)
check("default engine stamps quotex source",
      sigs and sigs[0].source == "quotex"
      and eng.stats_by_source_kind[("quotex", "perfect")].wins == 1)

# ── v1.2 IQ OPTION FEED ───────────────────────────────────────────────
from datetime import datetime, timezone
import iqoption_feed as iqf

check("iq actives: all 6 pairs, library ids",
      iqf.ACTIVES == {"EURUSD": (1, 76), "GBPUSD": (5, 81), "USDJPY": (6, 85),
                      "EURGBP": (2, 77), "AUDUSD": (99, None), "EURJPY": (4, 79)})
_wed = datetime(2026, 10, 28, 12, 0, tzinfo=timezone.utc)   # Wednesday noon
_sat = datetime(2026, 10, 31, 12, 0, tzinfo=timezone.utc)   # Saturday noon
_sun_early = datetime(2026, 11, 1, 21, 0, tzinfo=timezone.utc)
_sun_late = datetime(2026, 11, 1, 23, 0, tzinfo=timezone.utc)
_fri_late = datetime(2026, 10, 30, 23, 0, tzinfo=timezone.utc)
check("market open Wed noon, closed Sat/Sun-early/Fri-late",
      iqf.market_open(_wed) is True and iqf.market_open(_sat) is False
      and iqf.market_open(_sun_early) is False and iqf.market_open(_sun_late) is True
      and iqf.market_open(_fri_late) is False)
check("active_for: weekday -> regular, weekend -> OTC",
      iqf.active_for("EURUSD", _wed) == (1, False)
      and iqf.active_for("EURUSD", _sat) == (76, True)
      and iqf.active_for("USDJPY", _sat) == (85, True))
check("active_for: AUDUSD has no OTC -> regular even on weekend",
      iqf.active_for("AUDUSD", _sat) == (99, False))

_sub = iqf.build_subscribe(76)
check("subscribe envelope (candle-generated, str active_id, size 15)",
      _sub["name"] == "subscribeMessage"
      and _sub["msg"]["name"] == "candle-generated"
      and _sub["msg"]["params"]["routingFilters"] == {"active_id": "76", "size": 15})
_gc = iqf.build_get_candles(1, 1793334000, count=130)
check("get-candles envelope (sendMessage, v2.0 body)",
      _gc["name"] == "sendMessage" and _gc["msg"]["name"] == "get-candles"
      and _gc["msg"]["version"] == "2.0"
      and _gc["msg"]["body"] == {"active_id": 1, "size": 60, "to": 1793334000, "count": 130})
check("authenticate envelope (ssid + protocol 3)",
      iqf.build_authenticate("SSID123") == {"name": "authenticate",
      "msg": {"ssid": "SSID123", "protocol": 3}, "request_id": ""})

_pt = iqf.parse_candle_msg({"from": 1793334000, "to": 1793334015, "open": 1.1000,
                            "close": 1.1010, "min": 0.9990, "max": 1.1015,
                            "active_id": 1, "size": 15})
check("iq candle normalised (min->low, max->high)",
      _pt == {"timestamp": 1793334000, "open": 1.1000, "high": 1.1015,
              "low": 0.9990, "close": 1.1010})
check("iq candle garbage -> None",
      iqf.parse_candle_msg({"from": "x"}) is None
      and iqf.parse_candle_msg(None) is None
      and iqf.parse_candle_msg({"from": 1793334000, "open": -1, "close": 1,
                                "min": 1, "max": 1}) is None)

_bal = iqf.parse_balances([{"id": 111, "type": 1, "amount": 250.5},
                           {"id": 222, "type": 4, "amount": 10000},
                           {"id": 333, "type": 2, "amount": 50}])
check("balances parsed (type1=real, type4=practice)",
      _bal == {"real": 250.5, "practice": 10000.0, "real_id": 111, "practice_id": 222})
check("ssid from cookie header",
      iqf.ssid_from_login(200, ["ssid=ABC123xyz; Path=/; HttpOnly"], b"") == "ABC123xyz")
check("ssid from JSON body fallback",
      iqf.ssid_from_login(200, [], b'{"data": {"ssid": "JSONSSID"}}') == "JSONSSID")
try:
    iqf.ssid_from_login(401, [], b'{"message":"bad credentials"}')
    _ssid_err = False
except iqf.IqError:
    _ssid_err = True
check("login 401 without ssid -> IqError", _ssid_err)

# websocket framing (fake socket, no network): handshake bytes are fed
# by hand; we test frame build/parse + ping->pong at the frame level.
class _FakeSock:
    def __init__(self, incoming=b""):
        self.incoming = bytearray(incoming)
        self.sent = bytearray()
    def recv(self, n):
        out = bytes(self.incoming[:n])
        del self.incoming[:n]
        return out
    def sendall(self, data):
        self.sent += data
    def settimeout(self, t): pass
    def gettimeout(self): return None
    def close(self): pass

_ws = iqf._WsClient("iqoption.com", "/echo/websocket")
# server text frame "hello" (unmasked), then a ping "hi", then text "bye"
_ws._buf = b"\x81\x05hello" + b"\x89\x02hi" + b"\x81\x03bye"
_ws.sock = _FakeSock()
check("ws recv parses text frame", _ws.recv_text() == "hello")
check("ws recv answers ping, then parses next frame", _ws.recv_text() == "bye")
_pong = bytes(_ws.sock.sent)
check("ws ping answered with masked pong (opcode 0xA)",
      len(_pong) == 8 and (_pong[0] & 0x0F) == 0xA and (_pong[1] & 0x80) != 0)
_ws.sock.sent = bytearray()
_ws.send_text("abc")
_f = bytes(_ws.sock.sent)
_mask = _f[2:6]
_unmasked = bytes(b ^ _mask[i % 4] for i, b in enumerate(_f[6:9]))
check("ws send_text builds masked text frame",
      (_f[0] & 0x0F) == 0x1 and (_f[1] & 0x80) != 0 and (_f[1] & 0x7F) == 3
      and _unmasked == b"abc")

# full session over a fake websocket (scripted server messages)
class _FakeWs:
    def __init__(self, incoming):
        self.incoming = list(incoming)
        self.sent = []
        self.sock = None
        self.feed = None
    def connect(self): pass
    def send_text(self, text):
        import json as _json
        self.sent.append(_json.loads(text))
    def recv_text(self):
        if self.incoming:
            import json as _json
            return _json.dumps(self.incoming.pop(0))
        if self.feed is not None:
            self.feed.stop()
        return None
    def close(self): pass

_hist = [{"from": B + i * 60, "to": B + (i + 1) * 60, "open": 1.0, "close": 1.0 + i * 0.001,
          "min": 0.999, "max": 1.002} for i in range(5)]
_incoming = [
    {"name": "timeSync", "msg": (B + 3600) * 1000},
    {"name": "authenticated", "msg": True},
    {"name": "balances", "msg": [{"id": 111, "type": 1, "amount": 250.5},
                                 {"id": 222, "type": 4, "amount": 10000}]},
    {"name": "candles", "msg": {"candles": _hist}},                    # EURUSD warmup
    {"name": "candles", "msg": {"candles": _hist}},                    # GBPUSD warmup
    {"name": "candle-generated", "msg": {"active_id": iqf.active_for("EURUSD")[0],
        "size": 15, "from": B + 7200, "to": B + 7215, "open": 1.1000,
        "close": 1.1010, "min": 0.9990, "max": 1.1015}},
]
_fake = _FakeWs(_incoming)
_got, _bals = [], []
_feed3 = iqf.IqOptionFeed("a@b.c", "pw", ["EURUSD", "GBPUSD"], practice=True,
                          on_candle=lambda c, p: _got.append((c, p)),
                          on_balances=lambda p, r: _bals.append((p, r)),
                          login_fn=lambda e, p: "TESTSSID",
                          ws_factory=lambda: _fake)
_fake.feed = _feed3
_feed3.run()
_sent_names = [m["name"] for m in _fake.sent]
check("iq session: authenticate sent with ssid",
      _fake.sent[0] == {"name": "authenticate",
                        "msg": {"ssid": "TESTSSID", "protocol": 3}, "request_id": ""})
check("iq session: subscribes both assets",
      _sent_names.count("subscribeMessage") == 2)
check("iq session: PRACTICE balance selected (change-balance id 222)",
      any(m["name"] == "api_profile_changebalance" and m["msg"]["balance_id"] == 222
          for m in _fake.sent))
check("iq session: balances callback (practice, real)",
      _bals == [(10000.0, 250.5)])
check("iq session: warmup closes fed for both assets",
      sum(1 for c, p in _got if "price" in p) == 10)
_live = [p for c, p in _got if "open" in p]
check("iq session: live candle normalised + routed to EURUSD",
      len(_live) == 1 and _live[0]["high"] == 1.1015 and _live[0]["low"] == 0.9990
      and [c for c, p in _got if "open" in p] == ["EURUSD"])

# engine integration: iqoption-stamped signals + per-source stats
_iq_sigs, _iq_res = [], []
_iq_eng = SignalEngine({"EURUSD": "EUR/USD"}, [60], threshold_pct=85.0,
                       strategies=("perfect",), trend_filter=False, rsi_filter=False,
                       source="iqoption",
                       on_signal=_iq_sigs.append, on_result=_iq_res.append)
_iq_eng.feed("EURUSD", iqf.parse_candle_msg(
    {"from": B, "to": B + 60, "open": 1.0000, "close": 1.00095,
     "min": 0.99990, "max": 1.00100}))
_iq_eng.feed("EURUSD", iqf.parse_candle_msg(
    {"from": B + 60, "to": B + 120, "open": 1.00095, "close": 1.00120,
     "min": 1.00090, "max": 1.00150}))
_iq_eng.feed("EURUSD", {"timestamp": B + 120, "price": 1.00120})
check("iqoption engine: perfect candle -> CALL, source label IQ Option",
      len(_iq_sigs) >= 1 and _iq_sigs[0].source == "iqoption"
      and _iq_sigs[0].source_label == "IQ Option")
check("iqoption per-(source, kind) stats recorded",
      _iq_eng.stats_by_source_kind[("iqoption", "perfect")].total >= 1)

print()
if FAILED:
    print("FAILED:", FAILED); sys.exit(1)
print("ALL TESTS PASSED")
