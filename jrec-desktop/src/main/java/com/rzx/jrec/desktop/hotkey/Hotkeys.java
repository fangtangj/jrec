package com.rzx.jrec.desktop.hotkey;

import java.util.Locale;

/** Picks the system-wide shortcut implementation for the running platform. */
public final class Hotkeys {

    private Hotkeys() {
    }

    public static GlobalHotkey create() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return new WindowsHotkey();
        }
        return new UnsupportedHotkey();
    }
}
