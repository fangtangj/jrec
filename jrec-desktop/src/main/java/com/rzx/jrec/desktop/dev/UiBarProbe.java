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
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Developer utility: verifies the global shortcut end to end and captures the bar.
 * <p>
 * Registering a combination and it actually firing are different claims, so this probe
 * does not call the handler — it reads whichever combination the app bound, types it with
 * {@code java.awt.Robot}, and checks the recorder's own state changed. Pressing it twice
 * proves both directions.
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
            String hint = labelText(app, "keyHint");
            System.out.println("[probe] 绑定的快捷键 = " + hint);
            System.out.println("[probe] 已发送按键 = " + pressCombo(hint));

            after(Duration.seconds(3), () -> {
                System.out.println("[probe] 按一次后 录制中 = " + isRecording(app));
                System.out.println("[probe] 按一次后 悬浮条显示 = " + barShowing(app));
                System.out.println("[probe] 按一次后 主窗口最小化 = " + stage.isIconified());

                ControlBar bar = field(app, "controlBar", ControlBar.class);
                if (bar != null && bar.isShowing()) {
                    snapshot(bar.stage(), "docs/screenshot-bar.png");
                }

                System.out.println("[probe] 已再次发送按键 = " + pressCombo(hint));

                after(Duration.seconds(6), () -> {
                    System.out.println("[probe] 按两次后 录制中 = " + isRecording(app));
                    System.out.println("[probe] 按两次后 悬浮条显示 = " + barShowing(app));
                    System.out.println("[probe] 按两次后 主窗口最小化 = " + stage.isIconified());
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

    private static boolean isRecording(JRecApp app) {
        Object recorder = field(app, "recorder", Object.class);
        if (recorder == null) {
            return false;
        }
        try {
            return (Boolean) recorder.getClass().getMethod("isRecording").invoke(recorder);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static boolean barShowing(JRecApp app) {
        ControlBar bar = field(app, "controlBar", ControlBar.class);
        return bar != null && bar.isShowing();
    }

    /** Parses a label like "Ctrl+Alt+S" and types it for real. */
    private static boolean pressCombo(String label) {
        if (label == null || label.contains("无") || label.contains("占用")) {
            return false;
        }
        List<Integer> modifiers = new ArrayList<>();
        int key = -1;
        for (String raw : label.split("\\+")) {
            String token = raw.trim();
            switch (token) {
                case "Ctrl" -> modifiers.add(KeyEvent.VK_CONTROL);
                case "Alt" -> modifiers.add(KeyEvent.VK_ALT);
                case "Shift" -> modifiers.add(KeyEvent.VK_SHIFT);
                default -> {
                    if (token.length() == 1 && Character.isLetter(token.charAt(0))) {
                        key = KeyEvent.VK_A + (Character.toUpperCase(token.charAt(0)) - 'A');
                    } else if (token.startsWith("F")) {
                        key = KeyEvent.VK_F1 + (Integer.parseInt(token.substring(1)) - 1);
                    }
                }
            }
        }
        if (key < 0) {
            return false;
        }
        try {
            java.awt.Robot robot = new java.awt.Robot();
            for (int m : modifiers) {
                robot.keyPress(m);
            }
            robot.keyPress(key);
            robot.keyRelease(key);
            for (int i = modifiers.size() - 1; i >= 0; i--) {
                robot.keyRelease(modifiers.get(i));
            }
            return true;
        } catch (Throwable t) {
            System.out.println("[probe] 无法模拟按键：" + t);
            return false;
        }
    }

    private static String labelText(JRecApp app, String fieldName) {
        try {
            Field field = JRecApp.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            Label label = (Label) field.get(app);
            return label == null ? "<null>" : label.getText();
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
