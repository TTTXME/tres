package com.scamera.app.circularbarlib.api;

import com.scamera.app.circularbarlib.console.ManualModeConsoleImpl;

/** Where the app gets its manual-controls panel. */
public final class ManualInstanceProvider {
    private ManualInstanceProvider() {
    }

    /** The process's one panel (made on first use). */
    public static ManualModeConsole sharedConsole() {
        return ManualModeConsoleImpl.getInstance();
    }

    /** A panel of its own (one per camera screen). */
    public static ManualModeConsole newConsole() {
        return ManualModeConsoleImpl.newInstance();
    }
}
