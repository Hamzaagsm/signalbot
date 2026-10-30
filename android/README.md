# Hamza Signal Bot — Android (APK)

Phone twin of the Windows EXE in this repo's root: same Quotex
perfect-candle signal engine (`signals_engine.py` ported faithfully to
`SignalsEngine.java`), same strategies — Perfect Candle (body ≥ 85%),
Engulfing, Pin Bar — EMA 20/50 trend filter, RSI(14) filter, strength
grade (STRONG), WIN/LOSS result tracking overall + per strategy,
30s + 1m candles on OTC pairs.

**SIGNAL ONLY — the app never places orders.** The Quotex client is the
Qx Bot's Socket.IO polling client (OkHttp + DNS-over-HTTPS) with the
order API removed.

## Signal sources (v1.2)
- **Quotex Feed** — Quotex OTC candles (Quotex login).
- **IQ Option Feed** — IQ Option candles (own IQ Option login on the
  login screen; PRACTICE/DEMO by default; regular pairs in market
  hours, OTC pairs when the market is closed). Same iqoptionapi
  protocol as the EXE, read-only: login, balances, candles — no orders.
- **Real Market Feed** — Yahoo Finance quotes, no login at all.

Stats are tracked separately per (source, strategy), like the EXE.

Build: GitHub Actions workflow `.github/workflows/build-apk.yml`
(runs the JVM unit tests, then `gradle assembleRelease`). The release
build is signed with the debug key — the same approach the Hamza Qx Bot
APK used — so the APK installs directly on the phone.

Signals are not a guarantee of profit. Test on demo first.
