package com.rzx.jrec.desktop.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.PauseTransition;
import javafx.animation.RotateTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ToggleGroup;
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

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

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

    /**
     * The three size presets, smallest first.
     * <p>
     * The right-click menu and the settings sheet both offer them, so the numbers live
     * here rather than in each place: two lists that agree today are two lists that
     * drift apart after the next change.
     */
    public static final List<Double> SIZE_PRESETS = List.of(160.0, DEFAULT_HEIGHT, 272.0);

    private static final double DRAG_SLOP = 4;

    /** Click vs double click. Long enough to catch the second press, short enough to feel. */
    private static final Duration DOUBLE_CLICK_WINDOW = Duration.millis(240);

    private final Stage stage = new Stage(StageStyle.TRANSPARENT);
    private final ImageView body = new ImageView();
    private final Label bubble = new Label();
    private final Label badge = new Label("REC");
    private final StackPane art = new StackPane(body, badge);
    private final StackPane lift = new StackPane(art);
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
    private PauseTransition blinkTimer;
    private PauseTransition hopTimer;
    /** False once the life loop is stopped; orphaned timers check it before rescheduling. */
    private boolean lifeLoopRunning;
    private Image openEyes;
    private Image closedEyes;
    private DoubleConsumer sizeChosen = px -> { };

    private double pressScreenX;
    private double pressScreenY;
    private double pressStageX;
    private double pressStageY;
    private boolean dragging;

    public PetWindow() {
        openEyes = new Image(getClass().getResourceAsStream("/com/rzx/jrec/desktop/pet.png"));
        // A second frame with the eyes shut, drawn from the same art so the blink does not
        // need a second expression. Missing file simply means no blinking.
        try (java.io.InputStream blink = getClass().getResourceAsStream("/com/rzx/jrec/desktop/pet-blink.png")) {
            closedEyes = blink == null ? null : new Image(blink);
        } catch (java.io.IOException ignored) {
            closedEyes = null;
        }
        body.setImage(openEyes);
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

        lift.setPickOnBounds(false);
        lift.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        VBox root = new VBox(4, bubble, lift);
        root.setAlignment(Pos.BOTTOM_CENTER);
        root.setPadding(new Insets(8, 10, 12, 10));
        root.setPickOnBounds(false);
        root.getStyleClass().add("pet-root");

        MenuItem mainItem = new MenuItem("显示主窗口");
        mainItem.setOnAction(e -> onOpenMain.run());
        MenuItem latestItem = new MenuItem("打开最近录制");
        latestItem.setOnAction(e -> onOpenLatest.run());
        MenuItem folderItem = new MenuItem("打开录制文件夹");
        folderItem.setOnAction(e -> onOpenFolder.run());
        MenuItem hideItem = new MenuItem("隐藏桌宠");
        hideItem.setOnAction(e -> onHide.run());
        MenuItem quitItem = new MenuItem("退出 JRec");
        quitItem.setOnAction(e -> onQuit.run());

        // Named items, not indices: adding the size submenu silently moved every handler
        // below it one slot, which is exactly the kind of bug a list index invites.
        menu.getItems().addAll(toggleItem, mainItem, latestItem, folderItem,
                sizeMenu(), new SeparatorMenuItem(), hideItem, quitItem);
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
            if (e.getButton() != MouseButton.PRIMARY) {
                return;
            }
            if (dragging) {
                // Dropping the pet against an edge should leave it flush, not three pixels
                // off. Snapping after the drag (not during) keeps the pointer authoritative
                // while it moves.
                snapToEdges();
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
        startLifeLoop();
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

    // ------------------------------------------------------------------ interaction

    private record SizeOption(String label, double pixels) {
    }

    /** Size presets, right where the user already is instead of three clicks into settings. */
    private Menu sizeMenu() {
        Menu size = new Menu("大小");
        ToggleGroup group = new ToggleGroup();
        for (double pixels : SIZE_PRESETS) {
            RadioMenuItem item = new RadioMenuItem(sizeLabel(pixels));
            item.setToggleGroup(group);
            item.setUserData(pixels);
            item.setOnAction(e -> {
                setArtHeight(pixels);
                sizeChosen.accept(pixels);
            });
            size.getItems().add(item);
        }
        size.setOnShowing(e -> group.getToggles().forEach(t -> {
            double pixels = (double) ((RadioMenuItem) t).getUserData();
            t.setSelected(Math.abs(pixels - currentHeight()) < 1);
        }));
        return size;
    }

    /** The label the size presets are shown under, in both the menu and the settings. */
    public static String sizeLabel(double pixels) {
        if (Math.abs(pixels - DEFAULT_HEIGHT) < 1) {
            return "中";
        }
        if (pixels < DEFAULT_HEIGHT) {
            return "小";
        }
        return "大";
    }

    public void setSizeHandler(DoubleConsumer handler) {
        this.sizeChosen = handler;
    }

    public double currentHeight() {
        return body.getFitHeight();
    }

    /**
     * Pulls the pet flush against whichever screen edge it was dropped near. A mascot left
     * three pixels off the edge looks like a mistake rather than a position.
     * <p>
     * The screen is the one the pet is currently on, not always the primary one: a user
     * who works on a second monitor and drops the pet there expects it to snap to that
     * monitor's edge, not to jump across to another one.
     */
    private void snapToEdges() {
        Rectangle2D bounds = screenUnderPet();
        double x = stage.getX();
        double y = stage.getY();
        double w = stage.getWidth();
        double h = stage.getHeight();
        double tolerance = 28;

        if (Math.abs(x - bounds.getMinX()) < tolerance) {
            x = bounds.getMinX();
        } else if (Math.abs(x + w - bounds.getMaxX()) < tolerance) {
            x = bounds.getMaxX() - w;
        }
        if (Math.abs(y - bounds.getMinY()) < tolerance) {
            y = bounds.getMinY();
        } else if (Math.abs(y + h - bounds.getMaxY()) < tolerance) {
            y = bounds.getMaxY() - h;
        }
        if (x != stage.getX() || y != stage.getY()) {
            slideTo(x, y);
        }
    }

    /**
     * The screen the pet should live on.
     * <p>
     * A position on a known screen picks that screen. A position on none of them — the
     * monitor was unplugged, or there is no remembered position — falls back to the
     * primary screen.
     */
    private Rectangle2D screenFor(double x, double y) {
        if (!Double.isNaN(x) && !Double.isNaN(y)) {
            for (Screen screen : Screen.getScreens()) {
                Rectangle2D bounds = screen.getVisualBounds();
                if (bounds.contains(x, y)) {
                    return bounds;
                }
            }
        }
        return Screen.getPrimary().getVisualBounds();
    }

    /**
     * The visual bounds of the screen the pet is on.
     * <p>
     * Falls back to the primary screen when the pet sits outside every known screen —
     * a position that can happen when a monitor is unplugged.
     */
    private Rectangle2D screenUnderPet() {
        double centerX = stage.getX() + stage.getWidth() / 2;
        double centerY = stage.getY() + stage.getHeight() / 2;
        for (Screen screen : Screen.getScreens()) {
            Rectangle2D bounds = screen.getVisualBounds();
            if (bounds.contains(centerX, centerY)) {
                return bounds;
            }
        }
        return Screen.getPrimary().getVisualBounds();
    }

    private void slideTo(double targetX, double targetY) {
        double fromX = stage.getX();
        double fromY = stage.getY();
        Timeline slide = new Timeline();
        int frames = 8;
        for (int i = 1; i <= frames; i++) {
            double t = i / (double) frames;
            double e = 1 - Math.pow(1 - t, 3);
            slide.getKeyFrames().add(new KeyFrame(Duration.millis(130 * t), ev -> {
                stage.setX(fromX + (targetX - fromX) * e);
                stage.setY(fromY + (targetY - fromY) * e);
            }));
        }
        slide.play();
    }

    // ------------------------------------------------------------------ idle life

    /**
     * Blinks and occasional hops, on independent timers with random gaps. The point is that
     * the pet never falls into a visible rhythm: anything exactly periodic stops reading as
     * alive within about a minute.
     * <p>
     * Each timer reschedules itself when it fires, so a chain that is started twice would
     * keep two sets of timers alive with only one of them tracked by a field. The
     * {@code lifeLoopRunning} flag is what makes that impossible: a timer that fires after
     * the loop was stopped reschedules nothing, and the orphaned chain ends with itself.
     */
    private void startLifeLoop() {
        stopLifeLoop();
        lifeLoopRunning = true;
        scheduleBlink();
        scheduleHop();
    }

    private void stopLifeLoop() {
        lifeLoopRunning = false;
        if (blinkTimer != null) {
            blinkTimer.stop();
            blinkTimer = null;
        }
        if (hopTimer != null) {
            hopTimer.stop();
            hopTimer = null;
        }
        if (closedEyes != null) {
            body.setImage(openEyes);
        }
    }

    private void scheduleBlink() {
        PauseTransition timer = new PauseTransition(
                Duration.millis(ThreadLocalRandom.current().nextInt(2800, 7200)));
        timer.setOnFinished(e -> {
            if (!lifeLoopRunning) {
                return;
            }
            blink();
            scheduleBlink();
        });
        blinkTimer = timer;
        timer.play();
    }

    private void blink() {
        if (closedEyes == null || !stage.isShowing()) {
            return;
        }
        body.setImage(closedEyes);
        PauseTransition shut = new PauseTransition(Duration.millis(115));
        shut.setOnFinished(e -> body.setImage(openEyes));
        shut.play();
    }

    private void scheduleHop() {
        PauseTransition timer = new PauseTransition(
                Duration.millis(ThreadLocalRandom.current().nextInt(9000, 17000)));
        timer.setOnFinished(e -> {
            if (!lifeLoopRunning) {
                return;
            }
            hop();
            scheduleHop();
        });
        hopTimer = timer;
        timer.play();
    }

    /**
     * A small hop, played on a separate node from the float. Two animations writing
     * translateY on the same node fight each other and produce a stutter.
     */
    private void hop() {
        if (!stage.isShowing()) {
            return;
        }
        TranslateTransition jump = new TranslateTransition(Duration.millis(150), lift);
        jump.setByY(-16);
        jump.setInterpolator(Interpolator.EASE_OUT);
        jump.setAutoReverse(true);
        jump.setCycleCount(2);
        jump.play();
    }

    // ------------------------------------------------------------------ stage

    /**
     * Shows the pet at a remembered spot, clamped to the screen it lands on. A saved
     * position can easily point off-screen after a monitor change, and a mascot you cannot
     * reach is a bug report.
     * <p>
     * The screen is chosen by where the saved position points, not by "the primary
     * screen": a pet that lives on the second monitor must not be dragged back to the
     * first one every time it is shown.
     */
    public void show(double x, double y) {
        stage.show();
        double width = stage.getWidth() > 0 ? stage.getWidth() : DEFAULT_HEIGHT;
        double height = stage.getHeight() > 0 ? stage.getHeight() : DEFAULT_HEIGHT;
        double px = Double.isNaN(x) ? Double.NaN : x;
        double py = Double.isNaN(y) ? Double.NaN : y;

        Rectangle2D bounds = screenFor(px, py);
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

        startLifeLoop();

        FadeTransition fade = new FadeTransition(Duration.millis(180), stage.getScene().getRoot());
        fade.setFromValue(0.2);
        fade.setToValue(1);
        fade.play();
    }

    /** Puts the pet back in the corner it starts in. */
    public void resetPosition() {
        // show() with NaN positions is what "back to the corner" means; assigning NaN to
        // the stage first would only put a NaN into the window for a moment.
        show(Double.NaN, Double.NaN);
    }

    public void hide() {
        hideBubble();
        stopMotion();
        stopLifeLoop();
        stage.hide();
    }

    public void close() {
        stopMotion();
        // The timers reschedule themselves, so closing the window without stopping them
        // leaves them running for the rest of the process's life.
        stopLifeLoop();
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
        Rectangle2D bounds = screenUnderPet();
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
