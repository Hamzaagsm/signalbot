#!/usr/bin/env python3
"""Hamza Signal Bot v1.3 - GUI.

PERFECT-CANDLE SIGNAL bot (the "Chinese method"):
watches 30-second and 1-minute candles; when a candle closes with its
body >= threshold % of its range (default 85%), it signals the NEXT
candle in the same direction (green -> CALL/UP, red -> PUT/DOWN).

v1.1 added a SIGNAL SOURCE choice:
  * Quotex Feed    - the v1.0 behaviour (Quotex OTC candles, login).
  * Real Market Feed - real forex quotes polled over HTTPS from Yahoo
    Finance (realmarket_feed.py). NO Quotex login needed. Hamza's
    logic: binary/OTC prices move AFTER the real market, so signals
    are read where the move starts. Stats are tracked separately per
    (source, strategy), so demo runs show which feed performs better.

v1.2 adds a third source (Hamza: "bot IQ Option aur Quotex aur baqi
binary options par work kare, sirf Quotex nahi"):
  * IQ Option Feed - IQ Option login (iqoption_feed.py); PRACTICE
    balance by default; regular pairs in market hours, OTC pairs
    when the forex market is closed. Same engine, source stamp
    "iqoption", stats kept separately again.
  * HAR platform ka login ALAG hai (Hamza ki wazahat): Quotex Login
    frame Quotex ke liye, IQ Option Login frame IQ Option ke liye —
    koi shared login form nahi; jo source select ho, usi ka login
    frame active hota hai.

v1.3 adds a fourth source (Phase 2, EXE only):
  * MT5 Feed - MetaTrader 5 terminal (mt5_feed.py): apna alag MT5
    login (account number + password + server). Official MetaTrader5
    package PC par installed MT5 terminal se ticks leta hai; 30s/1m
    candles wahi engine banata hai, source stamp "mt5". DEMO account
    recommended. (APK me MT5 nahi — third-party Android apps ke liye
    koi official MT5 API nahi hai.)

SIGNAL ONLY - this bot places NO trades. It shows a big banner, plays a
sound and logs the signal; the user places the trade wherever he wants.
Every signal's result is tracked automatically (WIN / LOSS / DRAW) with
running stats, exactly like the Chinese signal-givers worked - except
here the record is kept honestly, in the open.

Uses the vendored QuotexAPI (streaming candles via WebSocket), same as
Hamza Qx Bot v1.1. DEMO account is the default.
"""
import tkinter as tk
from tkinter import ttk, scrolledtext, messagebox
import threading, sys, os, asyncio
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from signals_engine import SignalEngine

try:
    import winsound
except ImportError:  # non-Windows (dev machines)
    winsound = None

ASSETS = [  # (code, display) - all Quotex OTC pairs
    ("EURUSD", "EUR/USD OTC"),
    ("GBPUSD", "GBP/USD OTC"),
    ("USDJPY", "USD/JPY OTC"),
    ("EURGBP", "EUR/GBP OTC"),
    ("AUDUSD", "AUD/USD OTC"),
    ("EURJPY", "EUR/JPY OTC"),
]
DEFAULT_ASSETS = {"EURUSD", "GBPUSD"}


class SignalBotGUI:
    def __init__(self, root):
        self.root = root
        root.title("Hamza Signal Bot v1.3 🔔")
        root.geometry("740x1060")
        root.configure(bg="#0a0a14")
        self.running = False
        self.engine = None
        self.feed = None       # RealMarketFeed when source = realmarket
        self.iq_feed = None    # IqOptionFeed when source = iqoption
        self.mt5_feed = None   # Mt5Feed when source = mt5 (v1.3)
        self._rm_points = 0
        self._iq_points = 0
        self._mt5_points = 0
        self.hist_index = {}  # id(signal) -> listbox index

        hdr = tk.Label(root, text="🔔 HAMZA SIGNAL BOT", font=("Arial", 20, "bold"),
                       bg="#0a0a14", fg="#00d4aa")
        hdr.pack(pady=8)
        tk.Label(root, text="Perfect Candle Signals (30s / 1m) — SIGNAL ONLY, no auto trade",
                 bg="#0a0a14", fg="#888").pack()

        # ── Signal source ─────────────────────────────────────────────
        srcf = tk.LabelFrame(root, text="Signal Source — signal kahan se aaye", bg="#0a0a14",
                             fg="#00d4aa", font=("Arial", 10, "bold"))
        srcf.pack(fill="x", padx=15, pady=6)
        self.source_var = ttk.Combobox(srcf, values=["Quotex Feed", "IQ Option Feed", "MT5 Feed", "Real Market Feed"],
                                       width=20, state="readonly")
        self.source_var.current(0)
        self.source_var.pack(side="left", padx=8, pady=4)
        self.source_note = tk.Label(srcf, text="Quotex login chahiye — signals Quotex OTC candles se",
                                    bg="#0a0a14", fg="#888")
        self.source_note.pack(side="left", padx=4)
        self.source_var.bind("<<ComboboxSelected>>", lambda e: self._apply_source_ui())
        tk.Label(srcf, text="Balance:", bg="#0a0a14", fg="#fff").pack(side="left", padx=(12, 2))
        self.mode = ttk.Combobox(srcf, values=["DEMO", "REAL (Asli paisa!)"], width=16, state="readonly")
        self.mode.current(0)
        self.mode.pack(side="left", padx=4, pady=4)

        # ── Logins — HAR platform ka APNA alag login (Hamza ki hidayat:
        # Quotex ka login Quotex wala, IQ Option ka login IQ Option wala;
        # koi shared login form nahi). Jo source select ho, sirf usi
        # platform ka login frame active rehta hai.
        self.qx_frame, self.qx_email, self.qx_pw = self._login_frame(
            root, "Quotex Login — apna Quotex account")
        self.iq_frame, self.iq_email, self.iq_pw = self._login_frame(
            root, "IQ Option Login — apna IQ Option account")

        # ── MT5 ka apna alag login (v1.3): account number + password +
        # server. Terminal PC par installed hona chahiye; DEMO account
        # behtar hai. Fields khali chhorho to chal raha terminal jo
        # account logged-in hai, usi se jud jayega.
        mf = tk.LabelFrame(root, text="MT5 Login — apna MT5 account (DEMO behtar hai)",
                           bg="#0a0a14", fg="#00d4aa", font=("Arial", 10, "bold"))
        mf.pack(fill="x", padx=15, pady=4)
        self.mt5_frame = mf
        tk.Label(mf, text="Login #:", bg="#0a0a14", fg="#fff").grid(
            row=0, column=0, padx=6, pady=3, sticky="w")
        self.mt5_login = tk.Entry(mf, width=14, bg="#1a1a2e", fg="#fff",
                                  insertbackground="#fff")
        self.mt5_login.grid(row=0, column=1, padx=4, pady=3)
        tk.Label(mf, text="Password:", bg="#0a0a14", fg="#fff").grid(
            row=0, column=2, padx=6, pady=3, sticky="w")
        self.mt5_pw = tk.Entry(mf, width=16, show="*", bg="#1a1a2e", fg="#fff",
                               insertbackground="#fff")
        self.mt5_pw.grid(row=0, column=3, padx=4, pady=3)
        tk.Label(mf, text="Server:", bg="#0a0a14", fg="#fff").grid(
            row=0, column=4, padx=6, pady=3, sticky="w")
        self.mt5_server = tk.Entry(mf, width=24, bg="#1a1a2e", fg="#fff",
                                   insertbackground="#fff")
        self.mt5_server.grid(row=0, column=5, padx=4, pady=3)

        # ── Assets + timeframes ───────────────────────────────────────
        af = tk.LabelFrame(root, text="Assets (OTC) — jo pairs dekhne hain", bg="#0a0a14",
                           fg="#00d4aa", font=("Arial", 10, "bold"))
        self.assets_frame = af
        af.pack(fill="x", padx=15, pady=6)
        self.asset_vars = {}
        self.asset_checks = {}
        for i, (code, disp) in enumerate(ASSETS):
            var = tk.BooleanVar(value=code in DEFAULT_ASSETS)
            self.asset_vars[code] = var
            cb = tk.Checkbutton(af, text=disp, variable=var, bg="#0a0a14", fg="#fff",
                                selectcolor="#1a1a2e", activebackground="#0a0a14",
                                activeforeground="#fff")
            cb.grid(row=i // 3, column=i % 3, padx=8, pady=2, sticky="w")
            self.asset_checks[code] = cb
        tf_row = tk.Frame(af, bg="#0a0a14")
        tf_row.grid(row=2, column=0, columnspan=3, sticky="w", padx=8, pady=4)
        tk.Label(tf_row, text="Timeframes:", bg="#0a0a14", fg="#fff").pack(side="left")
        self.tf30 = tk.BooleanVar(value=True)
        self.tf60 = tk.BooleanVar(value=True)
        for text, var in (("30 second", self.tf30), ("1 minute", self.tf60)):
            tk.Checkbutton(tf_row, text=text, variable=var, bg="#0a0a14", fg="#fff",
                           selectcolor="#1a1a2e", activebackground="#0a0a14",
                           activeforeground="#fff").pack(side="left", padx=6)

        # ── Strategies + filters ──────────────────────────────────────
        stf = tk.LabelFrame(root, text="Strategies & Filters — jo tareeqay on rakhne hain", bg="#0a0a14",
                            fg="#00d4aa", font=("Arial", 10, "bold"))
        stf.pack(fill="x", padx=15, pady=6)
        self.strat_vars = {}
        for i, (key, label, default) in enumerate((
                ("perfect", "Perfect Candle ⭐ (main)", True),
                ("engulfing", "Engulfing Pattern", True),
                ("pinbar", "Pin Bar / Hammer", True))):
            var = tk.BooleanVar(value=default)
            self.strat_vars[key] = var
            tk.Checkbutton(stf, text=label, variable=var, bg="#0a0a14", fg="#fff",
                           selectcolor="#1a1a2e", activebackground="#0a0a14",
                           activeforeground="#fff").grid(row=0, column=i, padx=8, pady=2, sticky="w")
        self.trend_var = tk.BooleanVar(value=True)
        tk.Checkbutton(stf, text="Trend Filter (EMA 20/50) — sirf trend ki simt signals", variable=self.trend_var,
                       bg="#0a0a14", fg="#fff", selectcolor="#1a1a2e", activebackground="#0a0a14",
                       activeforeground="#fff").grid(row=1, column=0, columnspan=2, padx=8, pady=2, sticky="w")
        self.rsi_var = tk.BooleanVar(value=False)
        tk.Checkbutton(stf, text="RSI Filter — overbought (70+) par CALL band, oversold (30-) par PUT band",
                       variable=self.rsi_var, bg="#0a0a14", fg="#fff",
                       selectcolor="#1a1a2e", activebackground="#0a0a14",
                       activeforeground="#fff").grid(row=2, column=0, columnspan=3, padx=8, pady=2, sticky="w")

        # ── Settings ──────────────────────────────────────────────────
        sf = tk.LabelFrame(root, text="Settings", bg="#0a0a14", fg="#00d4aa", font=("Arial", 10, "bold"))
        sf.pack(fill="x", padx=15, pady=6)
        tk.Label(sf, text="Perfect Candle body %:", bg="#0a0a14", fg="#fff").grid(row=0, column=0, padx=8, pady=4, sticky="w")
        self.threshold = tk.Spinbox(sf, from_=70, to=95, increment=1, width=6,
                                    bg="#1a1a2e", fg="#fff", buttonbackground="#1a1a2e")
        self.threshold.delete(0, "end")
        self.threshold.insert(0, "85")
        self.threshold.grid(row=0, column=1, padx=8, pady=4, sticky="w")
        tk.Label(sf, text="Max signals (0 = unlimited):", bg="#0a0a14", fg="#fff").grid(row=0, column=2, padx=8, pady=4, sticky="w")
        self.max_signals = tk.Entry(sf, width=8, bg="#1a1a2e", fg="#fff", insertbackground="#fff")
        self.max_signals.insert(0, "0")
        self.max_signals.grid(row=0, column=3, padx=8, pady=4, sticky="w")
        self.sound_var = tk.BooleanVar(value=True)
        tk.Checkbutton(sf, text="Sound (beep)", variable=self.sound_var, bg="#0a0a14", fg="#fff",
                       selectcolor="#1a1a2e", activebackground="#0a0a14",
                       activeforeground="#fff").grid(row=1, column=0, padx=8, pady=2, sticky="w")

        # ── Buttons ───────────────────────────────────────────────────
        bf = tk.Frame(root, bg="#0a0a14")
        bf.pack(pady=8)
        self.start_btn = tk.Button(bf, text="▶ START SIGNALS", font=("Arial", 14, "bold"),
                                   bg="#00d4aa", fg="#000", width=16, command=self.start_bot)
        self.start_btn.pack(side="left", padx=10)
        self.stop_btn = tk.Button(bf, text="⏹ STOP", font=("Arial", 14, "bold"),
                                  bg="#e94560", fg="#fff", width=12, command=self.stop_bot,
                                  state="disabled")
        self.stop_btn.pack(side="left", padx=10)

        # ── Signal banner ─────────────────────────────────────────────
        self.banner = tk.Label(root, text="— abhi koi signal nahi —", font=("Arial", 16, "bold"),
                               bg="#1a1a2e", fg="#888", pady=10)
        self.banner.pack(fill="x", padx=15, pady=6)

        self.stats = tk.Label(root, text="Signals: 0 | WIN: 0 | LOSS: 0 | Win%: —",
                              bg="#0a0a14", fg="#00d4aa", font=("Arial", 12, "bold"))
        self.stats.pack(pady=2)
        self.kind_stats = tk.Label(root, text="", bg="#0a0a14", fg="#aaa", font=("Arial", 9))
        self.kind_stats.pack()
        self.status = tk.Label(root, text="", bg="#0a0a14", fg="#ffaa00", font=("Arial", 10))
        self.status.pack()

        # ── Signal history ────────────────────────────────────────────
        tk.Label(root, text="Signal History:", bg="#0a0a14", fg="#888").pack(anchor="w", padx=15)
        hf = tk.Frame(root, bg="#0a0a14")
        hf.pack(fill="x", padx=15, pady=2)
        self.history = tk.Listbox(hf, height=6, bg="#111", fg="#fff", font=("Consolas", 10))
        self.history.pack(side="left", fill="x", expand=True)
        hsb = tk.Scrollbar(hf, command=self.history.yview)
        hsb.pack(side="right", fill="y")
        self.history.config(yscrollcommand=hsb.set)

        # ── Log ───────────────────────────────────────────────────────
        tk.Label(root, text="Log:", bg="#0a0a14", fg="#888").pack(anchor="w", padx=15)
        self.log = scrolledtext.ScrolledText(root, height=8, bg="#111", fg="#0f0",
                                             font=("Consolas", 9))
        self.log.pack(fill="both", expand=True, padx=15, pady=5)

        tk.Label(root, text="Signals are not a guarantee of profit. Test on demo first.",
                 bg="#0a0a14", fg="#e94560", font=("Arial", 9, "italic")).pack(pady=4)

        self.log_msg("Signal Bot v1.3 tayyar! Pehle DEMO par test karo. ⚠️")
        self.log_msg("Perfect candle = body candle ke kul range ka diya gaya % ya zyada. Engulfing aur Pin Bar bhi on hain.")
        self.log_msg("Source upar se chuno: Quotex Feed (OTC), IQ Option Feed (login, PRACTICE default), MT5 Feed (PC terminal, DEMO behtar) ya Real Market Feed (asal market, login nahi).")
        self.log_msg("Har platform ka login ALAG hai: Quotex ka apna frame, IQ Option ka apna frame, MT5 ka apna frame — jo source select hoga, usi ka login chalega.")
        self._apply_source_ui()

    # ── login frame builder (har platform ka alag frame) ────────────

    def _login_frame(self, root, title):
        """Ek platform ka apna login frame: (frame, email_entry, pw_entry)."""
        f = tk.LabelFrame(root, text=title, bg="#0a0a14", fg="#00d4aa",
                          font=("Arial", 10, "bold"))
        f.pack(fill="x", padx=15, pady=4)
        tk.Label(f, text="Email:", bg="#0a0a14", fg="#fff").grid(
            row=0, column=0, padx=8, pady=3, sticky="w")
        email = tk.Entry(f, width=35, bg="#1a1a2e", fg="#fff", insertbackground="#fff")
        email.grid(row=0, column=1, padx=8, pady=3)
        tk.Label(f, text="Password:", bg="#0a0a14", fg="#fff").grid(
            row=0, column=2, padx=8, pady=3, sticky="w")
        pw = tk.Entry(f, width=28, show="*", bg="#1a1a2e", fg="#fff",
                      insertbackground="#fff")
        pw.grid(row=0, column=3, padx=8, pady=3)
        return f, email, pw

    @staticmethod
    def _set_fields(state, *entries):
        for e in entries:
            e.config(state=state)

    def creds_for(self, src):
        """Selected source ke APNE login fields se (email, password)."""
        if src == "quotex":
            return self.qx_email.get(), self.qx_pw.get()
        if src == "iqoption":
            return self.iq_email.get(), self.iq_pw.get()
        return "", ""

    # ── GUI helpers (thread-safe via after) ───────────────────────────

    def log_msg(self, msg):
        ts = datetime.now().strftime("%H:%M:%S")
        def _w():
            self.log.insert("end", f"[{ts}] {msg}\n")
            self.log.see("end")
        self.root.after(0, _w)

    def set_status(self, msg):
        self.root.after(0, lambda: self.status.config(text=msg))

    def set_status_full(self, msg, color="#ffaa00"):
        self.root.after(0, lambda: self.status.config(text=msg, fg=color))

    # ── signal source UI ──────────────────────────────────────────────

    def current_source(self):
        v = self.source_var.get()
        if v.startswith("Real"):
            return "realmarket"
        if v.startswith("IQ"):
            return "iqoption"
        if v.startswith("MT5"):
            return "mt5"
        return "quotex"

    def _apply_source_ui(self):
        src = self.current_source()
        rm = src == "realmarket"
        # Har platform ka login SIRF apne source par active — baqi band.
        self._set_fields("normal" if src == "quotex" else "disabled",
                         self.qx_email, self.qx_pw)
        self._set_fields("normal" if src == "iqoption" else "disabled",
                         self.iq_email, self.iq_pw)
        self._set_fields("normal" if src == "mt5" else "disabled",
                         self.mt5_login, self.mt5_pw, self.mt5_server)
        # MT5 ka DEMO/REAL account khud tay karta hai — Balance selector
        # wahan lagu nahi; Real Market par bhi login/mode band hai.
        self.mode.config(state="disabled" if rm or src == "mt5" else "readonly")
        disp_map = dict(ASSETS)
        for code, cb in self.asset_checks.items():
            cb.config(text=disp_map[code] if src == "quotex"
                      else disp_map[code].replace(" OTC", ""))
        self.assets_frame.config(
            text={"realmarket": "Assets (Real Market) — jo pairs dekhne hain",
                  "iqoption": "Assets (IQ Option) — jo pairs dekhne hain",
                  "mt5": "Assets (MT5) — jo pairs dekhne hain"}.get(
                      src, "Assets (OTC) — jo pairs dekhne hain"))
        if src == "mt5":
            self.source_note.config(
                text="MT5 terminal (PC par installed) chahiye — DEMO account behtar hai")
            self.log_msg("MT5 mode: signals tumhare PC ke MT5 terminal ke ticks se ayenge — MT5 Login frame me account number + server likho (terminal pehle se khula/logged-in ho to fields khali bhi chalenge).")
        elif src == "realmarket":
            self.source_note.config(
                text="Real Market = asal forex data (Yahoo) — koi login NAHI chahiye")
            self.log_msg("Real Market mode: kisi broker ka login NAHI chahiye — signals asal market data (Yahoo Finance) se ayenge.")
        elif src == "iqoption":
            self.source_note.config(
                text="IQ Option login chahiye — Balance DEMO = IQ ka PRACTICE account")
            self.log_msg("IQ Option mode: signals IQ Option candles se ayenge — neeche IQ Option Login wale frame me IQ Option wala email/password likho, Balance DEMO (PRACTICE) rakho.")
        else:
            self.source_note.config(
                text="Quotex login chahiye — signals Quotex OTC candles se")
            self.log_msg("Quotex mode: signals Quotex OTC candles se ayenge — Quotex Login wale frame me login likho.")

    def beep(self, kind="signal"):
        if not self.sound_var.get():
            return
        def _b():
            try:
                if winsound:
                    if kind == "signal":
                        winsound.Beep(880, 250); winsound.Beep(1320, 400)
                    elif kind == "win":
                        winsound.Beep(1046, 200); winsound.Beep(1568, 300)
                    else:
                        winsound.Beep(220, 400)
                else:
                    self.root.bell()
            except Exception:
                pass
        threading.Thread(target=_b, daemon=True).start()

    # ── engine callbacks (called from the bot thread) ─────────────────

    def _tf_label(self, tf):
        return "30s" if tf == 30 else "1m"

    def _grade_text(self, sig):
        return f"⭐ STRONG ({sig.strength})" if sig.grade == "STRONG" else f"{sig.grade} ({sig.strength})"

    def on_signal(self, sig):
        arrow = "⬆ CALL" if sig.direction == "call" else "⬇ PUT"
        tstr = datetime.fromtimestamp(sig.issued_at).strftime("%H:%M:%S")
        line = (f"{tstr}  [{sig.source_label}]  {arrow}  {sig.asset}  {self._tf_label(sig.timeframe)}"
                f"  [{sig.kind_label}: {sig.detail}]  {self._grade_text(sig)}  → natija pending…")

        def _ui():
            self.history.insert("end", line)
            self.history.see("end")
            self.hist_index[id(sig)] = self.history.size() - 1
            color = "#0f7a3d" if sig.direction == "call" else "#a11"
            self.banner.config(
                text=f"🔔 {arrow} — {sig.asset} ({self._tf_label(sig.timeframe)}) — {sig.kind_label} — {self._grade_text(sig)} — {tstr} — {sig.source_label}",
                bg=color, fg="#fff")
        self.root.after(0, _ui)
        self.beep("signal")
        self.log_msg(f"🔔 SIGNAL [{sig.kind_label}] {arrow} {sig.asset} {self._tf_label(sig.timeframe)} "
                     f"@ {tstr} ({sig.detail}, grade {sig.grade} {sig.strength})")

    def on_blocked(self, kind, direction, why):
        from signals_engine import KIND_LABELS
        arrow = "CALL" if direction == "call" else "PUT"
        self.log_msg(f"🚫 Blocked [{KIND_LABELS.get(kind, kind)}] {arrow} — {why}")

    def on_result(self, sig):
        mark = {"WIN": "✅ WIN", "LOSS": "❌ LOSS", "DRAW": "➖ DRAW"}[sig.result]
        arrow = "⬆ CALL" if sig.direction == "call" else "⬇ PUT"
        tstr = datetime.fromtimestamp(sig.issued_at).strftime("%H:%M:%S")
        line = (f"{tstr}  [{sig.source_label}]  {arrow}  {sig.asset}  {self._tf_label(sig.timeframe)}"
                f"  [{sig.kind_label}: {sig.detail}]  {self._grade_text(sig)}  → {mark}")
        idx = self.hist_index.get(id(sig))

        def _ui():
            if idx is not None and idx < self.history.size():
                self.history.delete(idx)
                self.history.insert(idx, line)
            self._refresh_stats()
        self.root.after(0, _ui)
        self.beep("win" if sig.result == "WIN" else "loss" if sig.result == "LOSS" else "signal")
        self.log_msg(f"{mark}: [{sig.kind_label}] {arrow} {sig.asset} {self._tf_label(sig.timeframe)} @ {tstr}")

    def _refresh_stats(self):
        if not self.engine:
            return
        st = self.engine.stats
        pct = f"{st.win_pct:.0f}%" if st.win_pct is not None else "—"
        self.stats.config(text=f"Signals: {st.total} | WIN: {st.wins} | LOSS: {st.losses} "
                               f"| DRAW: {st.draws} | Win%: {pct}")
        from signals_engine import KIND_LABELS, SOURCE_LABELS
        src = self.engine.source
        parts = []
        for kind, label in KIND_LABELS.items():
            ks = self.engine.stats_by_source_kind.get((src, kind),
                                                      self.engine.stats_by_kind[kind])
            kp = f"{ks.win_pct:.0f}%" if ks.win_pct is not None else "—"
            parts.append(f"{label}: {ks.total} signals, W{ks.wins}/L{ks.losses} ({kp})")
        self.kind_stats.config(text=f"{SOURCE_LABELS.get(src, src)} — " + "  •  ".join(parts))

    # ── start / stop ──────────────────────────────────────────────────

    def start_bot(self):
        src = self.current_source()
        if src in ("quotex", "iqoption"):
            _em, _pw = self.creds_for(src)
            if not _em or not _pw:
                messagebox.showerror(
                    "Error",
                    "Quotex Login frame me Email aur Password likho!" if src == "quotex"
                    else "IQ Option Login frame me Email aur Password likho!")
                return
        if src == "mt5":
            _login_txt = self.mt5_login.get().strip()
            if _login_txt and not _login_txt.isdigit():
                messagebox.showerror("Error", "MT5 Login # sirf number hota hai — sahi account number likho!")
                return
            if _login_txt and not self.mt5_server.get().strip():
                messagebox.showerror("Error", "MT5 Server ka naam likho (MT5 terminal me jo server hai, bilkul waisa)!")
                return
        assets = [code for code, _ in ASSETS if self.asset_vars[code].get()]
        if not assets:
            messagebox.showerror("Error", "Kam az kam aik asset select karo!")
            return
        tfs = ([30] if self.tf30.get() else []) + ([60] if self.tf60.get() else [])
        if not tfs:
            messagebox.showerror("Error", "Kam az kam aik timeframe select karo!")
            return
        strategies = tuple(k for k, v in self.strat_vars.items() if v.get())
        if not strategies:
            messagebox.showerror("Error", "Kam az kam aik strategy on karo!")
            return
        try:
            threshold = float(self.threshold.get())
            if not (70 <= threshold <= 95):
                raise ValueError
            max_sig = int(self.max_signals.get())
            if max_sig < 0:
                raise ValueError
        except ValueError:
            messagebox.showerror("Error", "Settings ghalat hain: body % 70–95 ke darmiyan, max signals 0 ya zyada.")
            return
        if src in ("quotex", "iqoption") and self.mode.get().startswith("REAL"):
            ok = messagebox.askyesno("⚠️ KHATRA!",
                "REAL account mode!\n\n• Ye sirf SIGNALS dega — trade tum khud lagaoge.\n"
                "• Asli paisa lagana tumhara apna faisla hai — nuqsan ho sakta hai!\n\nPhir bhi chalana hai?")
            if not ok:
                return

        if src == "quotex":
            asset_map = {code + "_otc": disp for code, disp in ASSETS if code in assets}
        else:
            # realmarket + iqoption: plain codes, display without " OTC"
            asset_map = {code: disp.replace(" OTC", "") for code, disp in ASSETS if code in assets}
        self.engine = SignalEngine(asset_map, tfs, threshold_pct=threshold,
                                   strategies=strategies,
                                   trend_filter=self.trend_var.get(),
                                   rsi_filter=self.rsi_var.get(),
                                   max_signals=max_sig,
                                   source=src,
                                   on_signal=self.on_signal, on_result=self.on_result,
                                   on_blocked=self.on_blocked)
        self.log_msg(f"Strategies: {', '.join(strategies)} | Trend filter: "
                     f"{'ON' if self.trend_var.get() else 'OFF'} | RSI filter: "
                     f"{'ON' if self.rsi_var.get() else 'OFF'} | Body %: {threshold:.0f}")
        self.log_msg("Note: Trend/RSI ko candles ki history chahiye — shuru me kuch waqt warmup lagega.")
        self.hist_index = {}
        self.running = True
        self.start_btn.config(state="disabled")
        self.stop_btn.config(state="normal")
        self.banner.config(text="— signal ka intezar… —", bg="#1a1a2e", fg="#888")
        if src == "realmarket":
            self._start_realmarket(assets)
        elif src == "iqoption":
            self._start_iqoption(assets)
        elif src == "mt5":
            self._start_mt5(assets)
        else:
            threading.Thread(target=self.run_async_bot, args=(assets,), daemon=True).start()

    # ── Real Market mode ──────────────────────────────────────────────

    def _start_realmarket(self, assets):
        try:
            from realmarket_feed import RealMarketFeed
        except ImportError as e:
            self.log_msg(f"❌ Real Market feed load nahi hui: {e}")
            self.stop_bot()
            return
        self._rm_points = 0

        def on_point(code, ts, price):
            if not self.running or not self.engine:
                return
            self._rm_points += 1
            try:
                self.engine.feed(code, {"timestamp": ts, "price": price})
            except Exception as e:
                self.log_msg(f"⚠️ Data process error: {e}")

        self.feed = RealMarketFeed(assets, on_point=on_point, on_status=self.log_msg)
        self.feed.start()
        self.log_msg("📡 Real Market (Yahoo Finance) polling shuru — koi login nahi, koi account nahi, sirf signals.")
        self.set_status("📡 Real Market: pehle data ka intezar…")
        self._rm_status_tick()

    def _rm_status_tick(self):
        if not self.running or self.feed is None:
            return
        feed = self.feed
        if feed.last_poll is None:
            self.set_status_full("📡 Real Market: pehle data ka intezar…")
        elif feed.is_stale(10):
            tstr = datetime.fromtimestamp(feed.last_poll).strftime("%H:%M:%S")
            self.set_status_full(
                f"⚠️ STALE — Real Market data ruk gaya hai! Last update {tstr} — connection check karo",
                color="#e94560")
        else:
            tstr = datetime.fromtimestamp(feed.last_poll).strftime("%H:%M:%S")
            lag = feed.data_lag()
            lag_txt = f" • data lag ~{lag:.0f}s" if lag is not None else ""
            self.set_status_full(
                f"📡 Real Market (Yahoo) • last update {tstr}{lag_txt} • {self._rm_points} points — perfect candle ka intezar…")
        self.root.after(2000, self._rm_status_tick)

    # ── IQ Option mode (v1.2) ─────────────────────────────────────────

    def _start_iqoption(self, assets):
        try:
            from iqoption_feed import IqOptionFeed
        except ImportError as e:
            self.log_msg(f"❌ IQ Option feed load nahi hui: {e}")
            self.stop_bot()
            return
        self._iq_points = 0
        practice = self.mode.get() == "DEMO"

        def on_candle(code, point):
            if not self.running or not self.engine:
                return
            self._iq_points += 1
            if self._iq_points % 50 == 1:
                self.set_status(f"✅ IQ Option live: stream chal raha hai "
                                f"({self._iq_points} updates) — perfect candle ka intezar…")
            try:
                self.engine.feed(code, point)
            except Exception as e:
                self.log_msg(f"⚠️ Data process error: {e}")

        self.iq_feed = IqOptionFeed(
            self.iq_email.get(), self.iq_pw.get(), assets, practice=practice,
            on_candle=on_candle, on_status=self.log_msg)
        threading.Thread(target=self.iq_feed.run, daemon=True,
                         name="iq-feed").start()
        self.log_msg(f"📡 IQ Option feed shuru — mode: "
                     f"{'PRACTICE (DEMO)' if practice else 'REAL'} (sirf candles, bot trade nahi lagata).")
        self.set_status("📡 IQ Option: login ho raha hai…")

    # ── MT5 mode (v1.3, EXE only) ─────────────────────────────────────

    def _start_mt5(self, assets):
        try:
            import mt5_feed
            from mt5_feed import Mt5Feed
        except ImportError as e:
            self.log_msg(f"❌ MT5 feed load nahi hui: {e}")
            self.stop_bot()
            return
        if not mt5_feed.mt5_available():
            self.log_msg(mt5_feed.MISSING_PACKAGE_MSG)
            self.set_status("❌ MT5 package is build me nahi mila")
            self.stop_bot()
            return
        self._mt5_points = 0
        login_txt = self.mt5_login.get().strip()
        login_num = int(login_txt) if login_txt.isdigit() else 0
        pw_txt = self.mt5_pw.get()
        server_txt = self.mt5_server.get().strip()

        def on_point(code, ts, price):
            if not self.running or not self.engine:
                return
            self._mt5_points += 1
            if self._mt5_points % 50 == 1:
                self.set_status(f"✅ MT5 live: ticks chal rahe hain "
                                f"({self._mt5_points} updates) — perfect candle ka intezar…")
            try:
                self.engine.feed(code, {"timestamp": ts, "price": price})
            except Exception as e:
                self.log_msg(f"⚠️ Data process error: {e}")

        self.mt5_feed = Mt5Feed(
            login=login_num, password=pw_txt, server=server_txt,
            assets=assets, on_point=on_point, on_status=self.log_msg)
        threading.Thread(target=self.mt5_feed.run, daemon=True,
                         name="mt5-feed").start()
        self.log_msg("📡 MT5 feed shuru — PC ke MT5 terminal se ticks ayenge (sirf candles, bot trade nahi lagata).")
        self.set_status("📡 MT5: terminal se connect ho raha hai…")

    def stop_bot(self):
        self.running = False
        if self.feed is not None:
            try:
                self.feed.stop()
            except Exception:
                pass
            self.feed = None
        if self.iq_feed is not None:
            try:
                self.iq_feed.stop()
            except Exception:
                pass
            self.iq_feed = None
        if self.mt5_feed is not None:
            try:
                self.mt5_feed.stop()
            except Exception:
                pass
            self.mt5_feed = None
        self.root.after(0, lambda: self.start_btn.config(state="normal"))
        self.root.after(0, lambda: self.stop_btn.config(state="disabled"))
        self.set_status("")
        self.log_msg("Bot roka gaya.")

    def run_async_bot(self, assets):
        asyncio.run(self.bot_loop(assets))

    async def bot_loop(self, assets):
        try:
            from QuotexAPI import QuotexAPI, AccountType
        except ImportError as e:
            self.log_msg(f"❌ QuotexAPI load nahi hui: {e}")
            self.stop_bot()
            return

        api = None
        try:
            self.log_msg("Quotex se connect ho raha...")
            api = QuotexAPI(email=self.qx_email.get(), password=self.qx_pw.get())
            profile = await api.connect()
            em = getattr(profile, "email", "OK")
            self.log_msg(f"✅ Connected: {em}")

            is_demo = self.mode.get() == "DEMO"
            try:
                bal = await api.switch_account(AccountType.DEMO if is_demo else AccountType.REAL)
                bal_amt = getattr(bal, "amount", bal)
            except Exception as e:
                self.log_msg(f"⚠️ Account switch: {e}")
                nb = await api.get_balance()
                bal_amt = getattr(nb, "amount", nb)
            self.log_msg(f"Mode={'DEMO' if is_demo else 'REAL'} | Balance=${bal_amt} (sirf maloomat — bot trade nahi lagata)")

            loop = asyncio.get_running_loop()
            q = asyncio.Queue()

            for code in assets:
                qx_key = code + "_otc"

                def make_cb(key):
                    def on_candle(data):
                        try:
                            loop.call_soon_threadsafe(q.put_nowait, (key, data))
                        except Exception:
                            pass
                    return on_candle

                api.data.on_candle(qx_key, make_cb(qx_key))
                await api.data.subscribe_candles(code, timeframe=60)
                self.log_msg(f"📡 {code} OTC candles subscribe ho gaye.")

            self.set_status("✅ Live: candles aa rahe hain — perfect candle ka intezar…")
            n_points = 0
            while self.running:
                try:
                    key, data = await asyncio.wait_for(q.get(), timeout=30)
                except asyncio.TimeoutError:
                    self.set_status("⏳ Candle data ka intezar… (stream khamosh hai)")
                    continue
                n_points += 1
                if n_points % 50 == 1:
                    self.set_status(f"✅ Live: stream chal raha hai ({n_points} updates) — perfect candle ka intezar…")
                try:
                    self.engine.feed(key, dict(data) if isinstance(data, dict) else data)
                except Exception as e:
                    self.log_msg(f"⚠️ Data process error: {e}")

            self.log_msg("Session khatam.")
        except Exception as e:
            self.log_msg(f"❌ Fatal: {type(e).__name__}: {e}")
        finally:
            if api:
                try:
                    await api.disconnect()
                except Exception:
                    pass
        self.stop_bot()


# ── FUTURE AUTO-TRADE HOOK ────────────────────────────────────────────
# v1/v1.1/v1.2/v1.3 are SIGNAL-ONLY by design: the engine emits signals through
# SignalBotGUI.on_signal / SignalEngine.on_signal and results are only
# TRACKED, never traded. A future auto-trade version would subscribe an
# additional on_signal handler here and call api.buy(...) with the
# user's chosen stake/expiry. Intentionally not implemented.


if __name__ == "__main__":
    root = tk.Tk()
    app = SignalBotGUI(root)
    root.mainloop()
