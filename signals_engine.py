"""Signal engine for Hamza Signal Bot (pure logic, no network).

Strategies (each toggleable; PERFECT CANDLE is the core "Chinese method"):
  * perfect  - candle body >= threshold % of its high-low range (default
               85%) -> signal the NEXT candle in the same direction.
  * engulfing- bullish/bearish engulfing pair at candle close -> signal
               the next candle in the engulfing candle's direction.
  * pinbar   - pin bar / hammer / shooting star: one long wick (>= 60% of
               range and >= 2x body) rejecting prices, tiny opposite wick
               -> signal the next candle AWAY from the rejection
               (hammer -> CALL, shooting star -> PUT).

Filters (toggleable):
  * trend    - EMA(20/50): only allow signals in the trend direction.
               (With 20-49 closes, close-vs-EMA20 is used; below that the
               trend is "unknown" and nothing is blocked.)
  * rsi      - RSI(14): block CALL when RSI >= 70 (overbought), block PUT
               when RSI <= 30 (oversold).

Strength grade: 1 point for the pattern itself, +1 if the EMA trend
agrees, +1 if RSI sits on the signal's side of 50, +1 if 2+ strategies
fire on the same candle (confluence). >= 3 points = "STRONG".

Results: after the signalled next candle closes, its direction is
compared with the signal -> WIN / LOSS / DRAW. Stats are kept overall,
per strategy kind, AND per (source, kind) — v1.1 adds a feed "source"
stamp ("quotex" vs "realmarket") so the demo record can show which
feed actually performs better.

Everything here is pure Python (offline-testable, see test_signals.py).
NO orders are placed anywhere - v1 is a signal-only bot by design.

Feed format: ``SignalEngine.feed(asset_key, data)`` normalises raw
QuotexAPI depth-stream payloads into price points (ts_seconds, price):
dicts with OHLC, dicts with a tick price, nested dicts, and lists
(including [timestamp, price] pairs) are all tolerated. Candles are
built by wall-clock bucket (ts - ts % timeframe), like a chart.
"""

from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Tuple

KIND_LABELS = {"perfect": "Perfect Candle", "engulfing": "Engulfing", "pinbar": "Pin Bar"}
SOURCE_LABELS = {"quotex": "Quotex", "realmarket": "Real Market"}


# ── data classes ──────────────────────────────────────────────────────

@dataclass
class Candle:
    start: int          # bucket start, unix seconds
    open: float
    high: float
    low: float
    close: float

    @property
    def range(self) -> float:
        return self.high - self.low

    @property
    def body(self) -> float:
        return abs(self.close - self.open)

    @property
    def body_pct(self) -> float:
        if self.range <= 0:
            return 0.0
        return 100.0 * self.body / self.range

    @property
    def upper_wick(self) -> float:
        return self.high - max(self.open, self.close)

    @property
    def lower_wick(self) -> float:
        return min(self.open, self.close) - self.low

    @property
    def direction(self) -> Optional[str]:
        if self.close > self.open:
            return "call"
        if self.close < self.open:
            return "put"
        return None  # doji


@dataclass
class Signal:
    asset: str          # display name, e.g. "EUR/USD OTC"
    asset_key: str      # stream key, e.g. "EURUSD_otc"
    timeframe: int      # seconds: 30 or 60
    direction: str      # "call" or "put"
    kind: str           # "perfect" / "engulfing" / "pinbar"
    candle_start: int   # start of the candle that triggered it
    issued_at: int      # start of the target (next) candle == candle close
    strength: int = 1   # agreement score (see module docstring)
    grade: str = "LIGHT"          # "STRONG" / "NORMAL" / "LIGHT"
    detail: str = ""              # e.g. "body 91%" / "wick 74%"
    source: str = "quotex"        # feed that produced it: "quotex" / "realmarket"
    result: Optional[str] = None  # "WIN" / "LOSS" / "DRAW"
    result_close: Optional[float] = None

    @property
    def target_start(self) -> int:
        return self.candle_start + self.timeframe

    @property
    def kind_label(self) -> str:
        return KIND_LABELS.get(self.kind, self.kind)

    @property
    def source_label(self) -> str:
        return SOURCE_LABELS.get(self.source, self.source)


@dataclass
class Stats:
    total: int = 0
    wins: int = 0
    losses: int = 0
    draws: int = 0

    @property
    def decided(self) -> int:
        return self.wins + self.losses

    @property
    def win_pct(self) -> Optional[float]:
        if self.decided == 0:
            return None
        return 100.0 * self.wins / self.decided

    def record(self, result: str) -> None:
        self.total += 1
        if result == "WIN":
            self.wins += 1
        elif result == "LOSS":
            self.losses += 1
        else:
            self.draws += 1


# ── indicators (pure) ─────────────────────────────────────────────────

def ema_last(values: List[float], period: int) -> Optional[float]:
    """Last EMA value, or None when there is not enough data."""
    if len(values) < period:
        return None
    k = 2.0 / (period + 1)
    avg = sum(values[:period]) / period
    for v in values[period:]:
        avg = v * k + avg * (1 - k)
    return avg


def rsi_last(closes: List[float], period: int = 14) -> Optional[float]:
    """Last RSI (Wilder), or None when there is not enough data."""
    if len(closes) < period + 1:
        return None
    gains, losses = [], []
    for i in range(1, period + 1):
        d = closes[i] - closes[i - 1]
        gains.append(max(d, 0.0))
        losses.append(max(-d, 0.0))
    avg_gain = sum(gains) / period
    avg_loss = sum(losses) / period
    for i in range(period + 1, len(closes)):
        d = closes[i] - closes[i - 1]
        avg_gain = (avg_gain * (period - 1) + max(d, 0.0)) / period
        avg_loss = (avg_loss * (period - 1) + max(-d, 0.0)) / period
    if avg_loss == 0:
        return 100.0
    rs = avg_gain / avg_loss
    return 100.0 - (100.0 / (1.0 + rs))


def trend_direction(closes: List[float]) -> Optional[str]:
    """'call' (uptrend) / 'put' (downtrend) / None (unknown).

    Full mode (>= 50 closes): EMA20 vs EMA50. Early mode (20-49 closes):
    close vs EMA20. Below 20 closes the trend is unknown.
    """
    if len(closes) >= 50:
        ef = ema_last(closes, 20)
        es = ema_last(closes, 50)
        if ef is None or es is None or ef == es:
            return None
        return "call" if ef > es else "put"
    if len(closes) >= 20:
        e = ema_last(closes, 20)
        if e is None or closes[-1] == e:
            return None
        return "call" if closes[-1] > e else "put"
    return None


# ── pattern detectors (pure) ──────────────────────────────────────────

def engulfing_direction(prev: Optional[Candle], cur: Candle) -> Optional[str]:
    """Bullish/bearish engulfing on bodies; returns direction or None."""
    if prev is None:
        return None
    if prev.direction is None or cur.direction is None:
        return None
    if prev.direction == "put" and cur.direction == "call":
        if cur.open <= prev.close and cur.close >= prev.open:
            return "call"
    if prev.direction == "call" and cur.direction == "put":
        if cur.open >= prev.close and cur.close <= prev.open:
            return "put"
    return None


def pinbar_direction(c: Candle) -> Optional[str]:
    """Hammer -> 'call', shooting star -> 'put', else None.

    Long wick must be >= 60% of range and >= 2x the body; the opposite
    wick must be <= 15% of range; body must be non-zero.
    """
    if c.range <= 0 or c.body <= 0:
        return None
    lw, uw, b, r = c.lower_wick, c.upper_wick, c.body, c.range
    if lw >= 0.60 * r and lw >= 2 * b and uw <= 0.15 * r:
        return "call"   # hammer: lower prices rejected
    if uw >= 0.60 * r and uw >= 2 * b and lw <= 0.15 * r:
        return "put"    # shooting star: higher prices rejected
    return None


# ── payload normalisation ─────────────────────────────────────────────

def _norm_ts(ts) -> Optional[int]:
    try:
        ts = float(ts)
    except (TypeError, ValueError):
        return None
    if ts >= 1e14:
        ts /= 1e6
    elif ts >= 1e11:
        ts /= 1e3
    if ts < 1e8:
        return None
    return int(ts)


def extract_points(data) -> List[Tuple[int, float, Optional[dict]]]:
    """Extract (ts_seconds, price, ohlc_or_None) points from a payload."""
    points: List[Tuple[int, float, Optional[dict]]] = []

    def walk(node):
        if isinstance(node, dict):
            ts = _norm_ts(node.get("timestamp", node.get("time")))
            ohlc, price = None, None
            if all(k in node for k in ("open", "high", "low", "close")):
                try:
                    ohlc = {k: float(node[k]) for k in ("open", "high", "low", "close")}
                    price = ohlc["close"]
                except (TypeError, ValueError):
                    ohlc, price = None, None
            if price is None and "price" in node:
                try:
                    price = float(node["price"])
                except (TypeError, ValueError):
                    price = None
            if price is None and "close" in node:
                try:
                    price = float(node["close"])
                except (TypeError, ValueError):
                    price = None
            if ts is not None and price is not None and price > 0:
                points.append((ts, price, ohlc))
                return
            for key in ("data", "candle", "candles", "ticks", "depth"):
                if key in node:
                    walk(node[key])
        elif isinstance(node, (list, tuple)):
            if len(node) == 2 and all(isinstance(x, (int, float)) for x in node):
                ts = _norm_ts(node[0])
                if ts is not None and float(node[1]) > 0:
                    points.append((ts, float(node[1]), None))
                    return
            for item in node:
                walk(item)

    walk(data)
    return points


# ── candle builder ────────────────────────────────────────────────────

class CandleBuilder:
    """Aggregates price points into fixed-timeframe candles by wall clock."""

    def __init__(self, timeframe: int, on_close: Callable[[Candle], None]):
        self.tf = timeframe
        self.on_close = on_close
        self.current: Optional[Candle] = None

    def add(self, ts: int, price: float, ohlc: Optional[dict] = None) -> None:
        bucket = ts - (ts % self.tf)
        cur = self.current
        if cur is None:
            self.current = self._new(bucket, price, ohlc)
            return
        if bucket == cur.start:
            cur.high = max(cur.high, price)
            cur.low = min(cur.low, price)
            cur.close = price
            if ohlc:
                cur.high = max(cur.high, ohlc["high"])
                cur.low = min(cur.low, ohlc["low"])
                cur.close = ohlc["close"]
            return
        if bucket > cur.start:
            self.on_close(cur)
            self.current = self._new(bucket, price, ohlc)
            return
        # older point (late/out-of-order): ignore

    @staticmethod
    def _new(bucket: int, price: float, ohlc: Optional[dict]) -> Candle:
        if ohlc:
            return Candle(bucket, ohlc["open"], ohlc["high"], ohlc["low"], ohlc["close"])
        return Candle(bucket, price, price, price, price)


# ── signal engine ─────────────────────────────────────────────────────

class SignalEngine:
    """Strategies + filters + result tracking for many assets/timeframes.

    Callbacks:
      on_signal(signal)                  - a new signal was issued
      on_result(signal)                  - a pending signal was resolved
      on_blocked(kind, direction, why)   - a pattern fired but a filter
                                           blocked it (for the log)
      on_candle(asset_key, tf, candle)   - every closed candle (optional)
    """

    def __init__(self,
                 assets: Dict[str, str],          # asset_key -> display name
                 timeframes: List[int],
                 threshold_pct: float = 85.0,
                 strategies=("perfect", "engulfing", "pinbar"),
                 trend_filter: bool = True,
                 rsi_filter: bool = False,
                 rsi_overbought: float = 70.0,
                 rsi_oversold: float = 30.0,
                 max_signals: int = 0,            # 0 = unlimited (issued count)
                 source: str = "quotex",          # feed stamp for stats/history
                 on_signal: Optional[Callable[[Signal], None]] = None,
                 on_result: Optional[Callable[[Signal], None]] = None,
                 on_blocked: Optional[Callable[[str, str, str], None]] = None,
                 on_candle: Optional[Callable[[str, int, Candle], None]] = None):
        self.assets = dict(assets)
        self.timeframes = list(timeframes)
        self.threshold_pct = float(threshold_pct)
        self.strategies = tuple(strategies)
        self.trend_filter = bool(trend_filter)
        self.rsi_filter = bool(rsi_filter)
        self.rsi_ob = float(rsi_overbought)
        self.rsi_os = float(rsi_oversold)
        self.max_signals = int(max_signals)
        self.source = source
        self.on_signal = on_signal
        self.on_result = on_result
        self.on_blocked = on_blocked
        self.on_candle = on_candle

        self.builders: Dict[Tuple[str, int], CandleBuilder] = {}
        self.closes: Dict[Tuple[str, int], List[float]] = {}
        self.prev_candle: Dict[Tuple[str, int], Optional[Candle]] = {}
        for key in self.assets:
            for tf in self.timeframes:
                stream = (key, tf)
                self.builders[stream] = CandleBuilder(
                    tf, lambda c, k=key, t=tf: self._candle_closed(k, t, c))
                self.closes[stream] = []
                self.prev_candle[stream] = None
        self.pending: Dict[Tuple[str, int], List[Signal]] = {}
        self.history: List[Signal] = []
        self.stats = Stats()
        self.stats_by_kind: Dict[str, Stats] = {k: Stats() for k in KIND_LABELS}
        self.stats_by_source_kind: Dict[Tuple[str, str], Stats] = {
            (source, k): Stats() for k in KIND_LABELS}
        self.issued = 0

    # -- input ---------------------------------------------------------

    def feed(self, asset_key: str, data) -> None:
        if asset_key not in self.assets:
            return
        for ts, price, ohlc in extract_points(data):
            for tf in self.timeframes:
                self.builders[(asset_key, tf)].add(ts, price, ohlc)

    # -- internals -----------------------------------------------------

    def _candle_closed(self, asset_key: str, tf: int, candle: Candle) -> None:
        stream = (asset_key, tf)
        if self.on_candle:
            self.on_candle(asset_key, tf, candle)

        # 1) Resolve pending signals whose target candle just closed.
        for sig in self.pending.pop(stream, []):
            if candle.start < sig.target_start:
                self.pending.setdefault(stream, []).append(sig)
                continue
            actual = candle.direction
            if actual is None:
                sig.result = "DRAW"
            elif actual == sig.direction:
                sig.result = "WIN"
            else:
                sig.result = "LOSS"
            sig.result_close = candle.close
            self.stats.record(sig.result)
            self.stats_by_kind[sig.kind].record(sig.result)
            self.stats_by_source_kind.setdefault(
                (sig.source, sig.kind), Stats()).record(sig.result)
            if self.on_result:
                self.on_result(sig)

        # 2) Update indicator state with the just-closed candle.
        closes = self.closes[stream]
        closes.append(candle.close)
        if len(closes) > 300:
            del closes[:-300]
        trend = trend_direction(closes)
        rsi = rsi_last(closes, 14)
        prev = self.prev_candle[stream]
        self.prev_candle[stream] = candle

        # 3) Detect candidate signals from enabled strategies.
        candidates: List[Tuple[str, str, str]] = []  # (kind, direction, detail)
        if "perfect" in self.strategies:
            d = candle.direction
            if d is not None and candle.body_pct >= self.threshold_pct:
                candidates.append(("perfect", d, f"body {candle.body_pct:.0f}%"))
        if "engulfing" in self.strategies:
            d = engulfing_direction(prev, candle)
            if d is not None:
                candidates.append(("engulfing", d, "engulfing"))
        if "pinbar" in self.strategies:
            d = pinbar_direction(candle)
            if d is not None:
                wick = candle.lower_wick if d == "call" else candle.upper_wick
                pct = 100.0 * wick / candle.range if candle.range > 0 else 0.0
                candidates.append(("pinbar", d, f"wick {pct:.0f}%"))

        # 4) Filters.
        surviving: List[Tuple[str, str, str]] = []
        for kind, direction, detail in candidates:
            if self.trend_filter and trend is not None and trend != direction:
                if self.on_blocked:
                    self.on_blocked(kind, direction, "trend filter (EMA trend ulta hai)")
                continue
            if self.rsi_filter and rsi is not None:
                if direction == "call" and rsi >= self.rsi_ob:
                    if self.on_blocked:
                        self.on_blocked(kind, direction, f"RSI filter (RSI {rsi:.0f} overbought)")
                    continue
                if direction == "put" and rsi <= self.rsi_os:
                    if self.on_blocked:
                        self.on_blocked(kind, direction, f"RSI filter (RSI {rsi:.0f} oversold)")
                    continue
            surviving.append((kind, direction, detail))

        # 5) Strength + issue.
        confluence = 1 if len(surviving) >= 2 else 0
        for kind, direction, detail in surviving:
            if self.max_signals > 0 and self.issued >= self.max_signals:
                return
            strength = 1 + confluence
            if trend is not None and trend == direction:
                strength += 1
            if rsi is not None and ((direction == "call" and rsi > 50) or
                                    (direction == "put" and rsi < 50)):
                strength += 1
            grade = "STRONG" if strength >= 3 else ("NORMAL" if strength == 2 else "LIGHT")
            sig = Signal(
                asset=self.assets[asset_key],
                asset_key=asset_key,
                timeframe=tf,
                direction=direction,
                kind=kind,
                candle_start=candle.start,
                issued_at=candle.start + tf,
                strength=strength,
                grade=grade,
                detail=detail,
                source=self.source,
            )
            self.pending.setdefault(stream, []).append(sig)
            self.history.append(sig)
            self.issued += 1
            if self.on_signal:
                self.on_signal(sig)


# ── future auto-trade hook (v2) ───────────────────────────────────────
# v1 is SIGNAL-ONLY: nothing in this module (or the GUI) places orders.
# A future version can subscribe to SignalEngine.on_signal and call the
# broker API from there. The hook stays intentionally unimplemented so
# the signal logic remains broker-independent and testable.
def auto_trade_hook(signal: Signal) -> None:  # pragma: no cover - placeholder
    raise NotImplementedError("v1 is signal-only; auto-trading is not enabled")
