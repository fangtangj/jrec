package com.rzx.jrec.desktop.dev;

import com.rzx.jrec.desktop.JRecApp;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.SnapshotParameters;
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

/**
 * Developer utility: opens the settings sheet and captures it.
 * <p>
 * The settings sheet is a separate window, so the main-window snapshot tool cannot
 * see it. It is also the only surface a user can open that is not visible at launch,
 * which makes it exactly the kind of thing that breaks without anyone noticing.
 * <p>
 * Not part of the product.
 */
public class UiSettingsProbe extends Application {

    private static final String DOC_OUTPUT_PATH = "C:\\Users\\Public\\Videos\\JRec";

    @Override
    public void start(Stage stage) {
        JRecApp app = new JRecApp();
        try {
            app.start(stage);
        } catch (Exception e) {
            throw new IllegalStateException("无法启动主界面", e);
        }

        PauseTransition settle = new PauseTransition(Duration.seconds(4));
        settle.setOnFinished(e -> {
            try {
                Field field = JRecApp.class.getDeclaredField("settingsDialog");
                field.setAccessible(true);
                javafx.scene.control.Dialog<?> dialog = (javafx.scene.control.Dialog<?>) field.get(app);
                dialog.show();

                // This image goes into a public repository, and the real save directory
                // carries the machine's account name. Show a neutral path instead.
                Field dir = JRecApp.class.getDeclaredField("dirField");
                dir.setAccessible(true);
                ((TextField) dir.get(app)).setText(DOC_OUTPUT_PATH);

                PauseTransition wait = new PauseTransition(Duration.seconds(2));
                wait.setOnFinished(x -> {
                    snapshot(dialog, "docs/screenshot-settings.png");
                    dialog.close();
                    Platform.exit();
                });
                wait.play();
            } catch (ReflectiveOperationException ex) {
                System.out.println("[probe] 打不开设置弹窗：" + ex);
                Platform.exit();
            }
        });
        settle.play();
    }

    private void snapshot(javafx.scene.control.Dialog<?> dialog, String path) {
        try {
            WritableImage image = dialog.getDialogPane()
                    .snapshot(new SnapshotParameters(), null);
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
            System.out.println("[probe] 设置弹窗 " + w + "x" + h + " -> " + path);
        } catch (IOException e) {
            System.out.println("[probe] 快照失败：" + e.getMessage());
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
