package com.rzx.jrec.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads duration, resolution and audio presence out of a finished file.
 * <p>
 * Uses {@code ffmpeg -i} rather than {@code ffprobe} on purpose: only one binary is
 * downloaded and shipped, and the human-readable stream dump is stable enough to
 * parse for the three facts the file list needs.
 */
public final class MediaProbe {

    private static final Pattern DURATION =
            Pattern.compile("Duration:\\s*(\\d+):(\\d{2}):(\\d{2}(?:\\.\\d+)?)");
    private static final Pattern VIDEO_STREAM =
            Pattern.compile("Stream #\\d+:\\d+.*?: Video: .*?(\\d{2,5})x(\\d{2,5})");
    private static final Pattern AUDIO_STREAM =
            Pattern.compile("Stream #\\d+:\\d+.*?: Audio: ");

    private MediaProbe() {
    }

    public record MediaInfo(Path path, Duration duration, int width, int height, boolean hasAudio, long sizeBytes) {

        public boolean isLandscape() {
            return width >= height;
        }

        public String resolution() {
            return width > 0 && height > 0 ? width + "x" + height : "未知";
        }

        public String durationText() {
            long total = duration.getSeconds();
            return String.format("%02d:%02d", total / 60, total % 60);
        }
    }

    public static MediaInfo probe(Path file) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            size = -1;
        }

        String text;
        try {
            Process p = new ProcessBuilder(FfmpegLocator.require().toString(),
                    "-hide_banner", "-i", file.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            try (InputStream in = p.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                in.transferTo(buffer);
                text = buffer.toString(StandardCharsets.UTF_8);
            }
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (IOException e) {
            return new MediaInfo(file, Duration.ZERO, 0, 0, false, size);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new MediaInfo(file, Duration.ZERO, 0, 0, false, size);
        }

        Duration duration = Duration.ZERO;
        Matcher dur = DURATION.matcher(text);
        if (dur.find()) {
            duration = Duration.ofHours(Long.parseLong(dur.group(1)))
                    .plusMinutes(Long.parseLong(dur.group(2)))
                    .plusMillis(Math.round(Double.parseDouble(dur.group(3)) * 1000));
        }

        int width = 0;
        int height = 0;
        Matcher video = VIDEO_STREAM.matcher(text);
        if (video.find()) {
            width = Integer.parseInt(video.group(1));
            height = Integer.parseInt(video.group(2));
        }

        return new MediaInfo(file, duration, width, height, AUDIO_STREAM.matcher(text).find(), size);
    }
}
