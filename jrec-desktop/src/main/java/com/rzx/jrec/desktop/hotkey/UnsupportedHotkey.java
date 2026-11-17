package com.rzx.jrec.desktop.hotkey;

/**
 * Placeholder for platforms where no shortcut implementation exists yet.
 * <p>
 * Linux and macOS need their own native calls ({@code XGrabKey}, {@code
 * RegisterEventHotKey}) that this project has not written. Reporting the gap honestly
 * is better than shipping a shortcut that silently refuses to fire.
 */
public final class UnsupportedHotkey implements GlobalHotkey {

    @Override
    public String label() {
        return "—";
    }

    @Override
    public boolean register(HotkeySpec preferred, Runnable onTrigger) {
        return false;
    }

    @Override
    public String failure() {
        return "当前系统没有全局快捷键支持";
    }

    @Override
    public boolean isSupported() {
        return false;
    }

    @Override
    public void close() {
    }
}
