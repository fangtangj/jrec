package com.rzx.jrec.desktop.hotkey;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinUser;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 * it. Registration and the message pump therefore have to live on the same thread, and
 * unbinding has to happen on that thread too — {@code UnregisterHotKey} with a null
 * window acts on the <em>calling</em> thread's queue, so calling it from the FX thread
 * would silently target the wrong one.
 */
public final class WindowsHotkey implements GlobalHotkey {

    private static final int WM_HOTKEY = 0x0312;
    private static final int WM_QUIT = 0x0012;
    private static final int ID = 0x4A52; // "JR"

    private static final int MOD_ALT = 0x0001;
    private static final int MOD_CONTROL = 0x0002;
    private static final int MOD_SHIFT = 0x0004;
    private static final int MOD_NOREPEAT = 0x4000;

    /** Win32 ERROR_HOTKEY_ALREADY_REGISTERED. */
    private static final int ERROR_ALREADY_REGISTERED = 1409;

    private final AtomicBoolean registered = new AtomicBoolean(false);
    private final AtomicReference<String> boundLabel = new AtomicReference<>("—");
    private final AtomicReference<String> failure = new AtomicReference<>();
    private final AtomicInteger loopThreadId = new AtomicInteger();
    private Thread loop;

    @Override
    public String label() {
        return boundLabel.get();
    }

    @Override
    public String failure() {
        return failure.get();
    }

    @Override
    public boolean isSupported() {
        return true;
    }

    @Override
    public boolean register(HotkeySpec preferred, Runnable onTrigger) {
        return register(preferred, onTrigger, false);
    }

    @Override
    public boolean registerWithFallback(HotkeySpec preferred, Runnable onTrigger) {
        return register(preferred, onTrigger, true);
    }

    /**
     * @param allowFallback true to bind a free built-in combination when the preferred one
     *                      is refused. Used at startup, where the saved combination may have
     *                      been taken by another program since the last run.
     */
    private boolean register(HotkeySpec preferred, Runnable onTrigger, boolean allowFallback) {
        close();
        failure.set(null);

        CountDownLatch ready = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean(false);

        loop = new Thread(() -> {
            try {
                loopThreadId.set(Kernel32.INSTANCE.GetCurrentThreadId());

                // A null preferred means "whatever is free", which is the same request as
                // "this one, and fall back if it is taken" — so both go through the
                // fallback selector. An explicitly chosen combination is bound as-is.
                HotkeyCandidates.Selection selection = (preferred == null || allowFallback)
                        ? HotkeyCandidates.selectWithFallback(preferred, this::tryBind)
                        : HotkeyCandidates.selectExact(preferred, this::tryBind);

                HotkeySpec bound = selection.chosen();
                if (bound == null) {
                    if (selection.preferredFailure() != null) {
                        failure.set(selection.preferredFailure());
                    } else if (preferred == null) {
                        failure.set("默认组合全被占用");
                    }
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
                if (failure.get() == null) {
                    failure.set("系统拒绝了注册：" + t.getMessage());
                }
                ready.countDown();
            } finally {
                if (registered.get()) {
                    try {
                        User32.INSTANCE.UnregisterHotKey(null, ID);
                    } catch (Throwable ignored) {
                        // Process is going away.
                    }
                    registered.set(false);
                }
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

    /**
     * Registers one combination with the system.
     *
     * @return null when the system accepted it, otherwise why it refused
     */
    private String tryBind(HotkeySpec spec) {
        // MOD_NOREPEAT: holding the key fires once. Without it, a held Ctrl+Alt+S toggles
        // recording on and off several times a second.
        boolean bound = User32.INSTANCE.RegisterHotKey(null, ID,
                spec.modifierMask() | MOD_NOREPEAT, spec.vk());
        if (bound) {
            return null;
        }
        int error = Kernel32.INSTANCE.GetLastError();
        return error == ERROR_ALREADY_REGISTERED
                ? spec.label() + " 已被其它程序占用"
                : spec.label() + " 注册失败（错误码 " + error + "）";
    }

    @Override
    public void close() {
        int threadId = loopThreadId.getAndSet(0);
        if (threadId != 0) {
            // GetMessage blocks until a message arrives, so interrupting the Java thread
            // does nothing. WM_QUIT makes GetMessage return 0, which ends the loop and lets
            // the finally block unregister from the thread that owns the registration.
            try {
                User32.INSTANCE.PostThreadMessage(threadId, WM_QUIT,
                        new WinUser.WPARAM(0), new WinUser.LPARAM(0));
            } catch (Throwable ignored) {
                // Nothing left to unbind if the queue is already gone.
            }
        }
        if (loop != null) {
            try {
                // The loop thread unregisters the combination itself on the way out, so
                // it has to be given time to finish. Waiting for it — rather than for a
                // fixed guess — is what makes the release reliable.
                loop.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (loop.isAlive()) {
                // It did not finish. The registration is released by the thread's own
                // finally block whenever it does, but saying so beats pretending the
                // shortcut is gone while the system still holds it.
                failure.set("快捷键未能及时释放，可能仍被系统占用");
            }
            loop = null;
        }
        registered.set(false);
        boundLabel.set("—");
    }
}
