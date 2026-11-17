package com.rzx.jrec.desktop;

/**
 * Entry point for the packaged application.
 * <p>
 * JavaFX refuses to start when the main class extends {@code Application} and the JavaFX
 * modules are on the class path rather than the module path — the launcher reports
 * "JavaFX runtime components are missing". A plain class that delegates sidesteps that
 * check, which is what lets the shipped build be one folder of jars plus a runtime instead
 * of a hand-maintained module graph.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        JRecApp.main(args);
    }
}
