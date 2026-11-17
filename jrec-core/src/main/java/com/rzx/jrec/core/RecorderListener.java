package com.rzx.jrec.core;

import java.time.Duration;

/**
 * Callbacks fired by {@link Recorder}. Implementations must be thread-safe or
 * marshal to their own UI thread: FFmpeg output is parsed on a background thread.
 */
public interface RecorderListener {

    /** FFmpeg has produced its first frame; the output file now exists. */
    default void onStarted(String outputPath) {
    }

    /** Periodic progress update, roughly every {@code progressIntervalMs}. */
    default void onProgress(Duration elapsed, long frame) {
    }

    /** Raw FFmpeg stderr line, useful for a log pane or for diagnosing failures. */
    default void onLog(String line) {
    }

    default void onFinished(RecordingResult result) {
    }

    default void onFailed(String message, Throwable cause) {
    }
}
