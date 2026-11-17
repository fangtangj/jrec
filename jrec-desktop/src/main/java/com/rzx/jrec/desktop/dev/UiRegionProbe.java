package com.rzx.jrec.desktop.dev;

import com.rzx.jrec.core.Rect;
import com.rzx.jrec.desktop.ui.RegionSelector;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Optional;

/**
 * Developer utility: opens the region picker and drives it with synthetic mouse
 * events, then reports the rectangle it produced.
 * <p>
 * The engine's region cropping is covered by live tests, but the overlay itself —
 * does it appear, does it receive the drag, does the DPI conversion land on the
 * right pixels — can only be exercised by actually performing a drag. This does that
 * headlessly, and snapshots the overlay mid-drag for documentation.
 * <p>
 * Not part of the product.
 */
public class UiRegionProbe extends Application {

    private Stage owner;

    @Override
    public void start(Stage stage) {
        owner = stage;
        Label hint = new Label("region probe owner");
        StackPane root = new StackPane(hint);
        owner.setScene(new Scene(root, 320, 120));
        owner.setTitle("region probe");
        owner.show();

        // The injection has to be armed before pick() blocks in its nested event loop.
        Thread injector = new Thread(() -> {
            sleep(1200);
            Platform.runLater(this::driveOverlay);
        }, "region-probe-injector");
        injector.setDaemon(true);
        injector.start();

        Platform.runLater(() -> {
            Optional<Rect> picked = RegionSelector.pick(owner);
            System.out.println("[probe] 框选结果 = " + picked.map(Object::toString).orElse("<取消>"));
            picked.ifPresent(r -> {
                if (r.width() < 16 || r.height() < 16) {
                    System.out.println("[probe] 警告：区域过小");
                }
            });
            owner.close();
            Platform.exit();
        });
    }

    private void driveOverlay() {
        Optional<Window> overlay = Window.getWindows().stream()
                .filter(Window::isShowing)
                .filter(w -> w != owner)
                .findFirst();
        if (overlay.isEmpty()) {
            System.out.println("[probe] 没找到遮罩窗口");
            return;
        }

        Scene scene = overlay.get().getScene();
        if (!(scene.getRoot() instanceof Pane pane) || pane.getChildren().isEmpty()) {
            System.out.println("[probe] 遮罩窗口结构异常：" + scene.getRoot());
            return;
        }
        Canvas canvas = (Canvas) pane.getChildren().get(0);

        fire(canvas, MouseEvent.MOUSE_PRESSED, 200, 150);
        fire(canvas, MouseEvent.MOUSE_DRAGGED, 900, 600);
        // Snapshot while the selection is live: this is the state users see.
        snapshot(scene, "docs/screenshot-region.png");
        fire(canvas, MouseEvent.MOUSE_RELEASED, 900, 600);
    }

    private static void fire(Canvas canvas, javafx.event.EventType<MouseEvent> type, double x, double y) {
        MouseEvent event = new MouseEvent(
                type, x, y, x, y,
                MouseButton.PRIMARY, 1,
                false, false, false, false,
                true, false, false,
                false, false, false,
                null);
        javafx.event.Event.fireEvent(canvas, event);
    }

    private static void snapshot(Scene scene, String path) {
        try {
            WritableImage image = scene.snapshot(null);
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
            System.out.println("[probe] 快照 " + path + " (" + w + "x" + h + ")");
        } catch (IOException e) {
            System.out.println("[probe] 快照失败：" + e.getMessage());
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
