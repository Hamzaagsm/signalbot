"""MT5 (MetaTrader 5) feed for Hamza Signal Bot v1.3 — EXE ONLY.

Phase 2 (Hamza, 2026-10-30): MT5 joins the bot as a SEPARATE system
with its OWN login (account number + password + server), next to the
Quotex / IQ Option / Real Market sources. There is no official MT5
API for third-party Android apps, so this feed is Windows-EXE only.

How it works: the official `MetaTrader5` Python package talks over
local IPC to the MT5 terminal installed on the user's own PC. The
package itself is bundled into the EXE by PyInstaller (it is in
requirements.txt and CI builds on Windows); the TERMINAL must be
installed on the PC by the user — if it (or the package) is missing,
the feed reports a clear plain-language status and stops. It never
crashes the GUI.

Data: per asset, history rates (M1, ~130 bars) warm the engine up
(closes only, like the IQ warmup), then live ticks are polled with
copy_ticks_from (every tick since the cursor; symbol_info_tick is the
fallback) and handed to the caller as (ts, price) points — the SAME
shape RealMarketFeed uses, so the engine builds its 30s and 1m
candles identically. Source stamp: "mt5".

Symbol names: most brokers call the pairs exactly EURUSD etc.; some
add a suffix (EURUSD.m, EURUSD#). resolve_symbol() picks the exact
name first, else the shortest symbol starting with the pair code.

Account type: whatever the MT5 account itself is (the terminal
decides) — account_info().trade_mode 0=DEMO, 1=CONTEST, 2=REAL is
reported to the GUI; DEMO is recommended in the UI wording.

The MetaTrader5 import is guarded: on any machine without the
package (e.g. the Linux dev box) the module still imports cleanly
with mt5 = None, and the feed declines politely. Unit tests inject a
fake module — the real package cannot run off Windows.

SIGNAL ONLY (this phase): this module can initialise the terminal
connection, log in, read account info, select symbols, read rates
and ticks. There is deliberately NO order placement (no order_send,
no order_check, nothing) — a later phase may attach a separate
entry module to the engine's on_signal hook; the feed stays
read-only.

Structure note for that future phase: Mt5Feed exposes the connected
mt5 module handle as `feed.mt5_module` after connect() so a sibling
entry module can share the terminal session.
"""

import threading
import time
from typing import Callable, Dict, List, Optional, Tuple

try:  # Windows-only package; guarded so the module imports anywhere.
    import MetaTrader5 as mt5
except Exception:  # pragma: no cover - exercised on non-Windows dev
    mt5 = None

try:
    # MetaTrader5's native core builds NUMPY arrays for
    # copy_rates_from() / copy_ticks_from(). If numpy is not inside
    # the frozen EXE those calls fail on the user's PC, so it is
    # imported here — mt5_feed is bundled by PyInstaller, and its
    # imports get collected with it (verified in the v1.3 build:
    # without this line numpy was missing from the PYZ).
    import numpy  # noqa: F401
except Exception:  # pragma: no cover
    numpy = None

SOURCE_ID = "mt5"
SOURCE_LABEL = "MT5"

_UNSET = object()

MISSING_PACKAGE_MSG = (
    "❌ MT5 package (MetaTrader5) is build me nahi mila — MT5 feed chal "
    "nahi sakta. (Developer: requirements.txt me MetaTrader5 lazmi ho.)")
MISSING_TERMINAL_MSG = (
    "❌ MT5 terminal se connection nahi bana — pehle apne PC par "
    "MetaTrader 5 terminal INSTALL karo, use kholo, aur apne MT5 "
    "account se wahan login karo; phir yahan wahi login/server likh "
    "kar dobara START dabao.")


def mt5_available() -> bool:
    """True when the MetaTrader5 package is importable in this build."""
    return mt5 is not None


# ── pure helpers (unit-tested) ────────────────────────────────────────

def resolve_symbol(code: str, available_names) -> Optional[str]:
    """Broker symbol for a GUI pair code, or None.

    Exact name wins; otherwise the shortest available symbol that
    starts with the code (EURUSD.m / EURUSD# / EURUSD.pro style
    suffixes). Case-insensitive prefix match.
    """
    names = [str(n) for n in (available_names or [])]
    if code in names:
        return code
    upper = code.upper()
    cands = [n for n in names if n.upper().startswith(upper)]
    if not cands:
        return None
    return min(cands, key=len)


def _field(row, name, default=None):
    """Read a field from an MT5 row: dict, numpy void, or namedtuple."""
    if row is None:
        return default
    if isinstance(row, dict):
        return row.get(name, default)
    try:
        return row[name]
    except Exception:
        pass
    return getattr(row, name, default)


def tick_price(row) -> Optional[float]:
    """Chart price from a tick row: bid, else last, else ask (>0)."""
    for name in ("bid", "last", "ask"):
        try:
            v = float(_field(row, name, 0) or 0)
        except (TypeError, ValueError):
            v = 0.0
        if v > 0:
            return v
    return None


def tick_msc(row) -> int:
    """Tick time in milliseconds (time_msc, falling back to time*1000)."""
    msc = _field(row, "time_msc")
    if msc:
        try:
            return int(msc)
        except (TypeError, ValueError):
            pass
    try:
        return int(float(_field(row, "time", 0) or 0) * 1000)
    except (TypeError, ValueError):
        return 0


def account_mode_label(trade_mode) -> str:
    """account_info.trade_mode -> display label (0 DEMO, 1 CONTEST, 2 REAL)."""
    return {0: "DEMO", 1: "CONTEST", 2: "REAL"}.get(trade_mode, "UNKNOWN")


# ── the feed ──────────────────────────────────────────────────────────

class Mt5Feed:
    """MT5 terminal feed. Mirrors RealMarketFeed's callback shape.

    Callbacks:
      on_point(asset_code, ts_seconds, price) - a NEW tick arrived.
      on_status(text)                         - status lines.
      on_account(info_dict)                   - once after login:
        {login, server, balance, currency, mode, mode_label}.

    Lifecycle: run() blocks (call in a thread) until stop(). The
    terminal connection is opened once; a dropped feed does not try
    to re-login in a loop (the terminal owns the session) — it
    reports and ends, the user presses START again.

    mt5_module: injectable MetaTrader5-like module for tests;
    _UNSET = use the real (guarded) import, None = force "missing".
    """

    def __init__(self,
                 login: int = 0,
                 password: str = "",
                 server: str = "",
                 assets: Optional[List[str]] = None,
                 on_point: Optional[Callable[[str, int, float], None]] = None,
                 on_status: Optional[Callable[[str], None]] = None,
                 on_account: Optional[Callable[[dict], None]] = None,
                 poll_interval: float = 0.5,
                 mt5_module=_UNSET):
        self.login = int(login or 0)
        self.password = password or ""
        self.server = server or ""
        self.assets = list(assets or [])
        self.on_point = on_point
        self.on_status = on_status
        self.on_account = on_account
        self.poll_interval = float(poll_interval)
        self.mt5_module = mt5 if mt5_module is _UNSET else mt5_module
        self._stop = threading.Event()
        self.symbols: Dict[str, str] = {}        # code -> broker symbol
        self._cursor_msc: Dict[str, int] = {}    # code -> last tick msc
        self.account: Optional[dict] = None

    # -- lifecycle ------------------------------------------------------

    def stop(self) -> None:
        self._stop.set()

    def run(self) -> None:
        m = self.mt5_module
        if m is None:
            self._emit_status(MISSING_PACKAGE_MSG)
            return
        try:
            if not self._connect(m):
                return
            self._warmup(m)
            self._emit_status("✅ MT5 live: ticks aa rahe hain — "
                              "perfect candle ka intezar…")
            self._poll_loop(m)
        except Exception as e:
            if not self._stop.is_set():
                self._emit_status(f"❌ MT5 feed band: {type(e).__name__}: {e}")
        finally:
            try:
                m.shutdown()
            except Exception:
                pass

    # -- connection -------------------------------------------------------

    def _connect(self, m) -> bool:
        kwargs = {}
        if self.login:
            kwargs["login"] = self.login
        if self.password:
            kwargs["password"] = self.password
        if self.server:
            kwargs["server"] = self.server
        self._emit_status("MT5 terminal se connect ho raha hai…")
        try:
            ok = m.initialize(**kwargs) if kwargs else m.initialize()
        except Exception as e:
            self._emit_status(f"{MISSING_TERMINAL_MSG} (error: {e})")
            return False
        if not ok:
            err = ""
            try:
                err = f" — last_error: {m.last_error()}"
            except Exception:
                pass
            self._emit_status(MISSING_TERMINAL_MSG + err)
            return False
        if self.login:
            try:
                if not m.login(self.login, password=self.password,
                               server=self.server or None):
                    err = ""
                    try:
                        err = f" — last_error: {m.last_error()}"
                    except Exception:
                        pass
                    self._emit_status(
                        "❌ MT5 login fail — account number, password aur "
                        "server ka naam check karo." + err)
                    return False
            except Exception as e:
                self._emit_status(f"❌ MT5 login fail: {e}")
                return False
        info = None
        try:
            info = m.account_info()
        except Exception:
            info = None
        if info is not None:
            mode = _field(info, "trade_mode")
            self.account = {
                "login": _field(info, "login"),
                "server": _field(info, "server"),
                "balance": _field(info, "balance"),
                "currency": _field(info, "currency"),
                "mode": mode,
                "mode_label": account_mode_label(mode),
            }
            a = self.account
            self._emit_status(
                f"✅ MT5 login OK: account {a['login']} @ {a['server']} | "
                f"mode={a['mode_label']} | balance={a['balance']} "
                f"{a['currency'] or ''} (sirf maloomat — bot trade nahi lagata)")
            if a["mode_label"] == "REAL":
                self._emit_status(
                    "⚠️ Ye REAL MT5 account hai! Signals dekhna theek hai, "
                    "lekin trade apne faisle par — pehle DEMO par test karo.")
            if self.on_account:
                try:
                    self.on_account(a)
                except Exception:
                    pass
        # resolve + select symbols
        try:
            all_symbols = m.symbols_get()
            names = [_field(s, "name") for s in (all_symbols or [])]
        except Exception:
            names = []
        for code in self.assets:
            sym = resolve_symbol(code, names) if names else code
            if sym is None:
                self._emit_status(f"⚠️ {code}: MT5 me ye pair nahi mila — skip")
                continue
            try:
                m.symbol_select(sym, True)
            except Exception:
                pass
            self.symbols[code] = sym
            if sym != code:
                self._emit_status(f"📡 {code}: MT5 symbol '{sym}' use ho raha hai")
        if not self.symbols:
            self._emit_status("❌ MT5: koi asset select nahi hua — feed ruk gaya")
            return False
        return True

    # -- warmup -----------------------------------------------------------

    def _warmup(self, m) -> None:
        """Feed ~130 M1 closes per asset (EMA/RSI warmup, no fake signals)."""
        tf = getattr(m, "TIMEFRAME_M1", 1)
        now = int(time.time())
        for code, sym in self.symbols.items():
            if self._stop.is_set():
                return
            try:
                rates = m.copy_rates_from(sym, tf, now, 130)
            except Exception:
                rates = None
            if not rates:
                self._emit_status(f"⚠️ {code}: MT5 history (warmup) nahi mili — "
                                  "live ticks se hi warmup hoga")
                continue
            for r in rates:
                ts = _field(r, "time")
                close = _field(r, "close")
                try:
                    if ts and close and float(close) > 0:
                        self._emit_point(code, int(ts), float(close))
                except (TypeError, ValueError):
                    continue

    # -- live polling -------------------------------------------------------

    def _poll_loop(self, m) -> None:
        # Prime each cursor at the latest tick so a session only ever
        # emits ticks that arrive AFTER it started.
        for code, sym in self.symbols.items():
            tick = self._safe_tick(m, sym)
            self._cursor_msc[code] = tick_msc(tick) if tick else 0
        while not self._stop.is_set():
            t0 = time.time()
            for code, sym in self.symbols.items():
                if self._stop.is_set():
                    break
                self.poll_asset(m, code, sym)
            self._stop.wait(max(0.0, self.poll_interval - (time.time() - t0)))

    def poll_asset(self, m, code: str, sym: str) -> int:
        """One poll for one asset; returns how many points were emitted.

        Prefers copy_ticks_from (every tick since the cursor); falls
        back to symbol_info_tick when the broker returns nothing.
        Split out of the loop so the rules are unit-testable.
        """
        emitted = 0
        cursor = self._cursor_msc.get(code, 0)
        rows = None
        try:
            rows = m.copy_ticks_from(
                sym, max(0, cursor // 1000 - 2), 10000,
                getattr(m, "COPY_TICKS_ALL", -1))
        except Exception:
            rows = None
        if rows:
            for row in rows:
                msc = tick_msc(row)
                if msc <= cursor:
                    continue
                price = tick_price(row)
                if price is None:
                    continue
                cursor = msc
                self._emit_point(code, msc // 1000, price)
                emitted += 1
            self._cursor_msc[code] = cursor
            return emitted
        tick = self._safe_tick(m, sym)
        if tick:
            msc = tick_msc(tick)
            price = tick_price(tick)
            if msc > cursor and price is not None:
                self._cursor_msc[code] = msc
                self._emit_point(code, msc // 1000, price)
                emitted = 1
        return emitted

    def _safe_tick(self, m, sym):
        try:
            return m.symbol_info_tick(sym)
        except Exception:
            return None

    # -- plumbing -----------------------------------------------------------

    def _emit_status(self, text: str) -> None:
        if self.on_status:
            try:
                self.on_status(text)
            except Exception:
                pass

    def _emit_point(self, code: str, ts: int, price: float) -> None:
        if self.on_point:
            try:
                self.on_point(code, ts, price)
            except Exception:
                pass
