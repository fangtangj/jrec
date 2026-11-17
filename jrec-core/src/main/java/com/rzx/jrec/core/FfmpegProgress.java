package com.rzx.jrec.core;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the progress lines FFmpeg writes to stderr, e.g.
 * <pre>frame=  120 fps= 30 q=23.0 size=     512kB time=00:00:04.00 bitrate=1048.6kbits/s speed=1.00x</pre>
 * Timing lines are the only reliable clock here: {@code frame=} alone cannot tell
 * us how much wall-clock video has been produced, because FFmpeg drops frames when
 * the encoder cannot keep up.
 */
public record FfmpegProgress(long frame, Duration elapsed) {

    private static final Pattern FRAME = Pattern.compile("frame=\\s*(\\d+)");
    private static final Pattern TIME = Pattern.compile("time=(\\d+):(\\d{2}):(\\d{2}(?:\\.\\d+)?)");

    /**
     * @return the progress carried by this line, or empty for any other line
     *         (banner, encoder info, warnings)
     */
    public static Optional<FfmpegProgress> parse(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        Matcher timeMatcher = TIME.matcher(line);
        if (!timeMatcher.find()) {
            return Optional.empty();
        }
        long frame = 0;
        Matcher frameMatcher = FRAME.matcher(line);
        if (frameMatcher.find()) {
            frame = Long.parseLong(frameMatcher.group(1));
        }
        long seconds = Long.parseLong(timeMatcher.group(1)) * 3600
                + Long.parseLong(timeMatcher.group(2)) * 60L;
        double fractional = Double.parseDouble(timeMatcher.group(3));
        Duration elapsed = Duration.ofSeconds(seconds).plusMillis(Math.round(fractional * 1000));
        return Optional.of(new FfmpegProgress(frame, elapsed));
    }
}
