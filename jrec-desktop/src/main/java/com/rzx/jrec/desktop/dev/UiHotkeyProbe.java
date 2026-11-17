package com.rzx.jrec.desktop.dev;

import com.rzx.jrec.desktop.JRecApp;
import com.rzx.jrec.desktop.hotkey.HotkeySpec;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.util.Duration;


import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Developer utility: proves the shortcut can be reassigned from the settings sheet.
 * <p>
 * Three separate claims are checked, because the first one passing means nothing about
 * the others:
 * <ol>
 *   <li>a key press in the field produces a combination and the field shows it</li>
 *   <li>a combination another application owns is refused, and the working one survives</li>
 *   <li>the newly assigned combination, pressed for real, starts and stops a recording</li>
 * </ol>
 * Not part of the product.
 */
public class UiHotkeyProbe extends Application {

    private JRecApp app;

    @Override
    public void start(Stage stage) {
        app = new JRecApp();
        try {
            app.start(stage);
        } catch (Exception e) {
            throw new IllegalStateException("无法启动主界面", e);
        }

        after(5, () -> {
            log("启动后绑定 = " + text(app, "boundSpec"));

            Dialog<?> dialog = dialog();
            if (dialog == null) {
                log("打不开设置弹窗");
                Platform.exit();
                return;
            }
            dialog.show();

            after(1, () -> {
                log("弹窗打开后快捷键是否释放 = " + (field(app, "hotkey", Object.class) == null));

                // 1. Assign Ctrl+Alt+F10 by typing it into the recorder field.
                //    Registration is asynchronous, so the wait is for the field to show
                //    the new combination — not for a fixed number of seconds.
                pressInField(KeyCode.F10, true, true, false);
                awaitField("Ctrl+Alt+F10", () -> {
                    log("输入 Ctrl+Alt+F10 后 字段 = " + text(app, "hotkeyField")
                            + " | 状态 = " + text(app, "hotkeyStatus"));
                    log("实际绑定 = " + text(app, "boundSpec"));

                    // 2. Try a combination this machine refuses (owned by another app).
                    pressInField(KeyCode.R, true, true, false);
                    awaitFieldSettled(() -> {
                        log("输入 Ctrl+Alt+R 后 状态 = " + text(app, "hotkeyStatus")
                                + " | 绑定仍是 = " + text(app, "boundSpec"));

                        dialog.close();

                        after(2, () -> {
                            log("关闭弹窗后 字段 = " + text(app, "hotkeyField")
                                    + " | 底部提示 = " + text(app, "keyHint"));

                            HotkeySpec bound = (HotkeySpec) field(app, "boundSpec", HotkeySpec.class);
                            if (bound == null) {
                                log("没有绑定，无法继续");
                                Platform.exit();
                                return;
                            }
                            boolean typed = typeCombo(bound);
                            log("已发送 " + bound.label() + " = " + typed);

                            after(3, () -> {
                                log("按键后 录制中 = " + isRecording());
                                typeCombo(bound);
                                after(5, () -> {
                                    log("再按一次 录制中 = " + isRecording());
                                    Platform.exit();
                                });
                            });
                        });
                    });
                });
            });
        });
    }

    /**
     * Waits until the capture field shows the given combination.
     * <p>
     * Polling for the actual outcome instead of sleeping for a fixed time: the
     * registration round trip is measured in seconds on a busy machine and in
     * milliseconds on an idle one, and a fixed sleep is either flaky or slow.
     * <p>
     * The wait is on the <em>bound</em> combination, not on the field text. The field
     * echoes the key press immediately, so waiting for it would return before the
     * system has answered — and the probe would report a binding that had not happened.
     */
    private void awaitField(String expected, Runnable then) {
        awaitUntil(() -> expected.equals(text(app, "boundSpec")), then, 10,
                "绑定没有变成 " + expected);
    }

    /** Waits until the status line stops showing the "in effect" wording. */
    private void awaitFieldSettled(Runnable then) {
        awaitUntil(() -> !text(app, "hotkeyStatus").startsWith("当前生效"), then, 10,
                "状态行没有变成失败提示");
    }

    private void awaitUntil(java.util.function.BooleanSupplier condition, Runnable then,
                            int maxSeconds, String failureMessage) {
        if (condition.getAsBoolean()) {
            then.run();
            return;
        }
        if (maxSeconds <= 0) {
            log("等待超时：" + failureMessage);
            then.run();
            return;
        }
        after(1, () -> awaitUntil(condition, then, maxSeconds - 1, failureMessage));
    }

    private void pressInField(KeyCode code, boolean ctrl, boolean alt, boolean shift) {
        TextField field = (TextField) field(app, "hotkeyField", TextField.class);
        if (field == null) {
            log("找不到快捷键输入框");
            return;
        }
        // Fired at the node itself: the field is the only thing that listens, and this
        // avoids depending on which window happens to hold focus in a headless run.
        field.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, alt, false));
    }

    private boolean typeCombo(HotkeySpec spec) {
        try {
            java.awt.Robot robot = new java.awt.Robot();
            // Robot wants physical key codes for the modifiers, not the _DOWN_MASK flags.
            java.util.List<Integer> mods = new java.util.ArrayList<>();
            if (spec.mods().contains(HotkeySpec.Mod.CTRL)) {
                mods.add(java.awt.event.KeyEvent.VK_CONTROL);
            }
            if (spec.mods().contains(HotkeySpec.Mod.ALT)) {
                mods.add(java.awt.event.KeyEvent.VK_ALT);
            }
            if (spec.mods().contains(HotkeySpec.Mod.SHIFT)) {
                mods.add(java.awt.event.KeyEvent.VK_SHIFT);
            }
            int key;
            if (spec.key().startsWith("F") && spec.key().length() > 1) {
                key = java.awt.event.KeyEvent.VK_F1 + Integer.parseInt(spec.key().substring(1)) - 1;
            } else {
                key = java.awt.event.KeyEvent.getExtendedKeyCodeForChar(spec.key().charAt(0));
            }
            mods.forEach(robot::keyPress);
            robot.keyPress(key);
            robot.keyRelease(key);
            for (int i = mods.size() - 1; i >= 0; i--) {
                robot.keyRelease(mods.get(i));
            }
            return true;
        } catch (Throwable t) {
            log("无法模拟按键：" + t);
            return false;
        }
    }

    private boolean isRecording() {
        Object recorder = field(app, "recorder", Object.class);
        if (recorder == null) {
            return false;
        }
        try {
            Method m = recorder.getClass().getMethod("isRecording");
            return (Boolean) m.invoke(recorder);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private Dialog<?> dialog() {
        try {
            Field f = JRecApp.class.getDeclaredField("settingsDialog");
            f.setAccessible(true);
            return (Dialog<?>) f.get(app);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            Field f = JRecApp.class.getDeclaredField(name);
            f.setAccessible(true);
            return type.cast(f.get(target));
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static String text(Object target, String name) {
        try {
            Field f = JRecApp.class.getDeclaredField(name);
            f.setAccessible(true);
            Object value = f.get(target);
            if (value instanceof Label label) {
                return label.getText();
            }
            if (value instanceof TextField tf) {
                return tf.getText();
            }
            return String.valueOf(value);
        } catch (ReflectiveOperationException e) {
            return "<无>";
        }
    }

    private static void after(double seconds, Runnable action) {
        PauseTransition pause = new PauseTransition(Duration.seconds(seconds));
        pause.setOnFinished(e -> {
            try {
                action.run();
            } catch (Throwable t) {
                log("步骤失败：" + t);
                Platform.exit();
            }
        });
        pause.play();
    }

    private static void log(String message) {
        System.out.println("[probe] " + message);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
