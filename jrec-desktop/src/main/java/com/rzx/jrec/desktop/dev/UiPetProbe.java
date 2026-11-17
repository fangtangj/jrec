package com.rzx.jrec.desktop.dev;

import com.rzx.jrec.desktop.JRecApp;
import com.rzx.jrec.desktop.ui.PetWindow;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.SnapshotParameters;
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
 * Developer utility: captures the desktop pet on a transparent background.
 * <p>
 * The pet is its own stage, so the main-window snapshot tool cannot see it. Snapshotting
 * the scene with a transparent fill is also the only way to check the matte: on the real
 * desktop a leftover white box would be obvious, in a screenshot on white it would not.
 * <p>
 * Not part of the product.
 */
public class UiPetProbe extends Application {

    @Override
    public void start(Stage stage) {
        JRecApp app = new JRecApp();
        try {
            app.start(stage);
        } catch (Exception e) {
            throw new IllegalStateException("无法启动主界面", e);
        }

        after(4, () -> {
            PetWindow pet = field(app, "pet", PetWindow.class);
            if (pet == null) {
                System.out.println("[probe] 没有桌宠");
                Platform.exit();
                return;
            }
            // A previous run may have hidden it; the snapshot needs it on screen.
            try {
                Method show = JRecApp.class.getDeclaredMethod("setPetVisible", boolean.class);
                show.setAccessible(true);
                show.invoke(app, true);
            } catch (ReflectiveOperationException e) {
                System.out.println("[probe] 无法显示桌宠：" + e);
            }

            after(1, () -> {
                Stage petStage = field(pet, "stage", Stage.class);
                // Scene.snapshot takes a WritableImage, not SnapshotParameters; the scene's
                // fill is already transparent, so the alpha survives.
                WritableImage image = petStage.getScene().snapshot(null);
                write(image, "docs/screenshot-pet.png");
                Platform.exit();
            });
        });
    }

    private void write(WritableImage image, String path) {
        int w = (int) Math.round(image.getWidth());
        int h = (int) Math.round(image.getHeight());
        BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                buffered.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        try {
            File out = new File(path);
            File parent = out.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            ImageIO.write(buffered, "png", out);
            System.out.println("[probe] 桌宠快照 " + w + "x" + h + " -> " + path);
        } catch (IOException e) {
            System.out.println("[probe] 写入失败：" + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return (T) f.get(target);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static void after(double seconds, Runnable action) {
        PauseTransition pause = new PauseTransition(Duration.seconds(seconds));
        pause.setOnFinished(e -> action.run());
        pause.play();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
