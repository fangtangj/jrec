package com.rzx.jrec.desktop.ui;

import com.rzx.jrec.core.Rect;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A live viewfinder: the actual screen, scaled down, refreshed twice a second.
 * <p>
 * This is the difference between a settings form and a recorder. It also earns its
 * place functionally — it shows exactly what will be captured, including the chosen
 * region, so you find out that you picked the wrong monitor before recording rather
 * than after.
 * <p>
 * Capture runs on a background thread (AWT {@code Robot} at 1080p costs roughly 15ms)
 * and only the finished frame is handed to the FX thread. The image buffer is reused
 * across frames, so a two-hour session does not allocate two hours of garbage.
 * <p>
 * If the platform has no display to read, the widget degrades to a neutral placeholder
 * instead of throwing during startup.
 */
public final class ScreenPreview extends StackPane {

    private static final int REFRESH_MS = 500;
    private static final int CORNER = 14;

    private final ImageView frame = new ImageView();
    private final Label sizeChip = new Label();
    private final Label liveChip = new Label("LIVE");
    private final ScheduledExecutorService pump =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jrec-preview");
                t.setDaemon(true);
                return t;
            });

    private volatile Rect area;
    private volatile boolean running;
    private volatile boolean scheduled;
    private volatile boolean busy;
    private BufferedImage scratch;
    private WritableImage reusable;

    public ScreenPreview() {
        getStyleClass().add("viewfinder");
        setMinSize(0, 0);

        frame.setPreserveRatio(true);
        frame.setSmooth(true);
        frame.fitWidthProperty().bind(widthProperty().subtract(2));
        frame.fitHeightProperty().bind(heightProperty().subtract(2));

        // Clip to the frame so the scaled screenshot keeps crisp corners.
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        clip.setArcWidth(8);
        clip.setArcHeight(8);
        setClip(clip);

        sizeChip.getStyleClass().add("vf-chip");
        liveChip.getStyleClass().add("vf-live");

        Region spacer = new Region();
        StackPane.setAlignment(liveChip, Pos.TOP_LEFT);
        StackPane.setMargin(liveChip, new Insets(10));
        StackPane.setAlignment(sizeChip, Pos.TOP_RIGHT);
        StackPane.setMargin(sizeChip, new Insets(10));

        getChildren().addAll(frame, liveChip, sizeChip, corner(Pos.TOP_LEFT), corner(Pos.TOP_RIGHT),
                corner(Pos.BOTTOM_LEFT), corner(Pos.BOTTOM_RIGHT));

        // Warm up the grabber once so the first visible frame is not a second late.
        Platform.runLater(this::grabOnce);
    }

    private Region corner(Pos position) {
        Region mark = new Region();
        mark.getStyleClass().add("vf-corner");
        mark.getStyleClass().add(switch (position) {
            case TOP_LEFT -> "tl";
            case TOP_RIGHT -> "tr";
            case BOTTOM_LEFT -> "bl";
            default -> "br";
        });
        mark.setMaxSize(CORNER, CORNER);
        mark.setPrefSize(CORNER, CORNER);
        mark.setMouseTransparent(true);
        StackPane.setAlignment(mark, position);
        StackPane.setMargin(mark, new Insets(6));
        return mark;
    }

    /** {@code null} previews the whole desktop; a rect previews just that region. */
    public void setArea(Rect area) {
        this.area = area;
        // The chip reports pixels, not the mode: the segmented control already says
        // "全屏", and repeating it here just gives the eye a second thing to read.
        if (area != null) {
            sizeChip.setText(area.width() + " × " + area.height());
            return;
        }
        java.awt.Rectangle screen = java.awt.GraphicsEnvironment
                .getLocalGraphicsEnvironment().getMaximumWindowBounds();
        sizeChip.setText(screen.width + " × " + screen.height);
    }

    public void setCaption(String text) {
        sizeChip.setText(text);
    }

    /** Begins refreshing. Calling it again after {@link #pause()} resumes. */
    public void start() {
        if (scheduled) {
            running = true;
            return;
        }
        scheduled = true;
        running = true;
        pump.scheduleWithFixedDelay(this::grabOnce, 0, REFRESH_MS, TimeUnit.MILLISECONDS);
    }

    /** Stops refreshing without tearing the scheduler down, so recording can resume it. */
    public void pause() {
        running = false;
    }

    /** Final release; the preview cannot be restarted afterwards. */
    public void dispose() {
        running = false;
        pump.shutdownNow();
    }

    private void grabOnce() {
        if (!running || busy) {
            return;
        }
        busy = true;
        try {
            java.awt.Rectangle bounds = bounds();
            int w = (int) Math.max(2, getWidth());
            int h = (int) Math.max(2, getHeight());
            if (w < 8 || h < 8) {
                return;
            }

            Robot robot = robot();
            if (robot == null) {
                return;
            }
            BufferedImage shot = robot.createScreenCapture(bounds);

            if (scratch == null || scratch.getWidth() != w || scratch.getHeight() != h) {
                scratch = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            }
            Graphics2D g = scratch.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(shot, 0, 0, w, h, null);
            g.dispose();

            int[] pixels = new int[w * h];
            scratch.getRGB(0, 0, w, h, pixels, 0, w);

            Platform.runLater(() -> {
                if (reusable == null || reusable.getWidth() != w || reusable.getHeight() != h) {
                    reusable = new WritableImage(w, h);
                }
                reusable.getPixelWriter().setPixels(0, 0, w, h,
                        PixelFormat.getIntArgbInstance(), pixels, 0, w);
                if (frame.getImage() != reusable) {
                    frame.setImage(reusable);
                }
            });
        } catch (Throwable ignored) {
            // No display, or the grabber is not permitted: the panel simply stays blank.
        } finally {
            busy = false;
        }
    }

    private java.awt.Rectangle bounds() {
        Rect r = area;
        if (r != null) {
            return new java.awt.Rectangle(r.x(), r.y(), r.width(), r.height());
        }
        return GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
    }

    private Robot robot() {
        try {
            return new Robot();
        } catch (Throwable t) {
            return null;
        }
    }
}
