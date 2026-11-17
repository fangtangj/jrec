package com.rzx.jrec.desktop.dev;

import com.rzx.jrec.desktop.JRecApp;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.TextField;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Developer utility: drives the real UI through one full record cycle —
 * press record, let it run, press stop, confirm the file and the history row appear.
 * <p>
 * The engine already has live tests; what this exercises is the part tests cannot
 * reach cheaply: listener wiring, the view swap, the timer and the history refresh.
 * It snapshots the recording state and the finished state along the way.
 * <p>
 * Not part of the product. Run it with the module path set for JavaFX, output dir
 * as the first argument (defaults to a temp folder).
 */
public class UiFlowProbe extends Application {

    private static final Duration SETTLE = Duration.seconds(7);
    private static final Duration CAPTURE = Duration.seconds(4);
    private Path outputDir;

    @Override
    public void start(Stage stage) {
        outputDir = resolveOutputDir();

        JRecApp app = new JRecApp();
        try {
            app.start(stage);
        } catch (Exception e) {
            throw new IllegalStateException("无法启动主界面", e);
        }

        // Device probing is asynchronous; give it time to finish before recording.
        after(SETTLE, () -> {
            try {
                setOutputDir(app, outputDir);
                invoke(app, "startRecording");
                log("录制已启动");
            } catch (Exception e) {
                log("启动录制失败：" + e);
                Platform.exit();
                return;
            }
            after(CAPTURE, () -> {
                // Captured while FFmpeg is still running: proves the timer and the
                // recording view are actually live, not just the engine.
                snapshot(stage, "docs/screenshot-recording.png");
                try {
                    invoke(app, "stopRecording");
                    log("已请求停止");
                } catch (Exception e) {
                    log("停止失败：" + e);
                }
                after(Duration.seconds(6), () -> {
                    snapshot(stage, "docs/screenshot-history.png");
                    reportFiles();
                    Platform.exit();
                });
            });
        });
    }

    private Path resolveOutputDir() {
        String arg = getParameters().getRaw().isEmpty() ? null : getParameters().getRaw().get(0);
        try {
            Path dir = arg != null
                    ? Path.of(arg)
                    : Files.createTempDirectory("jrec-probe");
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new IllegalStateException("无法准备输出目录", e);
        }
    }

    private void after(Duration delay, Runnable action) {
        PauseTransition pause = new PauseTransition(delay);
        pause.setOnFinished(e -> action.run());
        pause.play();
    }

    private void setOutputDir(JRecApp app, Path dir) throws Exception {
        Field field = JRecApp.class.getDeclaredField("dirField");
        field.setAccessible(true);
        TextField textField = (TextField) field.get(app);
        textField.setText(dir.toAbsolutePath().toString());
    }

    private void invoke(JRecApp app, String methodName) throws Exception {
        Method method = JRecApp.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(app);
    }

    private void snapshot(Stage stage, String path) {
        try {
            WritableImage image = stage.getScene().snapshot(null);
            int w = (int) Math.round(image.getWidth());
            int h = (int) Math.round(image.getHeight());
            BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            PixelReader reader = image.getPixelReader();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    buffered.setRGB(x, y, reader.getArgb(x, y));
                }
            }
            File out = new File(path);
            File parent = out.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            ImageIO.write(buffered, "png", out);
            log("快照 " + path);
        } catch (IOException e) {
            log("快照失败：" + e.getMessage());
        }
    }

    private void reportFiles() {
        try (var stream = Files.list(outputDir)) {
            stream.filter(p -> p.toString().endsWith(".mp4")).forEach(p -> {
                try {
                    log("产物 " + p.getFileName() + " " + Files.size(p) + " bytes");
                } catch (IOException e) {
                    log("产物 " + p.getFileName() + " 大小未知");
                }
            });
        } catch (IOException e) {
            log("无法列出产物：" + e.getMessage());
        }
    }

    private static void log(String message) {
        System.out.println("[probe] " + message);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
