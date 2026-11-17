package com.rzx.jrec.desktop.dev;

import com.rzx.jrec.desktop.JRecApp;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Developer utility: renders the real window off-screen and writes it to a PNG.
 * <p>
 * Exists so documentation screenshots are reproducible on any machine instead of
 * being captured by hand from whatever the desktop happened to look like. Run with
 * {@code mvn -pl jrec-desktop javafx:run -Djavafx.mainClass=com.rzx.jrec.desktop.dev.UiSnapshot}
 * and an optional output path as the first argument.
 * <p>
 * Not part of the product: nothing in the app references this class.
 */
public class UiSnapshot extends Application {

    private static final Duration SETTLE = Duration.seconds(6);

    @Override
    public void start(Stage stage) {
        JRecApp app = new JRecApp();
        try {
            app.start(stage);
        } catch (Exception e) {
            throw new IllegalStateException("无法启动主界面", e);
        }

        // Device and encoder discovery are asynchronous; snapshot only once the
        // layout has stopped changing, otherwise the combos render empty.
        PauseTransition settle = new PauseTransition(SETTLE);
        settle.setOnFinished(e -> {
            try {
                write(stage);
            } catch (IOException ex) {
                ex.printStackTrace();
            } finally {
                Platform.exit();
            }
        });
        settle.play();
    }

    private void write(Stage stage) throws IOException {
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

        String path = getParameters().getRaw().isEmpty()
                ? "docs/screenshot-main.png"
                : getParameters().getRaw().get(0);
        File out = new File(path);
        File parent = out.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        ImageIO.write(buffered, "png", out);
        System.out.println("快照已写入 " + out.getAbsolutePath() + " (" + w + "x" + h + ")");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
