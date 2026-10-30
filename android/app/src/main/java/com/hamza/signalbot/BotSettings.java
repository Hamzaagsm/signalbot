package com.hamza.signalbot;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * User settings for the Signal Bot, persisted on-device.
 * Defaults mirror the Windows EXE v1 exactly: threshold 85%, both
 * timeframes on, all three strategies on, trend filter ON, RSI filter
 * OFF, sound ON, max signals 0 (unlimited), assets EUR/USD + GBP/USD.
 */
public class BotSettings {
    public static final String PREFS = "signalbot_settings";

    /** (code, display) — all Quotex OTC pairs, same list as the EXE. */
    public static final String[][] ASSETS = {
            {"EURUSD", "EUR/USD OTC"},
            {"GBPUSD", "GBP/USD OTC"},
            {"USDJPY", "USD/JPY OTC"},
            {"EURGBP", "EUR/GBP OTC"},
            {"AUDUSD", "AUD/USD OTC"},
            {"EURJPY", "EUR/JPY OTC"},
    };

    public double thresholdPct = 85.0;
    public int maxSignals = 0;
    public boolean sound = true;
    public boolean stratPerfect = true;
    public boolean stratEngulfing = true;
    public boolean stratPinbar = true;
    public boolean trendFilter = true;
    public boolean rsiFilter = false;
    public boolean tf30 = true;
    public boolean tf60 = true;
    public final Set<String> assetCodes = new LinkedHashSet<>();

    public BotSettings() {
        assetCodes.add("EURUSD");
        assetCodes.add("GBPUSD");
    }

    public Set<String> strategies() {
        Set<String> s = new LinkedHashSet<>();
        if (stratPerfect) s.add("perfect");
        if (stratEngulfing) s.add("engulfing");
        if (stratPinbar) s.add("pinbar");
        return s;
    }

    public java.util.List<Integer> timeframes() {
        java.util.List<Integer> t = new java.util.ArrayList<>();
        if (tf30) t.add(30);
        if (tf60) t.add(60);
        return t;
    }

    /** asset_key ("EURUSD_otc") -> display ("EUR/USD OTC"), selection order. */
    public java.util.LinkedHashMap<String, String> assetMap() {
        java.util.LinkedHashMap<String, String> m = new java.util.LinkedHashMap<>();
        for (String[] a : ASSETS)
            if (assetCodes.contains(a[0])) m.put(a[0] + "_otc", a[1]);
        return m;
    }

    /** Real Market mode: plain asset code -> display without " OTC" (EXE v1.1 jaisa). */
    public java.util.LinkedHashMap<String, String> assetMapRealMarket() {
        java.util.LinkedHashMap<String, String> m = new java.util.LinkedHashMap<>();
        for (String[] a : ASSETS)
            if (assetCodes.contains(a[0])) m.put(a[0], a[1].replace(" OTC", ""));
        return m;
    }

    public static BotSettings load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        BotSettings s = new BotSettings();
        s.thresholdPct = p.getFloat("threshold", 85f);
        s.maxSignals = p.getInt("max_signals", 0);
        s.sound = p.getBoolean("sound", true);
        s.stratPerfect = p.getBoolean("strat_perfect", true);
        s.stratEngulfing = p.getBoolean("strat_engulfing", true);
        s.stratPinbar = p.getBoolean("strat_pinbar", true);
        s.trendFilter = p.getBoolean("trend", true);
        s.rsiFilter = p.getBoolean("rsi", false);
        s.tf30 = p.getBoolean("tf30", true);
        s.tf60 = p.getBoolean("tf60", true);
        s.assetCodes.clear();
        for (String[] a : ASSETS)
            if (p.getBoolean("asset_" + a[0], a[0].equals("EURUSD") || a[0].equals("GBPUSD")))
                s.assetCodes.add(a[0]);
        return s;
    }

    public void save(Context ctx) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putFloat("threshold", (float) thresholdPct);
        e.putInt("max_signals", maxSignals);
        e.putBoolean("sound", sound);
        e.putBoolean("strat_perfect", stratPerfect);
        e.putBoolean("strat_engulfing", stratEngulfing);
        e.putBoolean("strat_pinbar", stratPinbar);
        e.putBoolean("trend", trendFilter);
        e.putBoolean("rsi", rsiFilter);
        e.putBoolean("tf30", tf30);
        e.putBoolean("tf60", tf60);
        for (String[] a : ASSETS) e.putBoolean("asset_" + a[0], assetCodes.contains(a[0]));
        e.apply();
    }
}
