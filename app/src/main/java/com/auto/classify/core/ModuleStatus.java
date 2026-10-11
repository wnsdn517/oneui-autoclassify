package com.auto.classify.core;

/** Hooked to return true by ModuleMain when LSPosed loads the module into its own app. */
public final class ModuleStatus {
    private ModuleStatus() {
    }

    public static boolean isHooked() {
        return false;
    }
}
