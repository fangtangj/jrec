package com.rzx.jrec.desktop.ui;

import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.util.function.BooleanSupplier;

/**
 * The mascot: a transparent, always-on-top window holding the character, which can start
 * and stop a recording without the main window being on screen.
 * <p>
 * Deliberately a separate stage rather than a node inside the main window. A pet that
 * disappears when the app is minimised is just a picture; the point of this one is that it
 * stays reachable while you work. For the same reason it has no owner — an owned stage is
 * hidden together with its owner.
 * <p>
 * It is hidden automatically while a take is running, because {@code gdigrab} has no way to
 * exclude a window from a full-screen capture and a mascot in the corner of every recording
 * would be worse than useless. The floating control bar takes over for stopping.
 */
public final class PetWindow {

    /** Rendered height. The source art is 512 px tall, so this keeps it crisp on HiDPI. */
    private static final double PET_HEIGHT = 210;
    private static final double DRAG_SLOP = 4;

    private final Stage stage = new Stage(StageStyle.TRANSPARENT);
    private final ImageView body = new ImageView();
    private final Label bubble = new Label();
    private final ContextMenu menu = new ContextMenu();
    private final MenuItem toggleItem = new MenuItem("开始录制");

    private Runnable onToggle = () -> { };
    private Runnable onOpenMain = () -> { };
    private Runnable onOpenLatest = () -> { };
    private Runnable onOpenFolder = () -> { };
    private Runnable onHide = () -> { };
    private Runnable onQuit = () -> { };
    private BooleanSupplier recording = () -> false;

    private double pressScreenX;
    private double pressScreenY;
    private double pressStageX;
    private double pressStageY;
    private boolean dragging;

    public PetWindow() {
        Image art = new Image(getClass().getResourceAsStream("/com/rzx/jrec/desktop/pet.png"));
        body.setImage(art);
        body.setFitHeight(PET_HEIGHT);
        body.setPreserveRatio(true);
        body.setSmooth(true);
        // Only the drawn pixels take clicks. With rectangular picking the transparent
        // padding around the character becomes an invisible click target sitting on top of
        // whatever the user is working on.
        body.setPickOnBounds(false);
        // The shadow follows the image's alpha, so it outlines the character rather than
        // sitting behind a rectangle. Without it the pet looks pasted onto the wallpaper.
        // No drop shadow, deliberately. A DropShadow on a large scaled ImageView inside a
        // transparent stage leaves a persistent white tile over the artwork on Windows —
        // reproduced on every capture, and gone the moment the effect is removed. The
        // character has its own dark outline and reads fine on a wallpaper without one.

        bubble.getStyleClass().add("pet-bubble");
        bubble.setVisible(false);
        // Stays managed on purpose. A Stage is sized from its scene when it is shown, so a
        // bubble that only joins the layout on hover gets clipped into a sliver and pushes
        // the character out of the window. Reserving its space up front costs a strip of
        // transparent pixels and makes the reveal a pure visibility flip.
        bubble.setMouseTransparent(true);

        VBox root = new VBox(4, bubble, body);
        root.setAlignment(Pos.BOTTOM_CENTER);
        root.setPadding(new Insets(8, 10, 12, 10));
        root.setPickOnBounds(false);
        root.getStyleClass().add("pet-root");

        menu.getItems().addAll(
                toggleItem,
                new MenuItem("显示主窗口"),
                new MenuItem("打开最近录制"),
                new MenuItem("打开录制文件夹"),
                new SeparatorMenuItem(),
                new MenuItem("隐藏桌宠"),
                new MenuItem("退出 JRec"));
        menu.getItems().get(1).setOnAction(e -> onOpenMain.run());
        menu.getItems().get(2).setOnAction(e -> onOpenLatest.run());
        menu.getItems().get(3).setOnAction(e -> onOpenFolder.run());
        menu.getItems().get(5).setOnAction(e -> onHide.run());
        menu.getItems().get(6).setOnAction(e -> onQuit.run());
        // The first entry flips between the two actions every time it is opened.
        menu.setOnShowing(e -> toggleItem.setText(recording.getAsBoolean() ? "停止录制" : "开始录制"));
        toggleItem.setOnAction(e -> onToggle.run());

        root.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) {
                return;
            }
            pressScreenX = e.getScreenX();
            pressScreenY = e.getScreenY();
            pressStageX = stage.getX();
            pressStageY = stage.getY();
            dragging = false;
        });
        root.setOnMouseDragged(e -> {
            if (e.getButton() != MouseButton.PRIMARY) {
                return;
            }
            double dx = e.getScreenX() - pressScreenX;
            double dy = e.getScreenY() - pressScreenY;
            if (!dragging && Math.hypot(dx, dy) < DRAG_SLOP) {
                return;
            }
            dragging = true;
            stage.setX(pressStageX + dx);
            stage.setY(pressStageY + dy);
        });
        root.setOnMouseReleased(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                menu.show(stage, e.getScreenX(), e.getScreenY());
                return;
            }
            // A drag that happens to end over the pet must not also fire the action.
            if (e.getButton() == MouseButton.PRIMARY && !dragging) {
                onToggle.run();
            }
        });
        root.setOnMouseEntered(e -> showBubble());
        root.setOnMouseExited(e -> hideBubble());

        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setResizable(false);
        stage.setTitle("JRec 桌宠");
        stage.setScene(new javafx.scene.Scene(root));
        stage.getScene().setFill(Color.TRANSPARENT);
        stage.getScene().getStylesheets().add(
                getClass().getResource("/com/rzx/jrec/desktop/style.css").toExternalForm());
    }

    public void setOnToggle(Runnable action) {
        this.onToggle = action;
    }

    public void setOnOpenMain(Runnable action) {
        this.onOpenMain = action;
    }

    public void setOnOpenLatest(Runnable action) {
        this.onOpenLatest = action;
    }

    public void setOnOpenFolder(Runnable action) {
        this.onOpenFolder = action;
    }

    public void setOnHide(Runnable action) {
        this.onHide = action;
    }

    public void setOnQuit(Runnable action) {
        this.onQuit = action;
    }

    public void setRecordingCheck(BooleanSupplier check) {
        this.recording = check;
    }

    /** The line shown in the bubble on hover, so the pet says what it will do. */
    public void setHint(String text) {
        bubble.setText(text);
    }

    public boolean isShowing() {
        return stage.isShowing();
    }

    public double x() {
        return stage.getX();
    }

    public double y() {
        return stage.getY();
    }

    /**
     * Shows the pet at a remembered spot, clamped to the screen it lands on. A saved
     * position can easily point off-screen after a monitor change, and a mascot you cannot
     * reach is a bug report.
     */
    public void show(double x, double y) {
        stage.show();
        double width = stage.getWidth() > 0 ? stage.getWidth() : PET_HEIGHT;
        double height = stage.getHeight() > 0 ? stage.getHeight() : PET_HEIGHT;
        double px = Double.isNaN(x) ? Double.NaN : x;
        double py = Double.isNaN(y) ? Double.NaN : y;

        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        if (Double.isNaN(px) || Double.isNaN(py)) {
            px = bounds.getMaxX() - width - 24;
            py = bounds.getMaxY() - height - 24;
        }
        px = Math.max(bounds.getMinX(), Math.min(px, bounds.getMaxX() - width));
        py = Math.max(bounds.getMinY(), Math.min(py, bounds.getMaxY() - height));
        stage.setX(px);
        stage.setY(py);

        // A layered window sometimes keeps a stale tile from its first composition until
        // something forces a re-layout, which shows up as a light square over the artwork for
        // a moment. Asking for a fresh layout on the next pulse clears it.
        javafx.application.Platform.runLater(stage::sizeToScene);

        FadeTransition fade = new FadeTransition(Duration.millis(180), stage.getScene().getRoot());
        fade.setFromValue(0.2);
        fade.setToValue(1);
        fade.play();
    }

    public void hide() {
        hideBubble();
        stage.hide();
    }

    public void close() {
        stage.close();
    }

    private void showBubble() {
        if (bubble.getText() == null || bubble.getText().isBlank()) {
            return;
        }
        bubble.setVisible(true);
    }

    private void hideBubble() {
        bubble.setVisible(false);
    }
}
