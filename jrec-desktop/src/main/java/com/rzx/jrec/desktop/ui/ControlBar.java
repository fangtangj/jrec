package com.rzx.jrec.desktop.ui;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/**
 * The strip that stays on screen while a recording runs.
 * <p>
 * Two decisions worth spelling out:
 * <ul>
 *   <li><b>No owner window.</b> An owned stage is hidden together with its owner, and
 *       the owner is deliberately minimised during recording — an owned bar would
 *       vanish exactly when it is needed.</li>
 *   <li><b>Draggable anywhere.</b> The bar lands in the recording when you capture the
 *       full screen. Making it movable means the user can shove it onto a corner of a
 *       second monitor, and the hide button gets it out of the frame entirely while the
 *       shortcut keeps working.</li>
 * </ul>
 */
public final class ControlBar {

    private final Stage stage = new Stage();
    private final Label clock = new Label("00:00");
    private final Circle dot = new Circle(4, Color.web("#FF5A22"));
    private final FadeTransition blink;
    private final Button hideButton = new Button("×");

    private Runnable onStop;
    private double grabX;
    private double grabY;

    public ControlBar() {
        // Transparent + undecorated: the bar is the shape in the stylesheet, not a window.
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setTitle("JRec 录制中");
        stage.setResizable(false);

        clock.getStyleClass().add("bar-time");

        Button stopButton = new Button();
        stopButton.getStyleClass().add("bar-stop");
        Region square = new Region();
        square.getStyleClass().add("bar-stop-mark");
        stopButton.setGraphic(square);
        // The button is a square with no text on it, so without a name it is invisible
        // to a screen reader — and to anyone who cannot tell a stop square from a dot.
        stopButton.setAccessibleText("停止录制");
        stopButton.setTooltip(new Tooltip("停止录制"));
        stopButton.setOnAction(e -> {
            if (onStop != null) {
                onStop.run();
            }
        });

        hideButton.getStyleClass().add("bar-close");
        hideButton.setAccessibleText("隐藏控制条");
        hideButton.setTooltip(new Tooltip("隐藏控制条"));
        hideButton.setOnAction(e -> hide());

        HBox row = new HBox(9, dot, clock, stopButton, hideButton);
        row.setAlignment(Pos.CENTER_LEFT);

        StackPane root = new StackPane(row);
        root.getStyleClass().add("bar-root");
        installDrag(root);

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(
                getClass().getResource("/com/rzx/jrec/desktop/style.css").toExternalForm());
        stage.setScene(scene);

        blink = new FadeTransition(Duration.seconds(0.9), dot);
        blink.setFromValue(1.0);
        blink.setToValue(0.5);
        blink.setCycleCount(Animation.INDEFINITE);
        blink.setAutoReverse(true);
    }

    /** Anywhere on the bar except the buttons drags the window. */
    private void installDrag(StackPane root) {
        root.setOnMousePressed(e -> {
            grabX = stage.getX() - e.getScreenX();
            grabY = stage.getY() - e.getScreenY();
        });
        root.setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() + grabX);
            stage.setY(e.getScreenY() + grabY);
        });
    }

    public void showFor(Runnable onStop) {
        this.onStop = onStop;
        if (!stage.isShowing()) {
            stage.show();
            Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
            stage.setX(bounds.getMinX() + (bounds.getWidth() - stage.getWidth()) / 2);
            stage.setY(bounds.getMinY() + 22);
        }
        blink.play();
    }

    public void hide() {
        blink.stop();
        stage.hide();
    }

    public boolean isShowing() {
        return stage.isShowing();
    }

    /** Called once per second from the recording clock. */
    public void setTime(long seconds) {
        clock.setText(com.rzx.jrec.core.MediaProbe.formatDuration(seconds));
    }

    public Stage stage() {
        return stage;
    }
}
