package com.rzx.jrec.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Finds a usable FFmpeg binary.
 * <p>
 * Resolution order, first hit wins:
 * <ol>
 *   <li>{@code -Djrec.ffmpeg=/path/to/ffmpeg}</li>
 *   <li>environment variable {@code JREC_FFMPEG}</li>
 *   <li>{@code ~/.jrec/bin/} — where {@link FfmpegDownloader} installs it</li>
 *   <li>{@code PATH}</li>
 * </ol>
 * Putting the managed copy ahead of PATH is deliberate: a stale FFmpeg on PATH
 * (e.g. an old 3.x build without {@code -stats_period}) is a common source of
 * confusing failures, so the version we downloaded wins.
 */
public final class FfmpegLocator {

    public static final String EXECUTABLE_NAME =
            System.getProperty("os.name", "").toLowerCase().contains("win") ? "ffmpeg.exe" : "ffmpeg";

    private FfmpegLocator() {
    }

    /** Directory where the auto-downloader keeps its binary. */
    public static Path managedBinDir() {
        return Path.of(System.getProperty("user.home"), ".jrec", "bin");
    }

    public static Path managedExecutable() {
        return managedBinDir().resolve(EXECUTABLE_NAME);
    }

    public static Optional<Path> find() {
        String explicit = System.getProperty("jrec.ffmpeg");
        if (isUsable(explicit)) {
            return Optional.of(Path.of(explicit));
        }
        String fromEnv = System.getenv("JREC_FFMPEG");
        if (isUsable(fromEnv)) {
            return Optional.of(Path.of(fromEnv));
        }
        Path managed = managedExecutable();
        if (Files.isRegularFile(managed)) {
            return Optional.of(managed);
        }
        return fromPath();
    }

    public static Path require() {
        return find().orElseThrow(() -> new RecorderException(
                "找不到 FFmpeg。可以运行 jrec 的内置下载，或手动把 ffmpeg 加入 PATH，"
                        + "也可以用 -Djrec.ffmpeg=<路径> 指定。"));
    }

    private static boolean isUsable(String candidate) {
        return candidate != null && !candidate.isBlank() && Files.isRegularFile(Path.of(candidate));
    }

    private static Optional<Path> fromPath() {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir, EXECUTABLE_NAME);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** First line of {@code ffmpeg -version}, e.g. {@code ffmpeg version 7.1-full_build ...}. */
    public static String version(Path ffmpeg) {
        try {
            Process p = new ProcessBuilder(ffmpeg.toString(), "-version")
                    .redirectErrorStream(true)
                    .start();
            String line;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                line = reader.readLine();
            }
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            return line == null ? "unknown" : line.trim();
        } catch (IOException e) {
            throw new RecorderException("无法执行 FFmpeg：" + ffmpeg + "（" + e.getMessage() + "）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RecorderException("读取 FFmpeg 版本被中断", e);
        }
    }

    /**
     * Lists the encoders this build actually contains, so the UI can hide
     * {@code h264_nvenc} on a machine without an NVIDIA card instead of failing at
     * record time.
     */
    public static List<String> availableH264Encoders(Path ffmpeg) {
        List<String> found = new ArrayList<>();
        List<String> candidates = List.of("libx264", "h264_nvenc", "h264_qsv", "h264_amf");
        try {
            Process p = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-encoders")
                    .redirectErrorStream(true)
                    .start();
            String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            for (String name : candidates) {
                if (text.contains(" " + name + " ")) {
                    found.add(name);
                }
            }
        } catch (IOException e) {
            throw new RecorderException("无法读取 FFmpeg 编码器列表：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return found.isEmpty() ? List.of("libx264") : found;
    }
}
