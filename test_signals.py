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

print()
if FAILED:
    print("FAILED:", FAILED); sys.exit(1)
print("ALL TESTS PASSED")
