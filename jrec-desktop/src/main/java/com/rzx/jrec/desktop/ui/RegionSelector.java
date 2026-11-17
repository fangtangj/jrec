package com.rzx.jrec.desktop.ui;

import com.rzx.jrec.core.Rect;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.Optional;

/**
 * A click-drag region picker drawn over the whole virtual desktop.
 * <p>
 * The overlay is one transparent, borderless window sized to the union of every
 * screen, so a selection may span monitors. Dimensions are reported in
 * <em>physical</em> pixels because FFmpeg's capture backends work in device pixels,
 * while JavaFX reports logical ones; the conversion uses the output scale of the
 * screen the selection sits on.
 * <p>
 * Known limit: a setup mixing monitors with different scale factors will mis-crop,
 * because a single scale is applied to the whole rectangle.
 */
public final class RegionSelector {

    private static final double MIN_SIZE = 16;
    private static final Color DIM = Color.rgb(13, 12, 9, 0.62);
    private static final Color ACCENT = Color.web("#E2400F");

    private double startX;
    private double startY;
    private double currentX;
    private double currentY;
    private boolean dragging;
    private Rect result;

    private final Canvas canvas;
    private final Stage stage;

    private RegionSelector(Window owner) {
        Bounds2D virtual = virtualBounds();
        canvas = new Canvas(virtual.width, virtual.height);
        Pane root = new Pane(canvas);
        root.setStyle("-fx-background-color: transparent;");

        Scene scene = new Scene(root, virtual.width, virtual.height);
        scene.setFill(Color.TRANSPARENT);

        stage = new Stage(StageStyle.TRANSPARENT);
        stage.initOwner(owner);
        stage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        stage.setAlwaysOnTop(true);
        stage.setX(virtual.minX);
        stage.setY(virtual.minY);
        stage.setTitle("选择录制区域");
        stage.setScene(scene);

        canvas.setOnMousePressed(e -> {
            dragging = true;
            startX = e.getX();
            startY = e.getY();
            currentX = startX;
            currentY = startY;
            draw();
        });
        canvas.setOnMouseDragged(e -> {
            currentX = e.getX();
            currentY = e.getY();
            draw();
        });
        canvas.setOnMouseReleased(e -> {
            currentX = e.getX();
            currentY = e.getY();
            dragging = false;
            finish(virtual);
        });
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                stage.close();
            }
        });

        // Without an explicit focus request the overlay opens with no focused node,
        // and the ESC handler never fires — the user would be trapped in the picker.
        root.setFocusTraversable(true);
        stage.setOnShown(e -> root.requestFocus());

        draw();
    }

    /**
     * Opens the picker and blocks until the user finishes or cancels.
     *
     * @return the chosen region in physical pixels, or empty when cancelled
     */
    public static Optional<Rect> pick(Window owner) {
        RegionSelector selector = new RegionSelector(owner);
        selector.stage.showAndWait();
        return Optional.ofNullable(selector.result);
    }

    private void finish(Bounds2D virtual) {
        double x = Math.min(startX, currentX);
        double y = Math.min(startY, currentY);
        double w = Math.abs(currentX - startX);
        double h = Math.abs(currentY - startY);

        if (w < MIN_SIZE || h < MIN_SIZE) {
            stage.close();
            return;
        }

        // Canvas origin sits at the virtual desktop's top-left, which may be negative
        // when a monitor is placed to the left of the primary one.
        double logicalX = virtual.minX + x;
        double logicalY = virtual.minY + y;
        double scale = scaleAt(logicalX, logicalY);

        result = new Rect(
                (int) Math.round(logicalX * scale),
                (int) Math.round(logicalY * scale),
                (int) Math.round(w * scale),
                (int) Math.round(h * scale));
        stage.close();
    }

    private void draw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();

        g.clearRect(0, 0, w, h);
        g.setFill(DIM);
        g.fillRect(0, 0, w, h);

        if (dragging) {
            double x = Math.min(startX, currentX);
            double y = Math.min(startY, currentY);
            double sw = Math.abs(currentX - startX);
            double sh = Math.abs(currentY - startY);

            // Punch a hole so the user sees the real pixels they are about to record.
            g.clearRect(x, y, sw, sh);

            g.setStroke(ACCENT);
            g.setLineWidth(1.5);
            g.strokeRect(x + 0.5, y + 0.5, sw, sh);

            g.setFill(ACCENT);
            double corner = 3;
            g.fillRect(x - corner, y - corner, corner * 2, corner * 2);
            g.fillRect(x + sw - corner, y - corner, corner * 2, corner * 2);
            g.fillRect(x - corner, y + sh - corner, corner * 2, corner * 2);
            g.fillRect(x + sw - corner, y + sh - corner, corner * 2, corner * 2);

            String label = (int) sw + " × " + (int) sh;
            Font font = Font.font("Segoe UI", FontWeight.SEMI_BOLD, 12);
            g.setFont(font);
            g.setTextAlign(TextAlignment.LEFT);

            // The label sits on top of whatever is behind the overlay, which on a bright
            // desktop is not far from white. A dark plate makes it readable everywhere
            // instead of only over dark windows.
            Text measure = new Text(label);
            measure.setFont(font);
            double textWidth = measure.getLayoutBounds().getWidth();
            double labelY = y > 30 ? y - 10 : y + sh + 22;
            double plateX = x + 1;
            double plateY = labelY - 13;

            g.setFill(Color.rgb(18, 16, 12, 0.86));
            g.fillRoundRect(plateX, plateY, textWidth + 14, 19, 4, 4);
            g.setFill(Color.WHITE);
            g.fillText(label, plateX + 7, labelY);
        } else {
            g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 18));
            g.setFill(Color.rgb(255, 255, 255, 0.92));
            g.setTextAlign(TextAlignment.CENTER);
            g.fillText("拖拽选择录制区域", w / 2, h / 2 - 10);
            g.setFont(Font.font("Segoe UI", 13));
            g.setFill(Color.rgb(180, 188, 208, 0.9));
            g.fillText("松开鼠标即确定 · 按 ESC 取消", w / 2, h / 2 + 18);
        }
    }

    private static double scaleAt(double x, double y) {
        for (Screen screen : Screen.getScreens()) {
            if (screen.getBounds().contains(x, y)) {
                return screen.getOutputScaleX();
            }
        }
        return Screen.getPrimary().getOutputScaleX();
    }

    private record Bounds2D(double minX, double minY, double width, double height) {
    }

    private static Bounds2D virtualBounds() {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Screen screen : Screen.getScreens()) {
            var b = screen.getBounds();
            minX = Math.min(minX, b.getMinX());
            minY = Math.min(minY, b.getMinY());
            maxX = Math.max(maxX, b.getMaxX());
            maxY = Math.max(maxY, b.getMaxY());
        }
        return new Bounds2D(minX, minY, maxX - minX, maxY - minY);
    }
}
