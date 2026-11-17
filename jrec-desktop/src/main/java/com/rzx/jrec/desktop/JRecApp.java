package com.rzx.jrec.desktop;

import com.rzx.jrec.core.AudioDevice;
import com.rzx.jrec.core.AudioDevices;
import com.rzx.jrec.core.FfmpegDownloader;
import com.rzx.jrec.core.FfmpegLocator;
import com.rzx.jrec.core.MediaProbe;
import com.rzx.jrec.core.Recorder;
import com.rzx.jrec.core.RecorderException;
import com.rzx.jrec.core.RecorderListener;
import com.rzx.jrec.core.RecordingConfig;
import com.rzx.jrec.core.RecordingResult;
import com.rzx.jrec.core.Rect;
import com.rzx.jrec.core.RecorderState;
import com.rzx.jrec.desktop.ui.RegionSelector;
import com.rzx.jrec.desktop.ui.ControlBar;
import com.rzx.jrec.desktop.ui.PetWindow;
import com.rzx.jrec.desktop.hotkey.GlobalHotkey;
import com.rzx.jrec.desktop.hotkey.HotkeySpec;
import com.rzx.jrec.desktop.hotkey.Hotkeys;
import javafx.css.PseudoClass;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.prefs.Preferences;
import java.util.stream.Stream;

/**
 * Desktop client. One window, two states: configure, then record.
 * <p>
 * All FFmpeg work happens on background threads; every mutation of the scene graph
 * is funnelled through {@link Platform#runLater}. That split is the whole reason the
 * timer keeps ticking while the encoder is pinned at 100% CPU.
 */
public class JRecApp extends Application {

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final String PREFS_DIR = "outputDir";
    private static final String PREFS_QUALITY = "quality";
    private static final String PREFS_HOTKEY = "hotkey";
    private static final String PREFS_PET = "petVisible";
    private static final String PREFS_PET_X = "petX";
    private static final String PREFS_PET_Y = "petY";
    private static final String PREFS_PET_SIZE = "petSize";
    private static final String PREFS_PET_OPACITY = "petOpacity";
    private static final String PREFS_PET_STAY = "petStayWhileRecording";

    /** The pet can fade to 35% at most: below that it stops reading as a character. */
    private static final double MIN_OPACITY = 35;
    private static final double MAX_OPACITY = 100;

    private final Recorder recorder = new Recorder();
    private final Preferences prefs = Preferences.userNodeForPackage(JRecApp.class);

    private Stage stage;
    private StackPane root;
    private VBox configView;
    private VBox recordingView;

    private ToggleGroup modeGroup;
    private ToggleButton fullScreenToggle;
    private Label regionValue;
    private Button pickRegionButton;
    private ComboBox<AudioChoice> micCombo;
    private ComboBox<AudioChoice> systemCombo;
    private ComboBox<Integer> fpsCombo;
    private ComboBox<String> qualityCombo;
    private ComboBox<EncoderChoice> encoderCombo;
    private TextField dirField;
    private Button recordButton;
    private Label ffmpegChip;
    private Label systemAudioHintLabel;
    private Label deckSpec;
    private Label deckLabel;
    private PetWindow pet;
    private ToggleButton petToggle;
    private Label latestName;
    private Label latestMeta;
    private Button latestPlay;
    private Button latestReveal;
    private Path latestFile;
    private ComboBox<String> petSizeCombo;
    private Slider petOpacitySlider;
    private Label petOpacityValue;
    private CheckBox petStayCheck;
    private Dialog<Void> settingsDialog;
    private ToggleButton regionToggle;
    private Label historyWhere;
    private Label keyHint;
    private ControlBar controlBar;
    private GlobalHotkey hotkey;
    private TextField hotkeyField;
    private Label hotkeyStatus;
    /** What the user explicitly chose and persisted. Null means "no explicit choice". */
    private HotkeySpec hotkeySpec;
    /** What is registered with the system right now. May differ from {@link #hotkeySpec}. */
    private HotkeySpec boundSpec;
    private boolean hotkeyBound;
    /** Bumped for every registration attempt; a stale result is discarded. */
    private int hotkeyGeneration;
    /** The generation whose result was applied; equal to {@link #hotkeyGeneration} when idle. */
    private int hotkeyAppliedGeneration;
    /** True while the settings sheet is open and the shortcut must stay released. */
    private boolean settingsOpen;

    private Label timerLabel;
    private Label recordingFileLabel;
    private Label recStopHint;
    private Label recordingCaption;
    private Label toast;
    /** The transition that hides the toast; kept so a new toast can cancel the old one. */
    private PauseTransition toastHide;
    private VBox historyBox;
    private Circle recDot;
    private Timeline wallClock;
    private FadeTransition recBlink;
    private final List<Region> ticks = new ArrayList<>();
    private int litTicks = -1;

    private static final PseudoClass LIT = PseudoClass.getPseudoClass("lit");
    private long recordingStartedAt;
    /** True between asking the recorder to start and it actually entering RECORDING. */
    private boolean starting;

    private Rect region;

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        buildUi();

        Scene scene = new Scene(root, 660, 520);
        scene.getStylesheets().add(getClass().getResource("/com/rzx/jrec/desktop/style.css").toExternalForm());
        stage.setScene(scene);

        // A Dialog binds to its owner's scene at construction time, so this has to come
        // after setScene. Built once and reused, which keeps the controls' state.
        settingsDialog = buildSettingsDialog();

        refreshHistory();
        probeFfmpegAsync();
        stage.setTitle("JRec — 屏幕录制");
        stage.setMinWidth(620);
        stage.setMinHeight(470);
        stage.setOnCloseRequest(e -> {
            if (recorder.isRecording()) {
                e.consume();
                toast("正在录制，请先停止录制", true);
            }
        });
        try (java.io.InputStream icon = getClass().getResourceAsStream("/com/rzx/jrec/desktop/app-icon.png")) {
            if (icon != null) {
                stage.getIcons().add(new Image(icon));
            }
        } catch (java.io.IOException ignored) {
            // The icon is cosmetic; never block startup for it.
        }
        stage.show();
        startPet();

        // The first call loads and unpacks JNA's native library, which can take a few
        // hundred milliseconds. Doing that on the FX thread would stall the first
        // paint, so registration happens off-thread and only the result comes back.
        hotkeySpec = HotkeySpec.parse(prefs.get(PREFS_HOTKEY, ""));
        int generation = ++hotkeyGeneration;
        startDaemon("jrec-hotkey-init", () -> {
            GlobalHotkey created;
            boolean bound;
            try {
                created = Hotkeys.create();
                // Startup uses the fallback path: a combination that was free yesterday may
                // be owned by another program today, and a recorder with no working shortcut
                // is a recorder nobody uses.
                bound = created.registerWithFallback(hotkeySpec,
                        () -> Platform.runLater(this::toggleRecording));
            } catch (Throwable t) {
                hotkeyAppliedGeneration = generation;
                Platform.runLater(() -> showHotkeyMessage("快捷键注册失败：" + t));
                return;
            }
            Platform.runLater(() -> {
                if (generation != hotkeyGeneration) {
                    created.close();
                    return;
                }
                hotkeyAppliedGeneration = generation;
                adoptHotkey(created, bound);
            });
        });
    }

    /**
     * Starts a background thread that will not keep the JVM alive.
     * <p>
     * The shortcut registration waits for the operating system, and the JVM must not
     * wait for it: a hung registration would keep a closed application alive.
     */
    private static void startDaemon(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        // A recording left running would keep FFmpeg alive after the window closes.
        // Stopping it asks FFmpeg to finalise the file, which can take up to fifteen
        // seconds — far too long to hold the UI thread hostage while the app exits.
        //
        // The worker is deliberately NOT a daemon. A daemon thread would be killed the
        // moment the JVM decided to exit, taking the MP4's moov index with it and
        // leaving a file that no player can open. A normal thread keeps the JVM alive
        // until FFmpeg has written the index, and because this thread does not join,
        // the window still closes immediately.
        if (recorder.isRecording()) {
            Thread finalizer = new Thread(() -> recorder.stop(), "jrec-finalize");
            finalizer.start();
        }
        if (pet != null) {
            prefs.putDouble(PREFS_PET_X, pet.x());
            prefs.putDouble(PREFS_PET_Y, pet.y());
            pet.close();
        }
        if (controlBar != null) {
            controlBar.hide();
        }
        if (hotkey != null) {
            hotkey.close();
        }
    }

    // ------------------------------------------------------------------ layout

    private void buildUi() {
        root = new StackPane();
        root.getStyleClass().add("root");
        root.setPadding(new Insets(20, 28, 22, 28));

        // Built up front so a recording started before the settings sheet is ever
        // opened still reads real values instead of nulls.
        createControls();
        controlBar = new ControlBar();

        VBox history = historySection();
        VBox card = captureCard();

        configView = new VBox(0);
        configView.getChildren().addAll(
                header(),
                card,
                history
        );
        // Spare height goes to the list. A result plate stretched to fill is just a big
        // empty box with a file name floating in the middle of it.
        VBox.setVgrow(history, Priority.ALWAYS);
        updateRegionUi();

        recordingView = buildRecordingView();
        recordingView.setVisible(false);
        recordingView.setManaged(false);

        toast = new Label();
        toast.getStyleClass().add("toast");
        toast.setVisible(false);
        toast.setManaged(false);
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toast, new Insets(0, 0, 18, 0));
        toast.setMaxWidth(Region.USE_PREF_SIZE);

        root.getChildren().addAll(configView, recordingView, toast);
    }

    private VBox header() {
        // The mark echoes the product page: a signal dot, then the wordmark set in mono.
        Circle mark = new Circle(3.5, Color.web("#E2400F"));
        Label brand = new Label("JREC");
        brand.getStyleClass().add("brand");
        Label sep = new Label("/");
        sep.getStyleClass().add("brand-sep");
        Label tagline = new Label("屏幕录制");
        tagline.getStyleClass().add("tagline");

        HBox left = new HBox(9, mark, brand, sep, tagline);
        left.setAlignment(Pos.CENTER_LEFT);

        ffmpegChip = new Label("检测 FFmpeg…");
        ffmpegChip.getStyleClass().addAll("chip", "chip-warn");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        petToggle = new ToggleButton("桌宠");
        petToggle.getStyleClass().addAll("btn-ghost", "pet-toggle");
        petToggle.setOnAction(e -> setPetVisible(petToggle.isSelected()));

        Button settings = new Button("设置");
        settings.getStyleClass().add("btn-ghost");
        settings.setOnAction(e -> settingsDialog.showAndWait());

        HBox topRow = new HBox(8, left, spacer, ffmpegChip, petToggle, settings);
        topRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(topRow);
        box.getStyleClass().add("app-header");
        return box;
    }

    /**
     * The panel: what to capture, the newest take, and the button that starts.
     * <p>
     * The live preview is gone on purpose. A recorder spends almost all of its life idle,
     * and a window-sized mirror of the desktop is the least useful thing to put in that
     * space — it showed the app's own reflection four times over, ate a frame every half
     * second, and pushed the one thing people actually come back for (the last file) into a
     * 10px row at the bottom.
     */
    private VBox captureCard() {
        VBox card = new VBox(14, toolbarRow(), latestBlock(), deck());
        card.getStyleClass().add("capture-card");
        card.setMinHeight(0);
        return card;
    }

    /**
     * The newest recording, given the space the preview used to take.
     * <p>
     * Its file name is the largest text in the window because after a take the first
     * question is "where did it go, and what is it called". The two actions beside it are
     * the two things anyone does next: watch it, or open the folder it lives in.
     */
    private VBox latestBlock() {
        Label cap = new Label("最新录制");
        cap.getStyleClass().add("latest-cap");

        latestPlay = new Button("播放");
        latestPlay.getStyleClass().add("btn-solid");
        latestPlay.setDisable(true);
        latestPlay.setOnAction(e -> openLatest());

        latestReveal = new Button("打开文件夹");
        latestReveal.getStyleClass().add("btn-ghost");
        latestReveal.setDisable(true);
        latestReveal.setOnAction(e -> reveal(latestFile));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox head = new HBox(8, cap, spacer, latestPlay, latestReveal);
        head.setAlignment(Pos.CENTER_LEFT);

        latestName = new Label("还没有录制");
        latestName.getStyleClass().add("latest-name");

        latestMeta = new Label("按下面的按钮开始，或单击桌宠");
        latestMeta.getStyleClass().add("latest-meta");

        VBox box = new VBox(6, head, latestName, latestMeta);
        box.getStyleClass().add("latest");
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMaxHeight(Double.MAX_VALUE);
        return box;
    }

    /** What kind of capture, and what the next take will contain. */
    private HBox toolbarRow() {
        deckSpec = new Label();
        deckSpec.getStyleClass().add("deck-spec");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(12, modeSwitch(), spacer, deckSpec);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("capture-toolbar");
        return row;
    }

    private void createControls() {
        micCombo = audioCombo();
        systemCombo = audioCombo();

        fpsCombo = new ComboBox<>(FXCollections.observableArrayList(15, 24, 30, 60));
        fpsCombo.setValue(30);
        fpsCombo.getStyleClass().add("combo");

        qualityCombo = new ComboBox<>(FXCollections.observableArrayList(
                "高画质 · 大文件", "标准", "小体积"));
        qualityCombo.setValue(prefs.get(PREFS_QUALITY, "标准"));
        qualityCombo.getStyleClass().add("combo");

        encoderCombo = new ComboBox<>();
        encoderCombo.getStyleClass().add("combo");
        encoderCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(EncoderChoice item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.label());
            }
        });
        encoderCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(EncoderChoice item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.label());
            }
        });

        dirField = new TextField(defaultOutputDir().toString());
        dirField.getStyleClass().add("text-field");
        dirField.setMinWidth(0);
        HBox.setHgrow(dirField, Priority.ALWAYS);

        for (ComboBox<?> combo : List.of(micCombo, systemCombo, fpsCombo, qualityCombo, encoderCombo)) {
            // Without this a combo refuses to shrink and its row overflows.
            combo.setMinWidth(0);
            combo.setMaxWidth(Double.MAX_VALUE);
        }
    }

    /**
     * The whole window is a viewfinder with the transport bar lying on top of it.
     * <p>
     * The previous layout was a settings form: five labelled rows, eight controls,
     * nothing to look at. This one shows the screen you are about to capture, which
     * is both the thing a recorder should be and the thing the product page promised
     * when it said "nothing else".
     */
    private HBox modeSwitch() {
        modeGroup = new ToggleGroup();
        fullScreenToggle = new ToggleButton("全屏");
        fullScreenToggle.getStyleClass().add("pill");
        fullScreenToggle.setToggleGroup(modeGroup);
        fullScreenToggle.setSelected(true);

        regionToggle = new ToggleButton("自选区域");
        regionToggle.getStyleClass().add("pill");
        regionToggle.setToggleGroup(modeGroup);

        modeGroup.selectedToggleProperty().addListener((obs, old, sel) -> {
            if (sel == null) {
                // A toggle group allows deselecting; keep one always active.
                modeGroup.selectToggle(old);
                return;
            }
            boolean isRegion = sel == regionToggle;
            if (isRegion && region == null) {
                pickRegion();
                if (region == null) {
                    modeGroup.selectToggle(fullScreenToggle);
                    return;
                }
            }
            updateRegionUi();
        });

        HBox segment = new HBox(0, fullScreenToggle, regionToggle);
        segment.getStyleClass().add("segmented");
        segment.setAlignment(Pos.CENTER_LEFT);
        segment.setMaxHeight(Region.USE_PREF_SIZE);
        return segment;
    }

    /** The transport bar: one round key, a readout, and the way into settings. */
    private HBox deck() {
        recordButton = new Button();
        recordButton.getStyleClass().add("rec-circle");
        recordButton.setOnAction(e -> startRecording());

        // The label lives inside the button now, so the key reads as one object at any
        // size. A bare circle with text beside it made the circle look like a dial that
        // belonged to nothing.
        deckLabel = new Label("开始录制");
        deckLabel.getStyleClass().add("deck-label");
        HBox recordContent = new HBox(9, new Circle(4, Color.WHITE), deckLabel);
        recordContent.setAlignment(Pos.CENTER);
        recordButton.setGraphic(recordContent);

        pickRegionButton = new Button("重新框选");
        pickRegionButton.getStyleClass().add("btn-ghost");
        pickRegionButton.setOnAction(e -> {
            pickRegion();
            if (region != null) {
                modeGroup.selectToggle(regionToggle);
            }
        });
        pickRegionButton.setVisible(false);
        pickRegionButton.setManaged(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        keyHint = new Label();
        keyHint.getStyleClass().add("key-hint");
        keyHint.setVisible(false);
        keyHint.setManaged(false);

        HBox bar = new HBox(12, recordButton, keyHint, spacer, pickRegionButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("deck");
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setMaxHeight(Region.USE_PREF_SIZE);
        return bar;
    }

    /**
     * Every setting lives here rather than on the main screen. Built once and reused so
     * the controls keep their state and never have to be re-parented.
     */
    private Dialog<Void> buildSettingsDialog() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("录制设置");
        dialog.setResizable(true);

        VBox content = new VBox(0, soundSection(), qualitySection(), outputSection(),
                hotkeySection(), petSection());
        content.setPrefWidth(660);
        content.setPadding(new Insets(2, 6, 4, 6));

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().add("settings-pane");
        pane.setContent(content);
        pane.getButtonTypes().add(ButtonType.CLOSE);
        pane.getStylesheets().add(
                getClass().getResource("/com/rzx/jrec/desktop/style.css").toExternalForm());

        // The shortcut has to be let go while the sheet is open. Otherwise pressing a
        // combination into the capture field is intercepted by this very application, and
        // the user cannot type the key they are trying to assign.
        dialog.setOnShowing(e -> {
            settingsOpen = true;
            releaseHotkey();
        });

        dialog.setOnHidden(e -> {
            settingsOpen = false;
            prefs.put(PREFS_DIR, dirField.getText());
            prefs.put(PREFS_QUALITY, qualityCombo.getValue());
            // The sheet releases the shortcut while it is open, so it has to be taken
            // back afterwards — unless an assignment made inside the sheet already
            // installed one. "hotkey == null" is not the right test any more: an
            // assignment started inside the sheet may still be waiting for the system,
            // and binding again on top of it would leave two live registrations. The
            // generation counter says whether a request is still in flight.
            if (!hotkeyPending() && !hotkeyBound) {
                bindDesired();
            }
            refreshHistory();
            updateDeckSpec();
        });
        return dialog;
    }

    /** True while a registration started in this session has not reported back yet. */
    private boolean hotkeyPending() {
        return hotkeyGeneration != hotkeyAppliedGeneration;
    }

    private HBox soundSection() {
        GridPane grid = grid(2,
                field("麦克风", micCombo),
                field("系统声音", systemCombo));

        systemAudioHintLabel = new Label();
        systemAudioHintLabel.getStyleClass().add("warn-link");
        systemAudioHintLabel.setVisible(false);
        systemAudioHintLabel.setManaged(false);
        systemAudioHintLabel.setOnMouseClicked(e -> showSystemAudioHint());

        VBox body = new VBox(9, grid, systemAudioHintLabel);
        body.setMinWidth(0);
        return section("01", "声音", "AUDIO", body, false);
    }

    private HBox qualitySection() {
        GridPane grid = grid(3,
                field("帧率", fpsCombo),
                field("画质", qualityCombo),
                field("编码器", encoderCombo));
        grid.setMinWidth(0);
        return section("02", "画质", "ENCODE", grid, false);
    }

    private void showSystemAudioHint() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(stage);
        alert.setTitle("如何录制系统声音");
        alert.setHeaderText("没有找到可以录制系统播放声音的设备");
        TextArea area = new TextArea(recorder.backend().systemAudioHint());
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefRowCount(10);
        area.setPrefColumnCount(52);
        alert.getDialogPane().setContent(area);
        alert.showAndWait();
    }

    private HBox outputSection() {
        Button browse = new Button("浏览…");
        browse.getStyleClass().add("btn-ghost");
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择保存目录");
            Path current = outputDirOrNull();
            if (current != null && Files.isDirectory(current)) {
                chooser.setInitialDirectory(current.toFile());
            }
            File chosen = chooser.showDialog(stage);
            if (chosen != null) {
                dirField.setText(chosen.getAbsolutePath());
            }
        });

        Button openDir = new Button("打开");
        openDir.getStyleClass().add("btn-ghost");
        openDir.setOnAction(e -> {
            Path dir = outputDirOrNull();
            if (dir == null) {
                toast("保存目录无效，请重新选择", true);
                return;
            }
            openPath(dir);
        });

        HBox body = new HBox(9, dirField, browse, openDir);
        body.setAlignment(Pos.CENTER_LEFT);
        return section("03", "保存位置", "OUTPUT", body, false);
    }

    /**
     * Assigning a shortcut by pressing it, rather than by picking from a list of keys the
     * author happened to think of. The field is read-only on purpose: it is a recorder,
     * not a text box, so there is nothing to type and no caret to fight.
     */
    private HBox hotkeySection() {
        hotkeyField = new TextField();
        hotkeyField.getStyleClass().add("hotkey-field");
        hotkeyField.setEditable(false);
        hotkeyField.setPrefWidth(214);
        hotkeyField.setMinWidth(160);
        hotkeyField.setPromptText("点击后按下组合键");
        // The field shows what is actually registered, so it never claims a combination
        // that the system refused.
        hotkeyField.setText(boundSpec == null ? "" : boundSpec.label());
        hotkeyField.addEventFilter(KeyEvent.KEY_PRESSED, this::captureHotkeyKey);
        Button reset = new Button("恢复默认");
        reset.getStyleClass().add("btn-ghost");
        reset.setOnAction(e -> resetHotkey());

        Label tip = new Label("需搭配 Ctrl / Alt / Shift / Win，或单独用 F1–F12");
        tip.getStyleClass().add("field-note");

        HBox row = new HBox(9, hotkeyField, reset);
        row.setAlignment(Pos.CENTER_LEFT);

        hotkeyStatus = new Label();
        hotkeyStatus.getStyleClass().add("field-note");

        VBox body = new VBox(8, row, hotkeyStatus, tip);
        body.setMinWidth(0);
        return section("04", "快捷键", "HOTKEY", body, false);
    }

    private void captureHotkeyKey(KeyEvent event) {
        // Tab and Enter would otherwise move focus or close the dialog mid-assignment.
        event.consume();

        if (event.getCode() == KeyCode.ESCAPE) {
            // Escape means "never mind" — the field goes back to what is registered.
            hotkeyField.setText(boundSpec == null ? "" : boundSpec.label());
            showHotkeyMessage(null);
            return;
        }

        HotkeySpec candidate = Hotkeys.fromKeyEvent(event);
        if (candidate == null) {
            showHotkeyMessage("这个组合不能用：需要修饰键搭配，或单独用 F1–F12");
            return;
        }
        // The previous registration is still live here, so an identical combination would
        // collide with ourselves and report a bogus conflict. The comparison is against
        // what is actually registered, which is not always what was last chosen.
        if (candidate.equals(boundSpec) && hotkeyBound) {
            hotkeyField.setText(candidate.label());
            showHotkeyMessage(null);
            return;
        }
        assignHotkey(candidate);
    }

    private void assignHotkey(HotkeySpec candidate) {
        // Registering with the system blocks until the OS answers, and the answer can
        // take up to three seconds. Doing that on the FX thread freezes the window for
        // that long, so it happens on a worker and the result comes back to the UI.
        //
        // The generation counter guards against two registrations overlapping: the user
        // presses a combination and closes the settings sheet before the system has
        // answered, which would otherwise leave two live registrations and the loser
        // untracked.
        int generation = ++hotkeyGeneration;
        // Show what was pressed straight away. Registration can take a moment, and a
        // field that does not react would look like the key press was swallowed.
        hotkeyField.setText(candidate.label());
        startDaemon("jrec-hotkey-assign", () -> {
            GlobalHotkey created;
            boolean bound;
            try {
                created = Hotkeys.create();
                // The user pressed this one on purpose, so it is bound exactly or not at
                // all: quietly binding something else would leave them pressing a key
                // that does nothing.
                bound = created.register(candidate,
                        () -> Platform.runLater(this::toggleRecording));
            } catch (Throwable t) {
                // A failure here must still release the generation, or the application
                // would believe a registration is still in flight and never take the
                // shortcut back.
                hotkeyAppliedGeneration = generation;
                Platform.runLater(() -> {
                    hotkeyField.setText(boundSpec == null ? "" : boundSpec.label());
                    showHotkeyMessage("快捷键注册失败：" + t);
                });
                return;
            }
            Platform.runLater(() -> {
                if (generation != hotkeyGeneration) {
                    // A newer request came in while this one was in flight; this result
                    // is stale and must not replace the newer state.
                    created.close();
                    return;
                }
                hotkeyAppliedGeneration = generation;
                if (!bound) {
                    // Keep whatever was working before; a failed attempt must not leave the
                    // user with no shortcut at all. The field goes back to what is really
                    // in effect, so it never shows a combination that is not registered.
                    created.close();
                    hotkeyField.setText(boundSpec == null ? "" : boundSpec.label());
                    showHotkeyMessage(created.failure());
                    if (!settingsOpen) {
                        // The sheet was closed while this attempt was in flight, so it
                        // skipped taking the shortcut back. Without this the failed
                        // attempt would leave the application with no shortcut at all.
                        bindDesired();
                    }
                    return;
                }
                closeHotkey();
                hotkey = created;
                hotkeySpec = candidate;
                boundSpec = candidate;
                hotkeyBound = true;
                prefs.put(PREFS_HOTKEY, candidate.label());
                hotkeyField.setText(candidate.label());
                showHotkeyMessage(null);
                updateDeckHint();
                if (settingsOpen) {
                    // The sheet is still open. Keeping this registration alive would
                    // make the system swallow the very combination the user is trying to
                    // type into the field — and pressing the newly assigned shortcut
                    // would start a recording instead of reaching the field. The choice
                    // is recorded; the registration waits until the sheet closes.
                    hotkey.close();
                    hotkey = null;
                    hotkeyBound = false;
                    keyHint.setText("快捷键已暂停");
                }
            });
        });
    }

    private void resetHotkey() {
        closeHotkey();
        hotkeySpec = null;
        boundSpec = null;
        prefs.remove(PREFS_HOTKEY);
        if (settingsOpen) {
            // The sheet is open, so the shortcut must stay released. The default is
            // picked when the sheet closes; binding it now would swallow the keystrokes
            // the user types into the field.
            hotkeyAppliedGeneration = hotkeyGeneration;
            return;
        }
        bindDesired();
    }

    /** Binds whatever {@link #hotkeySpec} says, falling back to the built-in list when null. */
    private void bindDesired() {
        closeHotkey();
        // Off the FX thread: registration waits for the OS and can take seconds.
        // The generation bump invalidates any assignment still in flight.
        int generation = ++hotkeyGeneration;
        startDaemon("jrec-hotkey-bind", () -> {
            GlobalHotkey created;
            boolean bound;
            try {
                created = Hotkeys.create();
                bound = created.registerWithFallback(hotkeySpec,
                        () -> Platform.runLater(this::toggleRecording));
            } catch (Throwable t) {
                hotkeyAppliedGeneration = generation;
                Platform.runLater(() -> {
                    showHotkeyMessage("快捷键注册失败：" + t);
                    updateDeckHint();
                });
                return;
            }
            Platform.runLater(() -> {
                if (generation != hotkeyGeneration) {
                    created.close();
                    return;
                }
                hotkeyAppliedGeneration = generation;
                adoptHotkey(created, bound);
            });
        });
    }

    private void adoptHotkey(GlobalHotkey created, boolean bound) {
        // A registration that landed while the settings sheet was open must not take
        // effect: the sheet released the shortcut on purpose so the user can type the
        // combination they want, and putting it back would swallow their keystroke.
        if (dropIfSettingsOpen()) {
            created.close();
            return;
        }
        hotkey = created;
        hotkeyBound = bound;
        if (bound) {
            String actual = created.label();
            boundSpec = HotkeySpec.parse(actual);
            // The user's own choice wins; the fallback only fills in when it was refused.
            // This is what keeps "恢复默认" able to clear the preference: an automatically
            // picked combination is never written back, so the next start tries the
            // candidate list again instead of insisting on the one it happened to get.
            boolean userChose = hotkeySpec != null;
            if (hotkeyField != null) {
                hotkeyField.setText(boundSpec == null ? actual : boundSpec.label());
            }
            if (!userChose && boundSpec != null) {
                // Nothing was saved, so the app picked one from the candidate list. Say
                // which one, without inventing a reason: on a fresh install the first
                // candidate is usually free, and claiming it was "occupied" would be a
                // detail nobody asked for and nobody can check.
                toast("全局快捷键已绑定 " + boundSpec.label(), false);
            } else if (userChose && !hotkeySpec.label().equals(actual)) {
                // The saved combination was refused and a fallback got bound instead.
                // The label now shows a combination the user never chose — saying so is
                // the difference between an explained substitution and a silent one.
                toast("原组合 " + hotkeySpec.label() + " 已被其它程序占用，已改用 " + actual, false);
            }
            showHotkeyMessage(null);
        } else {
            showHotkeyMessage(created.failure());
            if (created.failure() != null) {
                toast("全局快捷键不可用：" + created.failure(), true);
            }
        }
        updateDeckHint();
    }

    private void closeHotkey() {
        if (hotkey != null) {
            hotkey.close();
            hotkey = null;
        }
        hotkeyBound = false;
    }

    /** Lets go of the registration without forgetting what was chosen. */
    private void releaseHotkey() {
        closeHotkey();
        if (keyHint != null) {
            keyHint.setText("快捷键已暂停");
        }
    }

    /**
     * Keeps a registration that finished while the settings sheet was open from taking
     * effect.
     * <p>
     * The sheet releases the shortcut so that the combination the user is trying to type
     * is not swallowed by this very application. A registration that was already in
     * flight when the sheet opened would arrive afterwards and put the shortcut back,
     * quietly breaking the very thing the release was for. The registration is dropped
     * instead, and the sheet takes it back when it closes.
     * <p>
     * Only the startup binding is dropped. An assignment the user made inside the sheet
     * is the whole point of the sheet and must survive; it is released separately, once
     * it has been recorded.
     *
     * @return true when the registration was dropped and must be closed
     */
    private boolean dropIfSettingsOpen() {
        if (!settingsOpen) {
            return false;
        }
        // The generation is bumped so the in-flight result is not applied later either.
        hotkeyGeneration++;
        hotkeyAppliedGeneration = hotkeyGeneration;
        return true;
    }

    private void showHotkeyMessage(String error) {
        if (hotkeyStatus == null) {
            return;
        }
        boolean bad = error != null;
        // The status line reports what is actually in effect, which is not always what
        // the user last chose: a saved combination can be taken by another program
        // between two runs, and the fallback binds a different one.
        String text;
        if (bad) {
            text = error;
        } else if (settingsOpen) {
            // The sheet holds the shortcut released on purpose. Saying "in effect" here
            // would be a lie: the combination is chosen, not active.
            text = "已选择：" + (boundSpec == null ? "默认（自动挑选空闲组合）" : boundSpec.label())
                    + " · 关闭本窗口后生效";
        } else {
            text = "当前生效：" + (boundSpec == null
                    ? "默认（自动挑选空闲组合）" : boundSpec.label());
        }
        hotkeyStatus.setText(text);
        hotkeyStatus.getStyleClass().setAll(bad ? "field-error" : "field-note");
    }

    private void updateDeckHint() {
        if (recStopHint != null) {
            recStopHint.setText(hotkeyBound && boundSpec != null
                    ? "或按 " + boundSpec.label() + " 停止"
                    : "");
        }
        if (keyHint == null) {
            return;
        }
        if (hotkeyBound && boundSpec != null) {
            keyHint.setText(boundSpec.label());
            keyHint.getStyleClass().setAll("key-hint");
        } else {
            keyHint.setText("快捷键不可用");
            keyHint.getStyleClass().setAll("key-hint", "key-hint-off");
        }
        keyHint.setVisible(true);
        keyHint.setManaged(true);
    }

    /**
     * How the mascot looks and behaves. It sits on top of other people's work, so its size
     * and how solid it is are the user's call, not an art direction decision.
     */
    private HBox petSection() {
        petSizeCombo = new ComboBox<>(FXCollections.observableArrayList("小", "中", "大"));
        petSizeCombo.setValue(PetWindow.sizeLabel(prefs.getDouble(PREFS_PET_SIZE, PetWindow.DEFAULT_HEIGHT)));
        petSizeCombo.getStyleClass().add("combo");
        petSizeCombo.setMaxWidth(Double.MAX_VALUE);
        petSizeCombo.valueProperty().addListener((obs, old, value) -> {
            if (value == null) {
                return;
            }
            double pixels = sizePixels(value);
            prefs.putDouble(PREFS_PET_SIZE, pixels);
            if (pet != null) {
                pet.setArtHeight(pixels);
            }
        });

        petOpacityValue = new Label();
        petOpacityValue.getStyleClass().add("field-note");

        // The slider clamps its value, so a stored value outside the range would be
        // shown at one number while the pet is actually at another. Clamp first and
        // report what will really be applied.
        double current = clampOpacity(prefs.getDouble(PREFS_PET_OPACITY, 1.0) * 100);
        petOpacitySlider = new Slider(MIN_OPACITY, MAX_OPACITY, current);
        petOpacityValue.setText(Math.round(current) + "%");
        petOpacitySlider.valueProperty().addListener((obs, old, value) -> {
            double percent = value.doubleValue();
            petOpacityValue.setText(Math.round(percent) + "%");
            prefs.putDouble(PREFS_PET_OPACITY, percent / 100.0);
            if (pet != null) {
                pet.setArtOpacity(percent / 100.0);
            }
        });
        HBox.setHgrow(petOpacitySlider, Priority.ALWAYS);
        HBox opacityRow = new HBox(8, petOpacitySlider, petOpacityValue);
        opacityRow.setAlignment(Pos.CENTER_LEFT);

        petStayCheck = new CheckBox("录制时留在画面上");
        petStayCheck.getStyleClass().add("check");
        petStayCheck.setSelected(prefs.getBoolean(PREFS_PET_STAY, false));
        petStayCheck.selectedProperty().addListener((obs, old, value) -> {
            prefs.putBoolean(PREFS_PET_STAY, value);
            // Unchecking mid-take should take effect now, not after the next recording.
            if (pet != null && !value && recorder.isRecording()) {
                pet.hide();
            }
        });

        Label stayNote = new Label("默认在录制时隐藏；留在画面上就会录进视频");
        stayNote.getStyleClass().add("field-note");

        Button corner = new Button("回到右下角");
        corner.getStyleClass().add("btn-ghost");
        corner.setOnAction(e -> {
            if (pet != null) {
                pet.resetPosition();
            }
        });

        GridPane grid = grid(2,
                field("大小", petSizeCombo),
                field("不透明度", opacityRow));

        VBox body = new VBox(9, grid, petStayCheck, stayNote, corner);
        body.setMinWidth(0);
        return section("05", "桌宠", "PET", body, false);
    }

    /**
     * Keeps an opacity percentage inside the range the slider offers.
     *
     * @return the percentage that will actually be applied
     */
    private static double clampOpacity(double percent) {
        if (Double.isNaN(percent)) {
            return MAX_OPACITY;
        }
        return Math.max(MIN_OPACITY, Math.min(MAX_OPACITY, percent));
    }

    /**
     * The pixel size behind a preset label.
     * <p>
     * The numbers live in {@link PetWindow} because the right-click menu offers the same
     * presets; two copies of the same list would drift apart on the next change.
     */
    private static double sizePixels(String label) {
        for (double pixels : PetWindow.SIZE_PRESETS) {
            if (PetWindow.sizeLabel(pixels).equals(label)) {
                return pixels;
            }
        }
        return PetWindow.DEFAULT_HEIGHT;
    }

    private VBox historySection() {
        Label cap = new Label("更早的记录");
        cap.getStyleClass().add("sec-cap");

        historyWhere = new Label();
        historyWhere.getStyleClass().add("file-meta");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox head = new HBox(10, cap, spacer, historyWhere);
        head.setAlignment(Pos.BASELINE_LEFT);

        historyBox = new VBox(0);
        ScrollPane scroll = new ScrollPane(historyBox);
        scroll.getStyleClass().add("history-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setMinHeight(0);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox box = new VBox(4, head, scroll);
        box.setMinHeight(72);
        box.setPadding(new Insets(18, 2, 0, 2));
        return box;
    }

    private VBox buildRecordingView() {
        recDot = new Circle(5, Color.web("#E2400F"));
        Label pill = new Label("REC");
        pill.getStyleClass().add("rec-pill-text");
        HBox pillRow = new HBox(7, recDot, pill);
        pillRow.getStyleClass().add("rec-pill");
        pillRow.setAlignment(Pos.CENTER);
        pillRow.setMaxWidth(Region.USE_PREF_SIZE);

        timerLabel = new Label("00:00");
        timerLabel.getStyleClass().add("timer");

        recordingCaption = new Label("全屏 · 30 fps · libx264");
        recordingCaption.getStyleClass().add("rec-spec");

        recordingFileLabel = new Label();
        recordingFileLabel.getStyleClass().add("rec-file");

        // A tape counter: one bar per second of the current half minute. It carries no
        // information the timer lacks, but it makes the panel feel like it is running.
        ticks.clear();
        HBox meter = new HBox(3);
        meter.setAlignment(Pos.CENTER);
        for (int i = 0; i < 30; i++) {
            Region tick = new Region();
            tick.getStyleClass().add("tick");
            ticks.add(tick);
            meter.getChildren().add(tick);
        }

        Button stop = new Button("停止录制");
        stop.getStyleClass().add("btn-stop");
        stop.setMaxWidth(Double.MAX_VALUE);
        stop.setOnAction(e -> stopRecording());

        recStopHint = new Label();
        recStopHint.getStyleClass().add("field-note");

        VBox card = new VBox(14, pillRow, timerLabel, meter, recordingCaption,
                recordingFileLabel, stop, recStopHint);
        card.getStyleClass().add("rec-card");
        card.setAlignment(Pos.CENTER);
        // A recorder's stop panel should not stretch across the whole window.
        card.setMaxWidth(400);

        VBox box = new VBox(card);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private VBox field(String label, javafx.scene.Node control) {
        Label l = new Label(label);
        l.getStyleClass().add("field-label");
        VBox box = new VBox(5, l, control);
        box.setMinWidth(0);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    /**
     * One numbered band of the control panel: a mono index in the left gutter, a
     * caption, then the body.
     * <p>
     * This replaces a stack of identical rounded cards. Three equal cards made every
     * setting look equally important and left the eye with no place to start; a
     * numbered column with hairline rules reads like the instrument manual the
     * product page borrows from, and gives the one action that matters — record —
     * the only solid block of colour on the screen.
     */
    private HBox section(String number, String title, String english,
                         javafx.scene.Node body, boolean grow) {
        Label num = new Label(number);
        num.getStyleClass().add("sec-num");

        Label cap = new Label(title);
        cap.getStyleClass().add("sec-cap");

        Label en = new Label(tracked(english));
        en.getStyleClass().add("sec-en");

        HBox head = new HBox(9, cap, en);
        head.setAlignment(Pos.BASELINE_LEFT);

        VBox col = new VBox(10, head, body);
        col.getStyleClass().add("sec-body");
        col.setMinWidth(0);
        HBox.setHgrow(col, Priority.ALWAYS);
        if (grow) {
            col.setMaxHeight(Double.MAX_VALUE);
            VBox.setVgrow(body, Priority.ALWAYS);
        }

        Region rail = new Region();
        rail.getStyleClass().add("rail");
        rail.setMaxHeight(Double.MAX_VALUE);

        HBox row = new HBox(0, num, rail, col);
        row.getStyleClass().add("sec");
        row.setAlignment(Pos.TOP_LEFT);
        if (grow) {
            row.setMaxHeight(Double.MAX_VALUE);
        }
        return row;
    }

    /**
     * JavaFX CSS has no {@code letter-spacing}. Thin spaces between latin glyphs give
     * short labels the tracked-out look of an instrument legend. Only for latin text —
     * CJK with thin spaces reads as broken.
     */
    private static String tracked(String text) {
        StringBuilder sb = new StringBuilder(text.length() * 2);
        for (int i = 0; i < text.length(); i++) {
            if (i > 0) {
                sb.append('\u2009');
            }
            sb.append(text.charAt(i));
        }
        return sb.toString();
    }

    /** Equal-width columns; percentages avoid the manual pixel maths of an HBox. */
    private GridPane grid(int columns, javafx.scene.Node... cells) {
        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(9);
        for (int i = 0; i < columns; i++) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setPercentWidth(100.0 / columns);
            constraints.setFillWidth(true);
            g.getColumnConstraints().add(constraints);
        }
        for (int i = 0; i < cells.length; i++) {
            g.add(cells[i], i % columns, i / columns);
        }
        return g;
    }

    // ------------------------------------------------------------------ actions

    private void pickRegion() {
        RegionSelector.pick(stage).ifPresentOrElse(r -> {
            region = r;
            updateRegionUi();
        }, () -> updateRegionUi());
    }

    private void updateRegionUi() {
        boolean useRegion = modeGroup.getSelectedToggle() != fullScreenToggle && region != null;
        if (pickRegionButton != null) {
            pickRegionButton.setVisible(useRegion);
            pickRegionButton.setManaged(useRegion);
        }
        updateDeckSpec();
    }

    /** Keeps the transport readout honest about what the next take will contain. */
    private void updateDeckSpec() {
        if (deckSpec == null || modeGroup == null) {
            return;
        }
        boolean useRegion = modeGroup.getSelectedToggle() != fullScreenToggle && region != null;
        String area = useRegion ? region.width() + " × " + region.height() : "全屏";
        Object fps = fpsCombo == null ? null : fpsCombo.getValue();
        // The readout has to name every source that will be recorded. Showing only
        // "麦克风" while the system sound is mixed in as well makes the line a lie about
        // the take that is about to happen.
        boolean hasMic = micCombo != null && micCombo.getValue() != null
                && micCombo.getValue().device() != null;
        boolean hasSystem = systemCombo != null && systemCombo.getValue() != null
                && systemCombo.getValue().device() != null;
        String audio;
        if (hasMic && hasSystem) {
            audio = "麦克风 + 系统声音";
        } else if (hasMic) {
            audio = "麦克风";
        } else if (hasSystem) {
            audio = "系统声音";
        } else {
            audio = "无声音";
        }
        deckSpec.setText(area + " · " + (fps == null ? 30 : fps) + " fps · " + audio);
    }

    /** What the global shortcut does: one key, either direction. */
    private void toggleRecording() {
        RecorderState state = recorder.state();
        if (state == RecorderState.RECORDING) {
            stopRecording();
        } else if (state == RecorderState.IDLE || state == RecorderState.FAILED) {
            startRecording();
        }
        // STARTING / STOPPING: a transition is already in flight; ignore the press.
    }

    private void startRecording() {
        // The recorder's own state machine refuses a second start, but it only reaches
        // that state once start() has run. Until then the button is still clickable, so
        // two fast clicks would spawn two threads and the second would fail with a
        // confusing "当前状态不能开始录制". The guard closes that window.
        if (starting) {
            return;
        }
        RecordingConfig config;
        try {
            config = buildConfig();
        } catch (RecorderException e) {
            toast(e.getMessage(), true);
            return;
        }

        starting = true;
        recordButton.setDisable(true);
        new Thread(() -> {
            try {
                recorder.start(config);
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    starting = false;
                    recordButton.setDisable(false);
                    toast(e.getMessage(), true);
                });
            }
        }, "jrec-start").start();
    }

    private void stopRecording() {
        recordingCaption.setText("正在保存…");
        new Thread(() -> recorder.stop(), "jrec-stop").start();
    }

    private RecordingConfig buildConfig() {
        Path dir = outputDir();
        String name = "JRec_" + LocalDateTime.now().format(FILE_STAMP) + ".mp4";
        Path output = dir.resolve(name);

        boolean useRegion = modeGroup.getSelectedToggle() != fullScreenToggle && region != null;

        AudioChoice mic = micCombo.getValue();
        AudioChoice sys = systemCombo.getValue();

        RecordingConfig.Builder builder = RecordingConfig.builder()
                .outputPath(output)
                .fps(fpsCombo.getValue())
                .crf(crfForQuality(qualityCombo.getValue()))
                .videoCodec(encoderCombo.getValue() == null ? "libx264" : encoderCombo.getValue().codec())
                .preset("veryfast");

        if (useRegion) {
            builder.region(region);
        } else {
            builder.mode(com.rzx.jrec.core.RecordingMode.FULL_SCREEN);
        }
        if (mic != null && mic.device() != null) {
            builder.microphone(mic.device());
        }
        if (sys != null && sys.device() != null) {
            builder.systemAudio(sys.device());
        }

        prefs.put(PREFS_QUALITY, qualityCombo.getValue());
        prefs.put(PREFS_DIR, dir.toString());
        return builder.build();
    }

    private static int crfForQuality(String quality) {
        if (quality == null) {
            return 23;
        }
        return switch (quality) {
            case "高画质 · 大文件" -> 18;
            case "小体积" -> 28;
            default -> 23;
        };
    }

    // ------------------------------------------------------------------ ffmpeg bootstrap

    private void probeFfmpegAsync() {
        CompletableFuture.supplyAsync(() -> {
            Optional<Path> ffmpeg = FfmpegLocator.find();
            return ffmpeg.map(FfmpegLocator::version).orElse(null);
        }).thenAccept(version -> Platform.runLater(() -> {
            if (version == null) {
                onFfmpegMissing();
            } else {
                ffmpegChip.setText("FFmpeg 就绪");
                ffmpegChip.getStyleClass().setAll("chip", "chip-ok");
                ffmpegChip.setTooltip(new Tooltip(version));
                loadOptionsAsync();
            }
        })).exceptionally(throwable -> {
            // Without this the chip would sit on "检测中…" forever: the future fails, and
            // nobody is told. The reason is worth more than a generic warning.
            Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
            Platform.runLater(() -> {
                ffmpegChip.setText("FFmpeg 异常");
                ffmpegChip.getStyleClass().setAll("chip", "chip-warn");
                ffmpegChip.setTooltip(new Tooltip("无法检测 FFmpeg：" + cause.getMessage()));
                recordButton.setDisable(true);
                deckLabel.setText("检测 FFmpeg 时出错：" + cause.getMessage());
            });
            return null;
        });
    }

    private void onFfmpegMissing() {
        ffmpegChip.setText("缺少 FFmpeg");
        ffmpegChip.getStyleClass().setAll("chip", "chip-warn");
        recordButton.setDisable(true);
        deckLabel.setText("下载 FFmpeg 后即可录制");
        recordButton.setOnAction(e -> downloadFfmpeg());
    }

    private void downloadFfmpeg() {
        recordButton.setDisable(true);
        deckLabel.setText("正在下载…");
        CompletableFuture.runAsync(() -> FfmpegDownloader.install(new FfmpegDownloader.ProgressListener() {
            @Override
            public void onProgress(long downloaded, long total) {
                String text = total > 0
                        ? String.format("正在下载 %.0f%%", downloaded * 100.0 / total)
                        : "正在下载…";
                Platform.runLater(() -> {
                    ffmpegChip.setText(text);
                    deckLabel.setText(text);
                });
            }

            @Override
            public void onStatus(String message) {
                Platform.runLater(() -> ffmpegChip.setText(message));
            }
        })).thenRun(() -> Platform.runLater(() -> {
            ffmpegChip.getStyleClass().setAll("chip", "chip-ok");
            ffmpegChip.setText("FFmpeg 就绪");
            recordButton.setDisable(false);
            deckLabel.setText("开始录制");
            recordButton.setOnAction(e -> startRecording());
            loadOptionsAsync();
            toast("FFmpeg 安装完成", false);
        })).exceptionally(t -> {
            Platform.runLater(() -> {
                recordButton.setDisable(false);
                deckLabel.setText("重试下载 FFmpeg");
                toast(t.getCause() == null ? t.getMessage() : t.getCause().getMessage(), true);
            });
            return null;
        });
    }

    /** Device and encoder discovery touches FFmpeg, so it runs off the UI thread. */
    private void loadOptionsAsync() {
        CompletableFuture.supplyAsync(() -> {
            List<AudioDevice> devices = AudioDevices.scan();
            List<String> encoders = FfmpegLocator.find()
                    .map(FfmpegLocator::availableH264Encoders)
                    .orElse(List.of("libx264"));
            return new Discovered(devices, encoders);
        }).thenAccept(found -> Platform.runLater(() -> {
            fillAudioCombo(micCombo, found.devices(), true);
            fillAudioCombo(systemCombo, found.devices(), false);
            List<EncoderChoice> choices = new ArrayList<>();
            for (String codec : found.encoders()) {
                choices.add(new EncoderChoice(codec, encoderLabel(codec)));
            }
            encoderCombo.setItems(FXCollections.observableArrayList(choices));
            if (!choices.isEmpty()) {
                encoderCombo.setValue(choices.get(0));
            }
            // The readout was first computed before devices existed, so it said "无声音"
            // even with a microphone selected. Recompute now that the combos are filled.
            updateDeckSpec();
        })).exceptionally(t -> {
            Platform.runLater(() -> toast("枚举设备失败：" + t.getMessage(), true));
            return null;
        });
    }

    private record Discovered(List<AudioDevice> devices, List<String> encoders) {
    }

    private record EncoderChoice(String codec, String label) {
    }

    private static String encoderLabel(String codec) {
        return switch (codec) {
            case "h264_nvenc" -> "NVIDIA 硬件编码";
            case "h264_qsv" -> "Intel 硬件编码";
            case "h264_amf" -> "AMD 硬件编码";
            default -> "x264 软件编码";
        };
    }

    /** A combo entry that can mean "do not record this source". */
    private record AudioChoice(String label, AudioDevice device) {
    }

    private ComboBox<AudioChoice> audioCombo() {
        ComboBox<AudioChoice> combo = new ComboBox<>();
        combo.getStyleClass().add("combo");
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.setButtonCell(audioCell());
        combo.setCellFactory(lv -> audioCell());
        return combo;
    }

    private ListCell<AudioChoice> audioCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(AudioChoice item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.label());
            }
        };
    }

    private void fillAudioCombo(ComboBox<AudioChoice> combo, List<AudioDevice> devices, boolean preferMic) {
        List<AudioChoice> items = new ArrayList<>();
        items.add(new AudioChoice("不录制", null));
        for (AudioDevice device : devices) {
            String label = device.label();
            String lower = label.toLowerCase();
            // Many Windows devices are already called "麦克风 (...)", so only add a
            // qualifier when the name does not say what kind of device it is.
            boolean selfDescribing = lower.contains("麦克风") || lower.contains("microphone")
                    || lower.contains("立体声") || lower.contains("stereo");
            String prefix = device.isLoopback() ? "系统声音 · " : "麦克风 · ";
            items.add(new AudioChoice(selfDescribing ? label : prefix + label, device));
        }
        combo.setItems(FXCollections.observableArrayList(items));

        AudioChoice defaultChoice = items.get(0);
        for (AudioChoice choice : items) {
            if (choice.device() == null) {
                continue;
            }
            boolean match = preferMic ? !choice.device().isLoopback() : choice.device().isLoopback();
            if (match) {
                defaultChoice = choice;
                break;
            }
        }
        combo.setValue(defaultChoice);

        if (!preferMic) {
            boolean hasLoopback = devices.stream().anyMatch(AudioDevice::isLoopback);
            if (!hasLoopback && systemAudioHintLabel != null) {
                // Staying enabled but empty-handed is worse than one honest sentence
                // with a way to fix it, so the label doubles as the call to action.
                systemAudioHintLabel.setText("未检测到可录制系统声音的设备 · 查看启用方法 →");
                systemAudioHintLabel.setVisible(true);
                systemAudioHintLabel.setManaged(true);
            }
        }
    }

    // ------------------------------------------------------------------ recorder wiring

    {
        recorder.addListener(new RecorderListener() {
            @Override
            public void onStarted(String outputPath) {
                Platform.runLater(JRecApp.this::enterRecordingState);
            }

            @Override
            public void onProgress(java.time.Duration elapsed, long frame) {
                // The wall clock drives the display; this callback only proves liveness.
            }

            @Override
            public void onFinished(RecordingResult result) {
                Platform.runLater(() -> {
                    exitRecordingState();
                    refreshHistory();
                    toast("已保存 " + result.outputPath().getFileName()
                            + " · " + result.humanReadableSize(), false);
                });
            }

            @Override
            public void onFailed(String message, Throwable cause) {
                Platform.runLater(() -> {
                    exitRecordingState();
                    // The first line is the headline; the rest is the FFmpeg log tail, and
                    // that tail is the only thing that says why the recording died. Showing
                    // only the headline turns every failure into "录制失败", which is the
                    // one sentence that helps nobody.
                    toast(firstLine(message), true);
                    showFailureDetails(message);
                });
            }
        });
    }

    /**
     * Shows the full failure report, FFmpeg's own output included.
     * <p>
     * The toast carries the headline because it is where the eye already is, but the
     * headline alone never says what to fix. The detail goes in a scrollable dialog,
     * which is also the only place long enough to keep the log intact.
     */
    private void showFailureDetails(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(stage);
        alert.setTitle("录制失败");
        alert.setHeaderText(firstLine(message));
        TextArea area = new TextArea(message == null ? "未知错误" : message);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefRowCount(16);
        area.setPrefColumnCount(80);
        alert.getDialogPane().setContent(area);
        alert.show();
    }

    private void enterRecordingState() {
        recordingStartedAt = System.nanoTime();
        litTicks = -1;
        // gdigrab cannot exclude a window, so by default the pet steps out of the frame.
        // Users who want it visible while recording get the REC badge instead — and get told,
        // in the settings, that it will be in the video.
        if (pet != null) {
            if (prefs.getBoolean(PREFS_PET_STAY, false)) {
                pet.setRecordingLook(true);
            } else {
                pet.hide();
            }
        }
        if (controlBar != null) {
            controlBar.setTime(0);
            controlBar.showFor(this::stopRecording);
        }
        // Getting the window out of the frame is the whole point of the bar.
        if (stage != null) {
            stage.setIconified(true);
        }
        configView.setVisible(false);
        configView.setManaged(false);
        recordingView.setVisible(true);
        recordingView.setManaged(true);
        RecordingConfig active = recorder.config();
        if (active != null) {
            // Show what is actually being produced, not a reassurance the user can see
            // for themselves. A recorder should tell you the frame size while it runs.
            String size = active.mode() == com.rzx.jrec.core.RecordingMode.REGION && active.region() != null
                    ? active.region().width() + " × " + active.region().height()
                    : "全屏";
            recordingCaption.setText(size + " · " + active.fps() + " fps · " + active.videoCodec());
            recordingFileLabel.setText(active.outputPath().getFileName().toString());
        }

        recBlink = new FadeTransition(Duration.seconds(0.9), recDot);
        recBlink.setFromValue(1.0);
        recBlink.setToValue(0.55);
        recBlink.setCycleCount(Animation.INDEFINITE);
        recBlink.setAutoReverse(true);
        recBlink.play();

        wallClock = new Timeline(new KeyFrame(Duration.millis(200), e -> {
            long seconds = (System.nanoTime() - recordingStartedAt) / 1_000_000_000L;
            timerLabel.setText(MediaProbe.formatDuration(seconds));

            if (controlBar != null && controlBar.isShowing()) {
                controlBar.setTime(seconds);
            }

            int lit = (int) (seconds % 30);
            if (lit != litTicks) {
                for (int i = 0; i < ticks.size(); i++) {
                    ticks.get(i).pseudoClassStateChanged(LIT, i < lit);
                }
                litTicks = lit;
            }
        }));
        wallClock.setCycleCount(Timeline.INDEFINITE);
        wallClock.play();
    }

    private void exitRecordingState() {
        starting = false;
        if (wallClock != null) {
            wallClock.stop();
            wallClock = null;
        }
        if (recBlink != null) {
            recBlink.stop();
            recBlink = null;
            recDot.setOpacity(1);
        }
        for (Region tick : ticks) {
            tick.pseudoClassStateChanged(LIT, false);
        }
        litTicks = -1;
        if (pet != null) {
            pet.setRecordingLook(false);
            if (prefs.getBoolean(PREFS_PET, true)) {
                pet.show(prefs.getDouble(PREFS_PET_X, Double.NaN),
                        prefs.getDouble(PREFS_PET_Y, Double.NaN));
            }
        }
        if (controlBar != null) {
            controlBar.hide();
        }
        if (stage != null) {
            stage.setIconified(false);
            stage.toFront();
        }
        recordingView.setVisible(false);
        recordingView.setManaged(false);
        configView.setVisible(true);
        configView.setManaged(true);
        recordButton.setDisable(false);
        timerLabel.setText("00:00");
    }

    // ------------------------------------------------------------------ history

    private void refreshHistory() {
        Path dir = outputDirOrNull();
        if (dir == null) {
            // The field holds something unusable. Saying so beats a stack trace on the
            // UI thread, and beats listing a directory that does not exist.
            if (historyWhere != null) {
                historyWhere.setText("保存目录无效");
            }
            latestFile = null;
            latestName.setText("保存目录无效");
            latestMeta.setText("请在「03 保存位置」里重新选择");
            latestPlay.setDisable(true);
            latestReveal.setDisable(true);
            historyBox.getChildren().clear();
            return;
        }
        if (historyWhere != null) {
            historyWhere.setText(shortPath(dir));
        }
        List<Path> files = listRecordings(dir);

        latestFile = files.isEmpty() ? null : files.get(0);
        if (latestFile == null) {
            latestName.setText("还没有录制");
            latestMeta.setText("按「开始录制」，或单击桌宠");
            latestPlay.setDisable(true);
            latestReveal.setDisable(true);
        } else {
            Path file = latestFile;
            latestName.setText(file.getFileName().toString());
            latestMeta.setText("读取中…");
            latestPlay.setDisable(false);
            latestReveal.setDisable(false);
            // Probing spawns FFmpeg, so keep it off the UI thread and fill the label later.
            CompletableFuture.supplyAsync(() -> MediaProbe.probe(file))
                    .thenAccept(info -> Platform.runLater(() -> {
                        // A newer take may have replaced it while this probe was running.
                        if (file.equals(latestFile)) {
                            latestMeta.setText(describe(info));
                        }
                    }))
                    .exceptionally(t -> {
                        // Without this the label would read "读取中…" forever, which looks
                        // like the app is still working on a file it has given up on.
                        Platform.runLater(() -> {
                            if (file.equals(latestFile)) {
                                latestMeta.setText("无法读取该文件的信息");
                            }
                        });
                        return null;
                    });
        }

        historyBox.getChildren().clear();
        if (files.size() <= 1) {
            Label empty = new Label(files.isEmpty()
                    ? "录完的文件会出现在这里"
                    : "更早的录制会列在这里");
            empty.getStyleClass().add("history-empty");
            historyBox.getChildren().add(empty);
            return;
        }
        for (Path file : files.subList(1, files.size())) {
            historyBox.getChildren().add(historyRow(file));
        }
    }

    private static String describe(MediaProbe.MediaInfo info) {
        return info.durationText() + " · " + info.resolution()
                + (info.hasAudio() ? " · 含音轨" : " · 无音轨")
                + " · " + humanSize(info.sizeBytes());
    }

    private static List<Path> listRecordings(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".mp4"))
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .limit(5)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private HBox historyRow(Path file) {
        Label name = new Label(file.getFileName().toString());
        name.getStyleClass().add("file-name");

        Label meta = new Label("读取中…");
        meta.getStyleClass().add("file-meta");

        VBox texts = new VBox(2, name, meta);

        Button play = new Button("播放");
        play.getStyleClass().add("link-btn");
        play.setOnAction(e -> openPath(file));

        Button reveal = new Button("打开目录");
        reveal.getStyleClass().add("link-btn");
        reveal.setOnAction(e -> openPath(file.getParent()));

        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);

        HBox row = new HBox(10, texts, grow, play, reveal);
        row.getStyleClass().add("history-row");
        row.setAlignment(Pos.CENTER_LEFT);

        // Probing spawns FFmpeg, so keep it off the UI thread and fill the label later.
        CompletableFuture.supplyAsync(() -> MediaProbe.probe(file))
                .thenAccept(info -> Platform.runLater(() -> meta.setText(describe(info))))
                .exceptionally(t -> {
                    // Without this the row would read "读取中…" for good, which looks
                    // like the app is still working on a file it has given up on.
                    Platform.runLater(() -> meta.setText("无法读取该文件的信息"));
                    return null;
                });
        return row;
    }

    /** Last two path segments, so a long destination fits on one line. */
    private static String shortPath(Path path) {
        int n = path.getNameCount();
        if (n <= 2) {
            return path.toString();
        }
        return "…" + File.separator + path.getName(n - 2) + File.separator + path.getName(n - 1);
    }

    private static String humanSize(long bytes) {
        if (bytes < 0) {
            return "未知";
        }
        double mb = bytes / 1024.0 / 1024.0;
        return mb >= 1024 ? String.format("%.2f GB", mb / 1024) : String.format("%.1f MB", mb);
    }

    /**
     * Brings up the mascot and points its actions back at this window.
     * <p>
     * Everything the pet can do is routed through the same methods the window uses, so
     * there is no second implementation to keep in sync — and a click on the pet cannot
     * end up in a state the window does not know about.
     */
    private void startPet() {
        pet = new PetWindow();
        pet.setRecordingCheck(recorder::isRecording);
        pet.setOnToggle(this::toggleRecording);
        pet.setOnOpenMain(this::showMainWindow);
        pet.setOnOpenLatest(this::openLatest);
        pet.setOnOpenFolder(() -> {
            Path dir = outputDirOrNull();
            if (dir == null) {
                toast("保存目录无效，请重新选择", true);
                return;
            }
            openPath(dir);
        });
        pet.setOnHide(() -> setPetVisible(false));
        pet.setOnQuit(Platform::exit);
        pet.setHint("点我：开始录制 · 右键更多");
        // A size picked from the right-click menu has to reach the settings sheet too, or
        // the two places start disagreeing about how big the pet is.
        pet.setSizeHandler(pixels -> {
            prefs.putDouble(PREFS_PET_SIZE, pixels);
            if (petSizeCombo != null) {
                petSizeCombo.setValue(PetWindow.sizeLabel(pixels));
            }
        });

        pet.setArtHeight(prefs.getDouble(PREFS_PET_SIZE, PetWindow.DEFAULT_HEIGHT));
        pet.setArtOpacity(prefs.getDouble(PREFS_PET_OPACITY, 1.0));

        boolean visible = prefs.getBoolean(PREFS_PET, true);
        if (petToggle != null) {
            petToggle.setSelected(visible);
        }
        if (visible) {
            pet.show(prefs.getDouble(PREFS_PET_X, Double.NaN),
                    prefs.getDouble(PREFS_PET_Y, Double.NaN));
        }
    }

    private void setPetVisible(boolean visible) {
        prefs.putBoolean(PREFS_PET, visible);
        if (petToggle != null && petToggle.isSelected() != visible) {
            petToggle.setSelected(visible);
        }
        if (pet == null) {
            return;
        }
        if (visible) {
            pet.show(prefs.getDouble(PREFS_PET_X, Double.NaN),
                    prefs.getDouble(PREFS_PET_Y, Double.NaN));
        } else {
            prefs.putDouble(PREFS_PET_X, pet.x());
            prefs.putDouble(PREFS_PET_Y, pet.y());
            pet.hide();
        }
    }

    private void showMainWindow() {
        if (stage.isIconified()) {
            stage.setIconified(false);
        }
        stage.show();
        stage.toFront();
    }

    private void openLatest() {
        if (latestFile == null) {
            toast("还没有录制文件", false);
            return;
        }
        openPath(latestFile);
    }

    /**
     * Opens the containing folder with the file already selected. "Show me where this went"
     * is the actual request; dropping the user into a folder to hunt for the file is not.
     */
    private void reveal(Path file) {
        if (file == null) {
            return;
        }
        try {
            new ProcessBuilder("explorer.exe", "/select," + file.toAbsolutePath()).start();
        } catch (java.io.IOException e) {
            openPath(file.getParent());
        }
    }

    private void openPath(Path path) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(path.toFile());
            }
        } catch (IOException | UnsupportedOperationException e) {
            toast("无法打开：" + e.getMessage(), true);
        }
    }

    // ------------------------------------------------------------------ misc

    private void toast(String message, boolean error) {
        // Two toasts in a row would otherwise race: the first one's hide transition fires
        // after the second one has already been written, and the new message disappears
        // early. Stopping the previous transition first makes the last message win.
        if (toastHide != null) {
            toastHide.stop();
        }
        toast.setText(message);
        toast.getStyleClass().setAll("toast");
        if (error) {
            toast.getStyleClass().add("toast-error");
        }
        toast.setVisible(true);
        toast.setManaged(true);
        PauseTransition hide = new PauseTransition(Duration.seconds(error ? 6 : 4));
        toastHide = hide;
        hide.setOnFinished(e -> {
            // Only the transition that is still the current one may hide the toast. A
            // newer message has installed its own transition, and this one must not
            // take it down with it.
            if (toastHide != hide) {
                return;
            }
            toast.setVisible(false);
            toast.setManaged(false);
            toastHide = null;
        });
        hide.play();
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "录制失败";
        }
        int nl = message.indexOf('\n');
        return nl > 0 ? message.substring(0, nl) : message;
    }

    /**
     * The directory the user typed, validated.
     * <p>
     * The field is free text, so the value can be anything: an empty string, a path with
     * a character the platform forbids, a path that cannot be written to. Resolving it
     * without checking turns a typo into an unhandled exception on the UI thread, so
     * every read goes through here and reports the problem in the user's own words.
     *
     * @throws RecorderException when the text is not a usable directory
     */
    private Path outputDir() {
        return outputDir(true);
    }

    /**
     * The directory the user typed, validated.
     *
     * @param create whether a missing folder may be created. Listing the recordings must
     *               not create anything: the history list is read on every window
     *               refresh, and a stray empty folder would appear for a path the user
     *               only typed but never used.
     * @throws RecorderException when the text is not a usable directory
     */
    private Path outputDir(boolean create) {
        String text = dirField.getText() == null ? "" : dirField.getText().trim();
        if (text.isEmpty()) {
            throw new RecorderException("请先选择保存目录");
        }
        Path dir;
        try {
            dir = Path.of(text).toAbsolutePath();
        } catch (InvalidPathException e) {
            throw new RecorderException("保存目录不是合法的路径：" + text);
        }
        if (Files.exists(dir) && !Files.isDirectory(dir)) {
            throw new RecorderException("保存目录指向一个文件，不是文件夹：" + dir);
        }
        if (!Files.exists(dir)) {
            if (!create) {
                throw new RecorderException("保存目录不存在：" + dir);
            }
            // A folder that does not exist yet is fine — the recorder creates it. But it
            // has to be creatable, and the surest way to know is to create it here.
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new RecorderException("无法创建保存目录：" + dir + "（" + e.getMessage() + "）");
            }
        }
        return dir;
    }

    /** The directory, or null when it cannot be resolved — for display-only call sites. */
    private Path outputDirOrNull() {
        try {
            return outputDir(false);
        } catch (RecorderException e) {
            return null;
        }
    }

    private Path defaultOutputDir() {
        String saved = prefs.get(PREFS_DIR, null);
        if (saved != null && !saved.isBlank()) {
            try {
                Path candidate = Path.of(saved);
                // A remembered path can disappear (external drive, cleaned folder). Falling
                // back beats opening with a directory that cannot be written to.
                if (Files.isDirectory(candidate)) {
                    return candidate;
                }
            } catch (InvalidPathException e) {
                // The preference holds a path this platform cannot parse. That must not
                // stop the application from starting — the default is a fine answer.
            }
        }
        return Path.of(System.getProperty("user.home"), "Videos", "JRec");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
