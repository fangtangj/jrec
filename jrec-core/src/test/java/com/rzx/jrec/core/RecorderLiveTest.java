package com.rzx.jrec.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live capture tests. These spawn a real FFmpeg process against the real screen, so
 * they only run when an FFmpeg binary is resolvable and a desktop session exists;
 * otherwise they are skipped rather than failed, which keeps CI green on headless
 * builders.
 */
class RecorderLiveTest {

    /**
     * True when this machine can actually capture the screen.
     * <p>
     * FFmpeg being installed is not enough: on a headless build agent the capture
     * backend has no desktop to read, and the recording fails. Without this check the
     * test would fail on every CI runner, or — worse, if the assertion were relaxed to
     * match — pass without ever recording anything.
     */
    private static boolean hasDesktop() {
        try {
            java.awt.GraphicsEnvironment environment =
                    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment();
            if (environment.isHeadless()) {
                return false;
            }
            java.awt.GraphicsDevice[] devices = environment.getScreenDevices();
            if (devices.length == 0) {
                return false;
            }
            java.awt.DisplayMode mode = devices[0].getDisplayMode();
            // A virtual display with no size is what a session without a real screen
            // reports. Capturing it produces nothing worth asserting on.
            return mode.getWidth() > 0 && mode.getHeight() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void assumeDesktop() {
        assumeTrue(FfmpegLocator.find().isPresent(), "FFmpeg 不可用，跳过实拍测试");
        assumeTrue(hasDesktop(), "没有交互式桌面会话，跳过实拍测试");
    }

    @Test
    void recordsARegionToAPlayableMp4(@TempDir Path tempDir) throws Exception {
        assumeDesktop();

        Path output = tempDir.resolve("region.mp4");
        RecordingConfig config = RecordingConfig.builder()
                .region(new Rect(10, 10, 320, 240))
                .fps(10)
                .preset("ultrafast")
                .crf(30)
                .outputPath(output)
                .build();

        List<String> logs = new CopyOnWriteArrayList<>();
        CountDownLatch finished = new CountDownLatch(1);

        Recorder recorder = new Recorder();
        recorder.addListener(new RecorderListener() {
            @Override
            public void onLog(String line) {
                logs.add(line);
            }

            @Override
            public void onFinished(RecordingResult result) {
                finished.countDown();
            }

            @Override
            public void onFailed(String message, Throwable cause) {
                logs.add("FAILED: " + message);
                finished.countDown();
            }
        });

        recorder.start(config);
        Thread.sleep(2000);
        recorder.stop();

        assertTrue(finished.await(10, TimeUnit.SECONDS), "录制应当结束并回调");
        assertTrue(Files.isRegularFile(output), "应当生成 MP4，日志：\n" + String.join("\n", logs));

        MediaProbe.MediaInfo info = MediaProbe.probe(output);
        assertEquals(320, info.width(), "区域录制应当输出精确宽度");
        assertEquals(240, info.height(), "区域录制应当输出精确高度");
        assertTrue(info.duration().compareTo(Duration.ofMillis(1200)) > 0,
                "两秒录制应当得到超过 1.2 秒的视频，实际 " + info.duration());
        assertTrue(info.sizeBytes() > 0);
    }

    @Test
    void reportsFailureInsteadOfHangingWhenOutputDirectoryIsUnwritable(@TempDir Path tempDir) {
        // This one never touches the screen: it fails while preparing the output
        // directory, before FFmpeg is ever started. It needs FFmpeg to be installed
        // (the locator looks for it) but not a desktop session.
        assumeTrue(FfmpegLocator.find().isPresent(), "FFmpeg 不可用，跳过实拍测试");

        // A directory that cannot be created (a file already occupies the name) must
        // surface as a RecorderException rather than a hang or a silent no-op.
        Path blocker = tempDir.resolve("blocked");
        try {
            Files.writeString(blocker, "x");
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        RecordingConfig config = RecordingConfig.builder()
                .region(new Rect(0, 0, 160, 120))
                .outputPath(blocker.resolve("nested").resolve("out.mp4"))
                .build();

        Recorder recorder = new Recorder();
        boolean threw = false;
        try {
            recorder.start(config);
        } catch (RuntimeException e) {
            threw = true;
        } finally {
            recorder.stop();
        }
        assertTrue(threw, "输出目录不可写时应当抛出异常");
    }
}
