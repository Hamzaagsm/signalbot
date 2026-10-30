# Hamza Signal Bot 🔔

**Perfect Candle SIGNAL bot** for Quotex — built around the "Chinese
method": watch 30-second and 1-minute candles, and the moment a candle
closes as a *perfect candle* (its body fills at least the chosen % of
its whole range — default 85%), signal the **next** candle in the same
direction:

- Green perfect candle → **⬆ CALL / UP**
- Red perfect candle → **⬇ PUT / DOWN**

The bot is **broker-independent in spirit**: it only gives the signal
(big banner + beep + log + history). You place the trade yourself, on
whatever broker you like — exactly how the Chinese signal-givers worked.

## Strategies (each can be switched on/off)
1. **Perfect Candle** ⭐ — the main method above.
2. **Engulfing Pattern** — a candle whose body fully engulfs the
   previous candle's body → next candle in the engulfing direction.
3. **Pin Bar / Hammer / Shooting Star** — one long wick (≥60% of the
   candle) rejecting prices with a small body → next candle away from
   the rejection.

## Filters (switchable)
- **Trend Filter (EMA 20/50)** — ON by default: signals against the
  trend are blocked (and the block is written in the log).
- **RSI Filter** — OFF by default: blocks CALL when RSI(14) ≥ 70
  (overbought) and PUT when RSI ≤ 30 (oversold).

## Signal strength grade
Every signal is graded by how many checks agree with it:
pattern (1) + trend agrees (+1) + RSI on the same side of 50 (+1) +
2 or more strategies firing together (+1). **3 or more = ⭐ STRONG.**

## Honest result tracking
Every signal is also **tracked**: when the signalled candle closes,
the bot marks it ✅ WIN / ❌ LOSS / ➖ DRAW and keeps running stats —
overall **and separately for each strategy** (Perfect Candle /
Engulfing / Pin Bar) — on screen and in the log. Run it on demo and
see for yourself which method actually performs. No method wins
every trade; the record is kept in the open, not promised in advance.

## ⚠️ Please read
- **Signals are not a guarantee of profit.**
- **Test on DEMO first**, for days, before even thinking of real money.
- This bot **places NO trades** — v1 is signal-only.
- Quotex has NO official API; the candle feed uses the same
  reverse-engineered QuotexAPI library as Hamza Qx Bot.
- Trend/RSI filters need candle history, so they warm up after start.

## Settings
- Assets: EUR/USD, GBP/USD, USD/JPY, EUR/GBP, AUD/USD, EUR/JPY (OTC).
- Timeframes: 30 second and/or 1 minute.
- Perfect candle body %: 70–95 (default 85). Lower = more signals,
  higher = fewer but stronger-bodied candles.
- Max signals per session: 0 = unlimited.
- Sound on/off.

## Run (Windows EXE)
Download `HamzaSignalBot.exe` from Releases, double-click, enter your
Quotex email/password, keep DEMO selected, press **START SIGNALS**.

## Run (Python)
```
pip install -r requirements.txt
python gui.py
```

## Tests
```
python test_signals.py
```
(offline — checks all three strategies, both filters, strength
grading, WIN/LOSS tracking and per-strategy stats)
