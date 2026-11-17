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
    private HotkeySpec hotkeySpec;
    private boolean hotkeyBound;

    private Label timerLabel;
    private Label recordingFileLabel;
    private Label recStopHint;
    private Label recordingCaption;
    private Label toast;
    private VBox historyBox;
    private Circle recDot;
    private Timeline wallClock;
    private FadeTransition recBlink;
    private final List<Region> ticks = new ArrayList<>();
    private int litTicks = -1;

    private static final PseudoClass LIT = PseudoClass.getPseudoClass("lit");
    private long recordingStartedAt;

    private Rect region;

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        buildUi();

        Scene scene = new Scene(root, 660, 430);
        scene.getStylesheets().add(getClass().getResource("/com/rzx/jrec/desktop/style.css").toExternalForm());
        stage.setScene(scene);

        // A Dialog binds to its owner's scene at construction time, so this has to come
        // after setScene. Built once and reused, which keeps the controls' state.
        settingsDialog = buildSettingsDialog();

        refreshHistory();
        probeFfmpegAsync();
        stage.setTitle("JRec — 屏幕录制");
        stage.setMinWidth(620);
        stage.setMinHeight(400);
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
        new Thread(() -> {
            GlobalHotkey created = Hotkeys.create();
            boolean bound = created.register(hotkeySpec,
                    () -> Platform.runLater(this::toggleRecording));
            Platform.runLater(() -> adoptHotkey(created, bound));
        }, "jrec-hotkey-init").start();
    }

    @Override
    public void stop() {
        // A recording left running would keep FFmpeg alive after the window closes.
        if (recorder.isRecording()) {
            recorder.stop();
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
        dialog.setOnShowing(e -> releaseHotkey());

        dialog.setOnHidden(e -> {
            prefs.put(PREFS_DIR, dirField.getText());
            prefs.put(PREFS_QUALITY, qualityCombo.getValue());
            if (hotkey == null) {
                bindDesired();
            }
            refreshHistory();
            updateDeckSpec();
        });
        return dialog;
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
            Path current = Path.of(dirField.getText());
            if (Files.isDirectory(current)) {
                chooser.setInitialDirectory(current.toFile());
            }
            File chosen = chooser.showDialog(stage);
            if (chosen != null) {
                dirField.setText(chosen.getAbsolutePath());
            }
        });

        Button openDir = new Button("打开");
        openDir.getStyleClass().add("btn-ghost");
        openDir.setOnAction(e -> openPath(Path.of(dirField.getText())));

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
        hotkeyField.setText(hotkeySpec == null ? "" : hotkeySpec.label());
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
            hotkeyField.setText(hotkeySpec == null ? "" : hotkeySpec.label());
            showHotkeyMessage(null);
            return;
        }

        HotkeySpec candidate = Hotkeys.fromKeyEvent(event);
        if (candidate == null) {
            showHotkeyMessage("这个组合不能用：需要修饰键搭配，或单独用 F1–F12");
            return;
        }
        // The previous registration is still live here, so an identical combination would
        // collide with ourselves and report a bogus conflict.
        if (candidate.equals(hotkeySpec) && hotkeyBound) {
            hotkeyField.setText(candidate.label());
            showHotkeyMessage(null);
            return;
        }
        assignHotkey(candidate);
    }

    private void assignHotkey(HotkeySpec candidate) {
        GlobalHotkey created = Hotkeys.create();
        boolean bound = created.register(candidate,
                () -> Platform.runLater(this::toggleRecording));
        if (!bound) {
            // Keep whatever was working before; a failed attempt must not leave the user
            // with no shortcut at all.
            created.close();
            hotkeyField.setText(hotkeySpec == null ? "" : hotkeySpec.label());
            showHotkeyMessage(created.failure());
            return;
        }
        closeHotkey();
        hotkey = created;
        hotkeySpec = candidate;
        hotkeyBound = true;
        prefs.put(PREFS_HOTKEY, candidate.label());
        hotkeyField.setText(candidate.label());
        showHotkeyMessage(null);
        updateDeckHint();
    }

    private void resetHotkey() {
        closeHotkey();
        hotkeySpec = null;
        prefs.remove(PREFS_HOTKEY);
        bindDesired();
    }

    /** Binds whatever {@link #hotkeySpec} says, falling back to the built-in list when null. */
    private void bindDesired() {
        closeHotkey();
        GlobalHotkey created = Hotkeys.create();
        boolean bound = created.register(hotkeySpec,
                () -> Platform.runLater(this::toggleRecording));
        adoptHotkey(created, bound);
    }

    private void adoptHotkey(GlobalHotkey created, boolean bound) {
        hotkey = created;
        hotkeyBound = bound;
        if (bound) {
            String actual = created.label();
            if (hotkeySpec == null) {
                hotkeySpec = HotkeySpec.parse(actual);
            }
            if (hotkeySpec != null) {
                prefs.put(PREFS_HOTKEY, hotkeySpec.label());
            }
            if (hotkeyField != null) {
                hotkeyField.setText(hotkeySpec == null ? actual : hotkeySpec.label());
            }
            showHotkeyMessage(null);
        } else {
            showHotkeyMessage(created.failure());
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

    private void showHotkeyMessage(String error) {
        if (hotkeyStatus == null) {
            return;
        }
        boolean bad = error != null;
        hotkeyStatus.setText(bad ? error : "当前生效：" + (hotkeySpec == null
                ? "默认（自动挑选空闲组合）" : hotkeySpec.label()));
        hotkeyStatus.getStyleClass().setAll(bad ? "field-error" : "field-note");
    }

    private void updateDeckHint() {
        if (recStopHint != null) {
            recStopHint.setText(hotkeyBound && hotkeySpec != null
                    ? "或按 " + hotkeySpec.label() + " 停止"
                    : "");
        }
        if (keyHint == null) {
            return;
        }
        if (hotkeyBound && hotkeySpec != null) {
            keyHint.setText(hotkeySpec.label());
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
        petSizeCombo.setValue(sizeLabel(prefs.getDouble(PREFS_PET_SIZE, PetWindow.DEFAULT_HEIGHT)));
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

        double current = prefs.getDouble(PREFS_PET_OPACITY, 1.0) * 100;
        petOpacitySlider = new Slider(35, 100, current);
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

    private static double sizePixels(String label) {
        return switch (label) {
            case "小" -> 160;
            case "大" -> 272;
            default -> PetWindow.DEFAULT_HEIGHT;
        };
    }

    private static String sizeLabel(double pixels) {
        if (pixels <= 175) {
            return "小";
        }
        return pixels >= 240 ? "大" : "中";
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
        AudioChoice mic = micCombo == null ? null : micCombo.getValue();
        String audio = mic == null || mic.device() == null ? "无声音" : "麦克风";
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
        RecordingConfig config;
        try {
            config = buildConfig();
        } catch (RecorderException e) {
            toast(e.getMessage(), true);
            return;
        }

        recordButton.setDisable(true);
        new Thread(() -> {
            try {
                recorder.start(config);
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
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
        Path dir = Path.of(dirField.getText().trim());
        if (dir.toString().isBlank()) {
            throw new RecorderException("请先选择保存目录");
        }
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
        }));
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
                    toast(firstLine(message), true);
                });
            }
        });
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
            timerLabel.setText(String.format("%02d:%02d", seconds / 60, seconds % 60));

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
        Path dir = Path.of(dirField.getText());
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
                    .exceptionally(t -> null);
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
                .exceptionally(t -> null);
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
        pet.setOnOpenFolder(() -> openPath(Path.of(dirField.getText())));
        pet.setOnHide(() -> setPetVisible(false));
        pet.setOnQuit(Platform::exit);
        pet.setHint(hotkeySpec != null ? "点我：开始录制 · 右键更多" : "点我：开始录制");
        // A size picked from the right-click menu has to reach the settings sheet too, or
        // the two places start disagreeing about how big the pet is.
        pet.setSizeHandler(pixels -> {
            prefs.putDouble(PREFS_PET_SIZE, pixels);
            if (petSizeCombo != null) {
                petSizeCombo.setValue(sizeLabel(pixels));
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
        toast.setText(message);
        toast.getStyleClass().setAll("toast");
        if (error) {
            toast.getStyleClass().add("toast-error");
        }
        toast.setVisible(true);
        toast.setManaged(true);
        PauseTransition hide = new PauseTransition(Duration.seconds(error ? 6 : 4));
        hide.setOnFinished(e -> {
            toast.setVisible(false);
            toast.setManaged(false);
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

    private Path defaultOutputDir() {
        String saved = prefs.get(PREFS_DIR, null);
        if (saved != null && !saved.isBlank()) {
            Path candidate = Path.of(saved);
            // A remembered path can disappear (external drive, cleaned folder). Falling
            // back beats opening with a directory that cannot be written to.
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return Path.of(System.getProperty("user.home"), "Videos", "JRec");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
