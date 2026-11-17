package com.rzx.jrec.desktop.hotkey;

/**
 * A key combination that works while another application has focus.
 * <p>
 * Implementations must call the callback on a background thread; callers are
 * responsible for hopping to the UI thread.
 */
public interface GlobalHotkey extends AutoCloseable {

    /** Human-readable combination, e.g. {@code Ctrl+Alt+R}. */
    String label();

    /**
     * Registers the combination with the operating system.
     *
     * @return false when the platform cannot register it — usually because another
     *         application already owns the combination, or the platform has no such
     *         concept. Recording still works from the button; only the shortcut is lost.
     */
    boolean register(Runnable onTrigger);

    @Override
    void close();

    /** True when this platform can register system-wide shortcuts at all. */
    boolean isSupported();
}
