package com.rzx.jrec.desktop.hotkey;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinUser;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * System-wide shortcut on Windows via {@code RegisterHotKey}.
 * <p>
 * Chosen over a keyboard hook library on purpose: {@code RegisterHotKey} asks the OS
 * for one specific combination, so the application never sees a single other keystroke.
 * A hook would collect everything the user types and filter afterwards — more moving
 * parts, more privilege, and a bad property for a tool that asks for screen access.
 * <p>
 * The awkward part of the Win32 API is that a message-only registration
 * ({@code hWnd == null}) posts {@code WM_HOTKEY} to the <em>thread</em> that registered
 * it. Registration and the message pump therefore have to live on the same thread, which
 * is why this class owns a dedicated loop thread instead of registering from the FX thread.
 * <p>
 * Combinations are tried in order and the first available one wins. A single hard-coded
 * shortcut is wrong on real machines: {@code Ctrl+Alt+R} already failed with
 * {@code ERROR_HOTKEY_ALREADY_REGISTERED} (1409) on the developer's own box because an
 * unrelated application owned it.
 */
public final class WindowsHotkey implements GlobalHotkey {

    private static final int WM_HOTKEY = 0x0312;
    private static final int ID = 0x4A52; // "JR"

    private static final int MOD_ALT = 0x0001;
    private static final int MOD_CONTROL = 0x0002;
    private static final int MOD_SHIFT = 0x0004;

    private record Candidate(String label, int modifiers, int vk) {
    }

    /** Preferred first; the rest are fallbacks for combinations someone else already owns. */
    private static final List<Candidate> CANDIDATES = List.of(
            new Candidate("Ctrl+Alt+R", MOD_CONTROL | MOD_ALT, 0x52),
            new Candidate("Ctrl+Alt+S", MOD_CONTROL | MOD_ALT, 0x53),
            new Candidate("Ctrl+Alt+F9", MOD_CONTROL | MOD_ALT, 0x78),
            new Candidate("Ctrl+Shift+F9", MOD_CONTROL | MOD_SHIFT, 0x78)
    );

    private final AtomicBoolean registered = new AtomicBoolean(false);
    private final AtomicReference<String> boundLabel = new AtomicReference<>("—");
    private Thread loop;

    @Override
    public String label() {
        return boundLabel.get();
    }

    @Override
    public boolean isSupported() {
        return true;
    }

    @Override
    public boolean register(Runnable onTrigger) {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean(false);

        loop = new Thread(() -> {
            try {
                Candidate bound = bindFirstAvailable();
                if (bound == null) {
                    ready.countDown();
                    return;
                }
                boundLabel.set(bound.label());
                registered.set(true);
                ok.set(true);
                ready.countDown();

                WinUser.MSG msg = new WinUser.MSG();
                int result;
                while ((result = User32.INSTANCE.GetMessage(msg, null, 0, 0)) > 0) {
                    if (msg.message == WM_HOTKEY && msg.wParam.intValue() == ID) {
                        onTrigger.run();
                    }
                }
            } catch (Throwable t) {
                // No JNA, no user32, or a restricted session: degrade to the button.
                ready.countDown();
            }
        }, "jrec-hotkey");
        loop.setDaemon(true);
        loop.start();

        try {
            ready.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok.get();
    }

    private Candidate bindFirstAvailable() {
        for (Candidate candidate : CANDIDATES) {
            if (User32.INSTANCE.RegisterHotKey(null, ID, candidate.modifiers(), candidate.vk())) {
                return candidate;
            }
        }
        return null;
    }

    @Override
    public void close() {
        if (registered.getAndSet(false)) {
            try {
                User32.INSTANCE.UnregisterHotKey(null, ID);
            } catch (Throwable ignored) {
                // Shutting down anyway.
            }
        }
        if (loop != null) {
            loop.interrupt();
        }
    }
}
