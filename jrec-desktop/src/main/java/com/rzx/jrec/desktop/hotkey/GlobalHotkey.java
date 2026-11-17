package com.rzx.jrec.desktop.hotkey;

/**
 * A key combination that works while another application has focus.
 * <p>
 * Implementations must call the callback on a background thread; callers are
 * responsible for hopping to the UI thread.
 */
public interface GlobalHotkey extends AutoCloseable {

    /** Human-readable combination actually bound, e.g. {@code Ctrl+Alt+R}. */
    String label();

    /**
     * Registers a combination with the operating system.
     *
     * @param preferred the combination the user chose, or null to pick the first free one
     *                  from the built-in list. A chosen combination is never silently
     *                  replaced — if it is taken, registration fails and {@link #failure()}
     *                  explains why, because quietly binding something else would leave the
     *                  user pressing a key that does nothing.
     * @return false when registration did not happen
     */
    boolean register(HotkeySpec preferred, Runnable onTrigger);

    /**
     * Registers a combination, falling back to a free built-in one when it is refused.
     * <p>
     * This is the startup path. A combination that was free yesterday may be owned by
     * another program today, and a recorder whose only shortcut stopped working would
     * silently stop being used. The user is told which combination actually got bound,
     * so nothing is replaced quietly.
     *
     * @param preferred the combination the user chose, or null to pick the first free one
     * @return false when nothing could be registered
     */
    boolean registerWithFallback(HotkeySpec preferred, Runnable onTrigger);

    /** Why the last {@link #register} failed, or null when it succeeded. */
    String failure();

    @Override
    void close();

    /** True when this platform can register system-wide shortcuts at all. */
    boolean isSupported();
}
