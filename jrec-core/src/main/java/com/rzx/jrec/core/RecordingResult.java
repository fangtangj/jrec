package com.rzx.jrec.core;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Outcome of a finished recording.
 *
 * @param outputPath the MP4 that was written
 * @param duration   wall-clock duration of the produced video
 * @param sizeBytes  file size on disk, or -1 if it could not be read
 */
public record RecordingResult(Path outputPath, Duration duration, long sizeBytes) {

    public String humanReadableSize() {
        if (sizeBytes < 0) {
            return "未知";
        }
        double mb = sizeBytes / 1024.0 / 1024.0;
        return mb >= 1024 ? String.format("%.2f GB", mb / 1024) : String.format("%.2f MB", mb);
    }
}
