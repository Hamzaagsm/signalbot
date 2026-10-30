"""Real Market feed for Hamza Signal Bot v1.1 (HTTPS polling, no login).

Why this module exists: Hamza's rule — signals should come from where
the market moves FIRST. Binary/OTC prices (Quotex) follow the real
market, so v1.1 can also watch the real forex market directly.

Source: Yahoo Finance chart API (no account, no API key):
    GET https://query1.finance.yahoo.com/v8/finance/chart/<SYM>=X
        ?interval=1m&range=1d
    -> chart.result[0].meta.regularMarketPrice / regularMarketTime

Freshness was PROVEN live from this network before building (Fri
2026-10-30 ~04:00-04:25 UTC, forex market open):
  * USDJPY=X: tick-fresh — 11 price changes in 12 polls (3s cadence),
    market timestamps within ~0-2s of wall clock.
  * EURJPY=X: prices moved between polls, timestamps current.
  * EURUSD=X / GBPUSD=X / AUDUSD=X: real prices, but Yahoo publishes
    them in ~1-minute snapshots (timestamps quantised to :45), so
    candles on those pairs form more slowly — the engine simply gets
    fewer points; it never invents data.
  * TradingView's scanner endpoint was also tested and did not answer
    at all from this network (read timeouts), so Yahoo is the source.

The feed polls each selected asset every ~2 seconds in its own thread,
de-duplicates unchanged quotes, and hands (ts, price) points to the
caller's callback — the GUI feeds them into the SAME SignalEngine the
Quotex stream uses, so strategies/filters/stats behave identically.
The only difference is the source stamp on the signals.

HTTP is done with the Python standard library only (urllib), so the
frozen EXE needs no extra dependency for this feed.

SIGNAL ONLY: nothing here (or anywhere in this bot) places orders.
"""

import json
import threading
import time
import urllib.parse
import urllib.request
from typing import Callable, Dict, List, Optional, Tuple

# Asset code (as used by the GUI) -> Yahoo Finance symbol.
YAHOO_SYMBOLS: Dict[str, str] = {
    "EURUSD": "EURUSD=X",
    "GBPUSD": "GBPUSD=X",
    "USDJPY": "USDJPY=X",
    "EURGBP": "EURGBP=X",
    "AUDUSD": "AUDUSD=X",
    "EURJPY": "EURJPY=X",
}

CHART_URL = "https://query1.finance.yahoo.com/v8/finance/chart/{symbol}"
HEADERS = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                         "AppleWebKit/537.36 (KHTML, like Gecko)"}

SOURCE_ID = "realmarket"
SOURCE_LABEL = "Real Market"


def _fetch_json(url: str, timeout: float):
    """Default HTTP GET -> parsed JSON (stdlib only)."""
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def yahoo_symbol(code: str) -> str:
    """Map a GUI asset code ('EURUSD') to its Yahoo symbol ('EURUSD=X')."""
    return YAHOO_SYMBOLS[code]


def parse_chart_payload(payload) -> Optional[Tuple[float, int]]:
    """Extract (price, market_ts_seconds) from a Yahoo chart response.

    Returns None for any malformed/error payload (never raises).
    """
    try:
        result = payload["chart"]["result"]
        if not result:
            return None
        meta = result[0]["meta"]
        price = float(meta["regularMarketPrice"])
        mtime = int(meta["regularMarketTime"])
        if price <= 0 or mtime < 1_000_000_000:
            return None
        return price, mtime
    except (KeyError, TypeError, ValueError, IndexError):
        return None


class RealMarketFeed:
    """Polling quote feed. One thread per asset; exponential backoff.

    Callbacks:
      on_point(asset_code, ts_seconds, price) - a NEW quote arrived
        (unchanged quotes are de-duplicated and not re-emitted).
      on_status(text) - human-readable status changes (optional).

    Bookkeeping for the GUI's staleness indicator:
      last_update  - wall time of the last NEW quote from any asset
      last_poll    - wall time of the last successful poll (any asset)
      asset_state  - per asset: {price, market_ts, lag, last_poll,
                     last_point, errors}
    """

    def __init__(self,
                 assets: List[str],
                 on_point: Callable[[str, int, float], None],
                 on_status: Optional[Callable[[str], None]] = None,
                 poll_interval: float = 2.0,
                 timeout: float = 6.0,
                 fetcher: Optional[Callable[[str, float], dict]] = None):
        self.assets = list(assets)
        self.on_point = on_point
        self.on_status = on_status
        self.poll_interval = float(poll_interval)
        self.timeout = float(timeout)
        self._fetcher = fetcher or _fetch_json
        self._stop = threading.Event()
        self._threads: List[threading.Thread] = []
        self._lock = threading.Lock()
        self.last_update: Optional[float] = None
        self.last_poll: Optional[float] = None
        self.asset_state: Dict[str, dict] = {
            code: {"price": None, "market_ts": None, "lag": None,
                   "last_poll": None, "last_point": None, "errors": 0}
            for code in self.assets
        }
        self._last_emitted: Dict[str, Tuple[float, int]] = {}

    # -- lifecycle -----------------------------------------------------

    def start(self) -> None:
        for code in self.assets:
            t = threading.Thread(target=self._run_asset, args=(code,),
                                 daemon=True, name=f"rm-feed-{code}")
            t.start()
            self._threads.append(t)
        self._emit_status(f"Real Market feed shuru: {', '.join(self.assets)}")

    def stop(self) -> None:
        self._stop.set()

    # -- staleness -----------------------------------------------------

    def is_stale(self, threshold: float = 10.0, now: Optional[float] = None) -> bool:
        """True when no successful poll happened within `threshold`s."""
        if self.last_poll is None:
            return True
        now = time.time() if now is None else now
        return (now - self.last_poll) > threshold

    def data_lag(self, now: Optional[float] = None) -> Optional[float]:
        """Worst market-timestamp lag (seconds) across assets, or None."""
        now = time.time() if now is None else now
        lags = []
        with self._lock:
            for st in self.asset_state.values():
                if st["market_ts"] is not None:
                    lags.append(now - st["market_ts"])
        return max(lags) if lags else None

    def snapshot(self) -> dict:
        with self._lock:
            return {
                "last_update": self.last_update,
                "last_poll": self.last_poll,
                "stale": self.is_stale(),
                "assets": {k: dict(v) for k, v in self.asset_state.items()},
            }

    # -- internals -----------------------------------------------------

    def _emit_status(self, text: str) -> None:
        if self.on_status:
            try:
                self.on_status(text)
            except Exception:
                pass

    def fetch_quote(self, code: str) -> Optional[Tuple[float, int]]:
        """One HTTP poll for one asset; (price, market_ts) or None."""
        url = CHART_URL.format(symbol=yahoo_symbol(code))
        url += "?" + urllib.parse.urlencode({"interval": "1m", "range": "1d"})
        return parse_chart_payload(self._fetcher(url, self.timeout))

    def handle_quote(self, code: str, price: float, market_ts: int,
                     now: Optional[float] = None) -> bool:
        """Record a fetched quote; emit on_point only when it is new.

        Returns True when a point was emitted. Split out from the
        network loop so the aggregation rules are unit-testable.
        """
        now = time.time() if now is None else now
        with self._lock:
            st = self.asset_state[code]
            st["price"] = price
            st["market_ts"] = market_ts
            st["lag"] = round(now - market_ts, 1)
            st["last_poll"] = now
            st["errors"] = 0
            self.last_poll = now
            key = (price, market_ts)
            if self._last_emitted.get(code) == key:
                return False
            self._last_emitted[code] = key
            st["last_point"] = now
            self.last_update = now
        self.on_point(code, market_ts, price)
        return True

    def _run_asset(self, code: str) -> None:
        backoff = self.poll_interval
        while not self._stop.is_set():
            t0 = time.time()
            try:
                got = self.fetch_quote(code)
                if got is None:
                    raise ValueError("empty/bad payload")
                price, mtime = got
                self.handle_quote(code, price, mtime, now=t0)
                backoff = self.poll_interval
            except Exception as e:
                with self._lock:
                    self.asset_state[code]["errors"] += 1
                    errs = self.asset_state[code]["errors"]
                if errs == 1 or errs % 10 == 0:
                    self._emit_status(f"⚠️ Real Market {code}: {type(e).__name__} "
                                      f"— dobara koshish jaari hai…")
                self._stop.wait(backoff)
                backoff = min(backoff * 2, 30.0)
                continue
            self._stop.wait(max(0.0, self.poll_interval - (time.time() - t0)))
