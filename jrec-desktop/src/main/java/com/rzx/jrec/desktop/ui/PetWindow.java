package com.rzx.jrec.desktop.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.PauseTransition;
import javafx.animation.RotateTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
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
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
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
 * Motion is part of the point. A completely still sprite reads as a pasted image; a slow
 * float makes it read as alive. Everything here is kept small on purpose (a few pixels over
 * several seconds) because the pet sits on top of whatever the user is actually doing.
 */
public final class PetWindow {

    /** Default rendered height. The source art is 512 px tall, so 210 stays crisp. */
    public static final double DEFAULT_HEIGHT = 210;
    private static final double DRAG_SLOP = 4;

    /** Click vs double click. Long enough to catch the second press, short enough to feel. */
    private static final Duration DOUBLE_CLICK_WINDOW = Duration.millis(240);

    private final Stage stage = new Stage(StageStyle.TRANSPARENT);
    private final ImageView body = new ImageView();
    private final Label bubble = new Label();
    private final Label badge = new Label("REC");
    private final StackPane art = new StackPane(body, badge);
    private final ContextMenu menu = new ContextMenu();
    private final MenuItem toggleItem = new MenuItem("开始录制");
    private final PauseTransition pendingClick = new PauseTransition(DOUBLE_CLICK_WINDOW);

    private Runnable onToggle = () -> { };
    private Runnable onOpenMain = () -> { };
    private Runnable onOpenLatest = () -> { };
    private Runnable onOpenFolder = () -> { };
    private Runnable onHide = () -> { };
    private Runnable onQuit = () -> { };
    private BooleanSupplier recording = () -> false;

    private TranslateTransition floatMotion;
    private RotateTransition swayMotion;
    private ScaleTransition hoverMotion;

    private double pressScreenX;
    private double pressScreenY;
    private double pressStageX;
    private double pressStageY;
    private boolean dragging;

    public PetWindow() {
        Image artImage = new Image(getClass().getResourceAsStream("/com/rzx/jrec/desktop/pet.png"));
        body.setImage(artImage);
        body.setFitHeight(DEFAULT_HEIGHT);
        body.setPreserveRatio(true);
        body.setSmooth(true);
        // Only the drawn pixels take clicks. With rectangular picking the transparent
        // padding around the character becomes an invisible click target sitting on top of
        // whatever the user is working on.
        body.setPickOnBounds(false);
        // No drop shadow, deliberately. A DropShadow on a large scaled ImageView inside a
        // transparent stage leaves a persistent white tile over the artwork on Windows —
        // reproduced on every capture, and gone the moment the effect is removed. The
        // character has its own dark outline and reads fine on a wallpaper without one.

        badge.getStyleClass().add("pet-rec");
        badge.setVisible(false);
        badge.setMouseTransparent(true);
        StackPane.setAlignment(badge, Pos.TOP_RIGHT);
        StackPane.setMargin(badge, new Insets(6, 6, 0, 0));

        art.setPickOnBounds(false);
        art.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        bubble.getStyleClass().add("pet-bubble");
        bubble.setVisible(false);
        // Stays managed on purpose. A Stage is sized from its scene when it is shown, so a
        // bubble that only joins the layout on hover gets clipped into a sliver and pushes
        // the character out of the window. Reserving its space up front costs a strip of
        // transparent pixels and makes the reveal a pure visibility flip.
        bubble.setMouseTransparent(true);

        VBox root = new VBox(4, bubble, art);
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
            pressDown();
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
            releaseUp();
            if (e.getButton() == MouseButton.SECONDARY) {
                menu.show(stage, e.getScreenX(), e.getScreenY());
                return;
            }
            // A drag that happens to end over the pet must not also fire the action.
            if (e.getButton() != MouseButton.PRIMARY || dragging) {
                return;
            }
            // A single click must not fire straight away, or every double click would also
            // toggle a recording before opening the file.
            if (e.getClickCount() >= 2) {
                pendingClick.stop();
                onOpenLatest.run();
            } else {
                pendingClick.playFromStart();
            }
        });
        pendingClick.setOnFinished(e -> onToggle.run());

        root.setOnMouseEntered(e -> {
            showBubble();
            hoverTo(1.06, 140);
        });
        root.setOnMouseExited(e -> {
            hideBubble();
            hoverTo(1.0, 220);
        });

        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setResizable(false);
        stage.setTitle("JRec 桌宠");
        stage.setScene(new javafx.scene.Scene(root));
        stage.getScene().setFill(Color.TRANSPARENT);
        stage.getScene().getStylesheets().add(
                getClass().getResource("/com/rzx/jrec/desktop/style.css").toExternalForm());

        startIdleMotion(false);
    }

    // ------------------------------------------------------------------ wiring

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

    // ------------------------------------------------------------------ appearance

    /** Height in pixels. The stage is re-fitted so the art is never clipped. */
    public void setArtHeight(double pixels) {
        body.setFitHeight(pixels);
        if (stage.isShowing()) {
            stage.sizeToScene();
            clampOnScreen();
        }
    }

    /** Keeps the bubble readable while the character fades into the desktop. */
    public void setArtOpacity(double opacity) {
        art.setOpacity(Math.max(0.3, Math.min(1, opacity)));
    }

    /**
     * The recording look: a red badge and a faster float. There is only one drawing, so the
     * state is carried by motion and a badge rather than by a second expression.
     */
    public void setRecordingLook(boolean active) {
        badge.setVisible(active);
        startIdleMotion(active);
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

    // ------------------------------------------------------------------ stage

    /**
     * Shows the pet at a remembered spot, clamped to the screen it lands on. A saved
     * position can easily point off-screen after a monitor change, and a mascot you cannot
     * reach is a bug report.
     */
    public void show(double x, double y) {
        stage.show();
        double width = stage.getWidth() > 0 ? stage.getWidth() : DEFAULT_HEIGHT;
        double height = stage.getHeight() > 0 ? stage.getHeight() : DEFAULT_HEIGHT;
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

    /** Puts the pet back in the corner it starts in. */
    public void resetPosition() {
        stage.setX(Double.NaN);
        show(Double.NaN, Double.NaN);
    }

    public void hide() {
        hideBubble();
        stopMotion();
        stage.hide();
    }

    public void close() {
        stopMotion();
        stage.close();
    }

    // ------------------------------------------------------------------ motion

    private void startIdleMotion(boolean urgent) {
        stopMotion();
        // A few pixels over several seconds: enough to look alive, not enough to pull the
        // eye away from whatever is behind it.
        Duration cycle = urgent ? Duration.millis(1100) : Duration.millis(3200);
        double lift = urgent ? 4 : 7;

        floatMotion = new TranslateTransition(cycle, art);
        floatMotion.setFromY(0);
        floatMotion.setToY(-lift);
        floatMotion.setInterpolator(Interpolator.EASE_BOTH);
        floatMotion.setCycleCount(TranslateTransition.INDEFINITE);
        floatMotion.setAutoReverse(true);
        floatMotion.play();

        swayMotion = new RotateTransition(cycle.multiply(2.4), art);
        swayMotion.setFromAngle(-1.1);
        swayMotion.setToAngle(1.1);
        swayMotion.setInterpolator(Interpolator.EASE_BOTH);
        swayMotion.setCycleCount(RotateTransition.INDEFINITE);
        swayMotion.setAutoReverse(true);
        swayMotion.play();
    }

    private void stopMotion() {
        if (floatMotion != null) {
            floatMotion.stop();
            floatMotion = null;
        }
        if (swayMotion != null) {
            swayMotion.stop();
            swayMotion = null;
        }
        art.setTranslateY(0);
        art.setRotate(0);
    }

    private void pressDown() {
        scaleTo(0.94, 90);
    }

    private void releaseUp() {
        scaleTo(1.0, 160);
    }

    private void hoverTo(double target, int millis) {
        scaleTo(target, millis);
    }

    private void scaleTo(double target, int millis) {
        if (hoverMotion != null) {
            hoverMotion.stop();
        }
        hoverMotion = new ScaleTransition(Duration.millis(millis), art);
        hoverMotion.setToX(target);
        hoverMotion.setToY(target);
        hoverMotion.setInterpolator(Interpolator.EASE_OUT);
        hoverMotion.play();
    }

    // ------------------------------------------------------------------ helpers

    private void clampOnScreen() {
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        double width = stage.getWidth();
        double height = stage.getHeight();
        stage.setX(Math.max(bounds.getMinX(), Math.min(stage.getX(), bounds.getMaxX() - width)));
        stage.setY(Math.max(bounds.getMinY(), Math.min(stage.getY(), bounds.getMaxY() - height)));
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
