package com.rzx.jrec.core.backend;

import java.util.Locale;

/** Selects the {@link CaptureBackend} matching the running operating system. */
public final class Backends {

    private Backends() {
    }

    public static CaptureBackend current() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return new WindowsBackend();
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return new MacBackend();
        }
        return new LinuxBackend();
    }
}
