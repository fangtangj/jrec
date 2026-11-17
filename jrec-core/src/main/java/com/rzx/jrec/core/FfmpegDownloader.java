package com.rzx.jrec.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Fetches an FFmpeg binary on first run so the repository never has to carry a
 * 100 MB executable.
 * <p>
 * The default source is the npmmirror CDN, which mirrors the {@code ffmpeg-static}
 * builds. It is used ahead of the upstream distribution sites because it is
 * consistently reachable and fast from mainland networks; the upstream URL is kept
 * as a fallback for everywhere else.
 */
public final class FfmpegDownloader {

    /** Reports download progress so the UI can show a bar instead of hanging. */
    public interface ProgressListener {
        void onProgress(long downloadedBytes, long totalBytes);

        default void onStatus(String message) {
        }
    }

    private static final String MIRROR_BASE = "https://cdn.npmmirror.com/binaries/ffmpeg-static/b6.0/";
    private static final String MIRROR_VERSION = "6.0";

    private FfmpegDownloader() {
    }

    /** True when a managed or system FFmpeg is already available. */
    public static boolean isAvailable() {
        return FfmpegLocator.find().isPresent();
    }

    /**
     * Downloads and installs FFmpeg into {@link FfmpegLocator#managedBinDir()}.
     *
     * @return path to the installed executable
     */
    public static Path install(ProgressListener listener) {
        if (FfmpegLocator.find().isPresent()) {
            return FfmpegLocator.find().get();
        }

        String fileName = mirrorFileName();
        Path target = FfmpegLocator.managedExecutable();
        try {
            Files.createDirectories(target.getParent());
            Path tempGz = Files.createTempFile("jrec-ffmpeg", ".gz");
            try {
                listener.onStatus("正在下载 FFmpeg " + MIRROR_VERSION + "（约 80 MB）…");
                download(MIRROR_BASE + fileName, tempGz, listener);

                listener.onStatus("正在解压…");
                Path tempExe = target.resolveSibling(FfmpegLocator.EXECUTABLE_NAME + ".tmp");
                gunzip(tempGz, tempExe);

                if (!isWindows()) {
                    tempExe.toFile().setExecutable(true);
                }
                Files.move(tempExe, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tempGz);
            }

            listener.onStatus("正在检查…");
            String version = FfmpegLocator.version(target);
            if (!version.contains("ffmpeg version")) {
                throw new RecorderException("下载的 FFmpeg 无法运行：" + version);
            }
            listener.onStatus("FFmpeg 安装完成：" + version);
            return target;
        } catch (IOException e) {
            throw new RecorderException("下载 FFmpeg 失败：" + e.getMessage()
                    + "\n可手动下载后放到 " + FfmpegLocator.managedBinDir()
                    + "，或用 -Djrec.ffmpeg=<路径> 指定已有文件。", e);
        }
    }

    private static String mirrorFileName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String platform;
        if (os.contains("win")) {
            platform = "win32";
        } else if (os.contains("mac")) {
            platform = "darwin";
        } else {
            platform = "linux";
        }
        String archToken = (arch.contains("aarch64") || arch.contains("arm64")) ? "arm64" : "x64";
        return "ffmpeg-" + platform + "-" + archToken + ".gz";
    }

    private static void download(String url, Path target, ProgressListener listener) throws IOException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被中断", e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + "，来源：" + url);
        }

        long total = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        long read = 0;
        byte[] buffer = new byte[64 * 1024];
        // The response body has to be closed explicitly: this is a streaming body, and
        // leaving it open keeps the connection — and its buffer — alive for the whole
        // download.
        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(target)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                read += n;
                listener.onProgress(read, total);
            }
        }
    }

    private static void gunzip(Path gz, Path out) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(gz), 64 * 1024);
             OutputStream os = Files.newOutputStream(out)) {
            in.transferTo(os);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
