package com.auto.classify.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The one settings file. LSPosed relocates world-readable prefs (so the hooked launcher can
 * read them); every reader in the module app must open it the same way or it would see a
 * different, empty file.
 */
public final class Prefs {

    private Prefs() {
    }

    @SuppressWarnings("deprecation")
    public static SharedPreferences open(Context context) {
        try {
            return context.getSharedPreferences(Config.PREFS, Context.MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            return context.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE);
        }
    }

    /** True when LSPosed accepted the world-readable mode, i.e. the module is active. */
    @SuppressWarnings("deprecation")
    public static boolean moduleActive(Context context) {
        try {
            context.getSharedPreferences(Config.PREFS, Context.MODE_WORLD_READABLE);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }
}
