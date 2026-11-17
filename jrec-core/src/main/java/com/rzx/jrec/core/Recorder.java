package com.rzx.jrec.core;

import com.rzx.jrec.core.backend.Backends;
import com.rzx.jrec.core.backend.CaptureBackend;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives one FFmpeg process through its lifecycle.
 * <p>
 * Stopping deserves a note: killing the process would leave an MP4 without its
 * {@code moov} index, which is unplayable. FFmpeg accepts a {@code q} on stdin to
 * finalise the file and exit cleanly, so that is the primary stop path; a hard
 * destroy only happens if the process ignores {@code q} for
 * {@link #GRACEFUL_STOP_TIMEOUT_SECONDS}. The {@code q} path is verified on
 * Windows and is how every successful recording in this project ends.
 * <p>
 * Not thread-safe by design: one recorder owns one recording. Callers driving it
 * from a UI thread are expected to run {@link #start} and {@link #stop} off the
 * event dispatch thread, because both block on process I/O.
 */
public final class Recorder {

    private static final int GRACEFUL_STOP_TIMEOUT_SECONDS = 15;
    private static final int LOG_TAIL_LINES = 40;

    private final CaptureBackend backend;
    private final List<RecorderListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<RecorderState> state = new AtomicReference<>(RecorderState.IDLE);
    private final Deque<String> logTail = new ArrayDeque<>(LOG_TAIL_LINES);

    private volatile Process process;
    private volatile Thread pumpThread;
    private volatile RecordingConfig config;
    private volatile FfmpegProgress lastProgress;
    private volatile boolean stopRequested;

    public Recorder() {
        this(Backends.current());
    }

    public Recorder(CaptureBackend backend) {
        this.backend = backend;
    }

    public CaptureBackend backend() {
        return backend;
    }

    public RecorderState state() {
        return state.get();
    }

    public boolean isRecording() {
        return state.get() == RecorderState.RECORDING;
    }

    public RecordingConfig config() {
        return config;
    }

    public void addListener(RecorderListener listener) {
        listeners.add(listener);
    }

    public void removeListener(RecorderListener listener) {
        listeners.remove(listener);
    }

    /**
     * Launches FFmpeg. Returns as soon as the process is up; progress arrives via
     * listeners on the pump thread.
     *
     * @throws RecorderException if the configuration is invalid, FFmpeg is missing or
     *                           the process cannot be started
     */
    public void start(RecordingConfig config) {
        if (!state.compareAndSet(RecorderState.IDLE, RecorderState.STARTING)
                && !state.compareAndSet(RecorderState.FAILED, RecorderState.STARTING)) {
            throw new RecorderException("当前状态不能开始录制：" + state.get());
        }

        this.config = config;
        try {
            config.validate();
            Path ffmpeg = FfmpegLocator.require();
            Path parent = config.outputPath().toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            List<String> cmd = CommandBuilder.build(ffmpeg.toString(), config, backend);
            logTail.clear();
            stopRequested = false;
            lastProgress = null;
            emitLog("$ " + CommandBuilder.toDisplayString(cmd));

            Process p;
            try {
                p = new ProcessBuilder(cmd)
                        .redirectErrorStream(true)
                        .start();
            } catch (IOException e) {
                throw new RecorderException("启动 FFmpeg 失败：" + e.getMessage(), e);
            }
            this.process = p;
            state.set(RecorderState.RECORDING);

            Thread thread = new Thread(() -> pump(p), "jrec-ffmpeg-pump");
            thread.setDaemon(true);
            this.pumpThread = thread;
            thread.start();
        } catch (RecorderException e) {
            // The exception is the notification channel here: start() is synchronous,
            // so firing onFailed as well would double-report the same problem.
            state.set(RecorderState.FAILED);
            throw e;
        } catch (IOException e) {
            state.set(RecorderState.FAILED);
            throw new RecorderException("准备输出目录失败：" + e.getMessage(), e);
        }
    }

    /**
     * Asks FFmpeg to finalise the file and exit. Blocks until the process is gone or
     * the graceful timeout expires.
     *
     * @return the result, which is also delivered to listeners
     */
    public Optional<RecordingResult> stop() {
        Process p = this.process;
        if (p == null || !p.isAlive()) {
            return Optional.empty();
        }
        state.set(RecorderState.STOPPING);
        stopRequested = true;

        try (OutputStream stdin = p.getOutputStream()) {
            stdin.write('q');
            stdin.write('\n');
            stdin.flush();
        } catch (IOException e) {
            emitLog("无法向 FFmpeg 发送停止指令，将强制结束：" + e.getMessage());
        }

        try {
            if (!p.waitFor(GRACEFUL_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                emitLog("FFmpeg " + GRACEFUL_STOP_TIMEOUT_SECONDS + " 秒内未退出，强制结束（文件可能不完整）");
                p.destroy();
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Thread pump = this.pumpThread;
        if (pump != null) {
            try {
                pump.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return Optional.ofNullable(lastResult);
    }

    /** Result of the most recent finished recording, for callers that missed the event. */
    private volatile RecordingResult lastResult;

    private void pump(Process p) {
        boolean startEmitted = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                rememberLog(line);
                emitLog(line);

                Optional<FfmpegProgress> progress = FfmpegProgress.parse(line);
                if (progress.isPresent()) {
                    lastProgress = progress.get();
                    if (!startEmitted) {
                        startEmitted = true;
                        emitStarted(config.outputPath().toString());
                    }
                    emitProgress(progress.get().elapsed(), progress.get().frame());
                }
            }
        } catch (IOException e) {
            emitLog("读取 FFmpeg 输出失败：" + e.getMessage());
        }

        int exitCode;
        try {
            exitCode = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exitCode = -1;
        }

        Path output = config.outputPath();
        boolean fileUsable = Files.isRegularFile(output) && sizeOf(output) > 0;
        // A file that exists is not the same as a file that is finished. FFmpeg can
        // exit non-zero after having written most of the stream, leaving a file that
        // plays up to the point where it stopped. Reporting that as a success would
        // tell the user the take is ready when it is silently truncated.
        boolean exitedCleanly = exitCode == 0;

        if (fileUsable && exitedCleanly) {
            Duration duration = lastProgress != null ? lastProgress.elapsed() : Duration.ZERO;
            RecordingResult result = new RecordingResult(output, duration, sizeOf(output));
            lastResult = result;
            state.set(RecorderState.IDLE);
            emitLog("录制结束：" + output + "（" + result.humanReadableSize() + "）");
            emitFinished(result);
        } else if (fileUsable) {
            state.set(RecorderState.FAILED);
            String tail = String.join("\n", logTail);
            emitFailed("FFmpeg 以错误码 " + exitCode + " 退出，文件可能不完整（"
                    + output.getFileName() + "）。FFmpeg 输出：\n" + tail, null);
        } else {
            state.set(RecorderState.FAILED);
            String tail = String.join("\n", logTail);
            String reason = stopRequested
                    ? "录制结束时没有生成可用文件。FFmpeg 输出：\n" + tail
                    : "FFmpeg 意外退出（code=" + exitCode + "）。输出：\n" + tail;
            emitFailed(reason, null);
        }
    }

    private void rememberLog(String line) {
        synchronized (logTail) {
            if (logTail.size() == LOG_TAIL_LINES) {
                logTail.removeFirst();
            }
            logTail.addLast(line);
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1;
        }
    }

    private void emitStarted(String path) {
        for (RecorderListener l : listeners) {
            l.onStarted(path);
        }
    }

    private void emitProgress(Duration elapsed, long frame) {
        for (RecorderListener l : listeners) {
            l.onProgress(elapsed, frame);
        }
    }

    private void emitLog(String line) {
        for (RecorderListener l : listeners) {
            l.onLog(line);
        }
    }

    private void emitFinished(RecordingResult result) {
        for (RecorderListener l : listeners) {
            l.onFinished(result);
        }
    }

    private void emitFailed(String message, Throwable cause) {
        for (RecorderListener l : listeners) {
            l.onFailed(message, cause);
        }
    }
}
