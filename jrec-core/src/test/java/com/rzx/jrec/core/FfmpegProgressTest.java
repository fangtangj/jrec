package com.rzx.jrec.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegProgressTest {

    @Test
    void parsesFrameAndTimestamp() {
        String line = "frame=  120 fps= 30 q=23.0 size=     512kB time=00:00:04.00 bitrate=1048.6kbits/s speed=1.00x";

        FfmpegProgress progress = FfmpegProgress.parse(line).orElseThrow();

        assertEquals(120, progress.frame());
        assertEquals(Duration.ofSeconds(4), progress.elapsed());
    }

    @Test
    void parsesHourLongRecording() {
        String line = "frame=108000 fps=30 q=23.0 size=  512000kB time=01:02:03.45 bitrate=1048.6kbits/s speed=1.00x";

        FfmpegProgress progress = FfmpegProgress.parse(line).orElseThrow();

        assertEquals(Duration.ofHours(1).plusMinutes(2).plusSeconds(3).plusMillis(450), progress.elapsed());
    }

    @Test
    void ignoresBannerLines() {
        assertTrue(FfmpegProgress.parse("ffmpeg version 6.0 Copyright (c) 2000-2023").isEmpty());
        assertTrue(FfmpegProgress.parse("").isEmpty());
        assertTrue(FfmpegProgress.parse(null).isEmpty());
    }

    @Test
    void ignoresUnknownTimestamp() {
        // FFmpeg prints time=N/A for the first moments before any frame is written.
        String line = "frame=    0 fps=0.0 q=0.0 size=       0kB time=N/A bitrate=N/A speed=N/A";
        assertTrue(FfmpegProgress.parse(line).isEmpty());
    }
}
