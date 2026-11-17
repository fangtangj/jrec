package com.rzx.jrec.desktop.dev;

import com.rzx.jrec.desktop.JRecApp;
import com.rzx.jrec.desktop.ui.ControlBar;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Label;
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

/**
 * Developer utility: proves the global shortcut was accepted by the OS and captures the
 * floating bar.
 * <p>
 * The shortcut cannot be pressed programmatically — {@code RegisterHotKey} hands the
 * combination to the window manager, not to the process. What can be checked is that
 * registration succeeded (which is what the in-app hint reflects) and that the bar
 * appears with a live timer when a recording starts.
 * <p>
 * Not part of the product.
 */
public class UiBarProbe extends Application {

    @Override
    public void start(Stage stage) {
        JRecApp app = new JRecApp();
        try {
            app.start(stage);
        } catch (Exception e) {
            throw new IllegalStateException("无法启动主界面", e);
        }

        after(Duration.seconds(5), () -> {
            System.out.println("[probe] 快捷键提示 = " + labelText(app, "keyHint"));
            try {
                invoke(app, "startRecording");
                System.out.println("[probe] 录制已启动");
            } catch (Exception e) {
                System.out.println("[probe] 启动失败：" + e);
                Platform.exit();
                return;
            }

            after(Duration.seconds(3), () -> {
                ControlBar bar = field(app, "controlBar", ControlBar.class);
                System.out.println("[probe] 悬浮条显示中 = " + (bar != null && bar.isShowing()));
                System.out.println("[probe] 主窗口最小化 = " + stage.isIconified());
                if (bar != null && bar.isShowing()) {
                    snapshot(bar.stage(), "docs/screenshot-bar.png");
                }

                try {
                    invoke(app, "stopRecording");
                } catch (Exception e) {
                    System.out.println("[probe] 停止失败：" + e);
                }

                after(Duration.seconds(6), () -> {
                    System.out.println("[probe] 停止后悬浮条显示中 = " + (bar != null && bar.isShowing()));
                    System.out.println("[probe] 停止后主窗口最小化 = " + stage.isIconified());
                    Platform.exit();
                });
            });
        });
    }

    private static void after(Duration delay, Runnable action) {
        PauseTransition pause = new PauseTransition(delay);
        pause.setOnFinished(e -> action.run());
        pause.play();
    }

    private static String labelText(JRecApp app, String fieldName) {
        try {
            Field field = JRecApp.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            Label label = (Label) field.get(app);
            return label == null ? "<null>" : label.getText() + (label.isVisible() ? "" : " (隐藏)");
        } catch (ReflectiveOperationException e) {
            return "<读取失败:" + e + ">";
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(JRecApp app, String name, Class<T> type) {
        try {
            Field field = JRecApp.class.getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(app);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static void invoke(JRecApp app, String methodName) throws Exception {
        Method method = JRecApp.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(app);
    }

    private static void snapshot(Stage target, String path) {
        try {
            WritableImage image = target.getScene().snapshot(null);
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
            System.out.println("[probe] 悬浮条 " + w + "x" + h + " -> " + path);
        } catch (IOException e) {
            System.out.println("[probe] 快照失败：" + e.getMessage());
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
