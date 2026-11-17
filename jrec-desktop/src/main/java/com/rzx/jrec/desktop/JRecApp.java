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
import com.rzx.jrec.desktop.ui.RegionSelector;
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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;

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

    private Label timerLabel;
    private Label recordingFileLabel;
    private Label recordingCaption;
    private Label toast;
    private VBox historyBox;
    private Circle recDot;
    private Timeline wallClock;
    private long recordingStartedAt;

    private Rect region;

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        buildUi();
        refreshHistory();
        probeFfmpegAsync();

        Scene scene = new Scene(root, 780, 720);
        scene.getStylesheets().add(getClass().getResource("/com/rzx/jrec/desktop/style.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("JRec — 屏幕录制");
        stage.setMinWidth(720);
        stage.setMinHeight(640);
        stage.setOnCloseRequest(e -> {
            if (recorder.isRecording()) {
                e.consume();
                toast("正在录制，请先停止录制", true);
            }
        });
        stage.show();
    }

    @Override
    public void stop() {
        // A recording left running would keep FFmpeg alive after the window closes.
        if (recorder.isRecording()) {
            recorder.stop();
        }
    }

    // ------------------------------------------------------------------ layout

    private void buildUi() {
        root = new StackPane();
        root.getStyleClass().add("root");
        root.setPadding(new Insets(22, 24, 22, 24));

        configView = new VBox(14);
        configView.getChildren().addAll(
                header(),
                regionCard(),
                optionsCard(),
                outputCard(),
                recordButton(),
                historySection()
        );
        VBox.setVgrow(configView.getChildren().get(5), Priority.ALWAYS);

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

    private HBox header() {
        Label brand = new Label("JRec");
        brand.getStyleClass().add("brand");

        Label tagline = new Label("Java 跨平台屏幕录制 · 全屏 / 自选区域");
        tagline.getStyleClass().add("tagline");

        VBox titles = new VBox(2, brand, tagline);
        titles.setAlignment(Pos.CENTER_LEFT);

        ffmpegChip = new Label("检测 FFmpeg…");
        ffmpegChip.getStyleClass().addAll("chip", "chip-warn");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox box = new HBox(12, titles, spacer, ffmpegChip);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private VBox regionCard() {
        Label title = new Label("录制范围");
        title.getStyleClass().add("card-title");

        modeGroup = new ToggleGroup();
        fullScreenToggle = new ToggleButton("全屏");
        fullScreenToggle.getStyleClass().add("seg");
        fullScreenToggle.setToggleGroup(modeGroup);
        fullScreenToggle.setSelected(true);

        ToggleButton regionToggle = new ToggleButton("自选区域");
        regionToggle.getStyleClass().add("seg");
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

        HBox modes = new HBox(8, fullScreenToggle, regionToggle);

        regionValue = new Label("整个桌面（含全部显示器）");
        regionValue.getStyleClass().add("value-text");

        pickRegionButton = new Button("重新框选");
        pickRegionButton.getStyleClass().add("btn-ghost");
        pickRegionButton.setOnAction(e -> {
            pickRegion();
            if (region != null) {
                modeGroup.selectToggle(regionToggle);
            }
        });

        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox row = new HBox(10, regionValue, grow, pickRegionButton);
        row.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(10, title, modes, row);
        card.getStyleClass().add("card");
        return card;
    }

    private VBox optionsCard() {
        Label title = new Label("声音与画质");
        title.getStyleClass().add("card-title");

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

        for (ComboBox<?> combo : List.of(micCombo, systemCombo, fpsCombo, qualityCombo, encoderCombo)) {
            // Without this a combo refuses to shrink and the row overflows the card.
            combo.setMinWidth(0);
            combo.setMaxWidth(Double.MAX_VALUE);
        }

        // Six equal columns: row one uses 3+3, row two uses 2+2+2, so the two rows
        // line up without any manual pixel maths.
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(12);
        for (int i = 0; i < 6; i++) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setPercentWidth(100.0 / 6);
            constraints.setFillWidth(true);
            grid.getColumnConstraints().add(constraints);
        }

        grid.add(field("麦克风", micCombo), 0, 0, 3, 1);
        grid.add(field("系统声音", systemCombo), 3, 0, 3, 1);
        grid.add(field("帧率", fpsCombo), 0, 1, 2, 1);
        grid.add(field("画质", qualityCombo), 2, 1, 2, 1);
        grid.add(field("编码器", encoderCombo), 4, 1, 2, 1);

        systemAudioHintLabel = new Label();
        systemAudioHintLabel.getStyleClass().add("warn-link");
        systemAudioHintLabel.setVisible(false);
        systemAudioHintLabel.setManaged(false);
        systemAudioHintLabel.setOnMouseClicked(e -> showSystemAudioHint());

        VBox card = new VBox(12, title, grid, systemAudioHintLabel);
        card.getStyleClass().add("card");
        // The card must not be stretched by an over-wide child.
        card.setMinWidth(0);
        return card;
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

    private VBox outputCard() {
        Label title = new Label("保存位置");
        title.getStyleClass().add("card-title");

        dirField = new TextField(defaultOutputDir().toString());
        dirField.getStyleClass().add("text-field");
        HBox.setHgrow(dirField, Priority.ALWAYS);

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
                prefs.put(PREFS_DIR, chosen.getAbsolutePath());
                refreshHistory();
            }
        });

        Button openDir = new Button("打开");
        openDir.getStyleClass().add("btn-ghost");
        openDir.setOnAction(e -> openPath(Path.of(dirField.getText())));

        HBox row = new HBox(10, dirField, browse, openDir);
        row.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(10, title, row);
        card.getStyleClass().add("card");
        return card;
    }

    private Button recordButton() {
        recordButton = new Button("开始录制");
        recordButton.getStyleClass().add("btn-record");
        recordButton.setMaxWidth(Double.MAX_VALUE);
        recordButton.setOnAction(e -> startRecording());
        return recordButton;
    }

    private VBox historySection() {
        Label title = new Label("最近录制");
        title.getStyleClass().add("card-title");

        historyBox = new VBox(8);

        ScrollPane scroll = new ScrollPane(historyBox);
        scroll.getStyleClass().add("history-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        // A VBox child only takes leftover vertical space when its max height allows it.
        scroll.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox box = new VBox(10, title, scroll);
        box.getStyleClass().add("card");
        box.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    private VBox buildRecordingView() {
        recDot = new Circle(5, Color.web("#ff4d5e"));
        Label pill = new Label("录制中");
        pill.getStyleClass().add("rec-pill");
        HBox pillRow = new HBox(8, recDot, pill);
        pillRow.setAlignment(Pos.CENTER);

        timerLabel = new Label("00:00");
        timerLabel.getStyleClass().add("timer");

        recordingCaption = new Label("停止后自动保存为 MP4");
        recordingCaption.getStyleClass().add("rec-caption");

        recordingFileLabel = new Label();
        recordingFileLabel.getStyleClass().add("file-meta");

        Button stop = new Button("停止录制");
        stop.getStyleClass().add("btn-stop");
        stop.setMaxWidth(Double.MAX_VALUE);
        stop.setOnAction(e -> stopRecording());

        VBox card = new VBox(14, pillRow, timerLabel, recordingCaption, recordingFileLabel, stop);
        card.getStyleClass().add("card");
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(34, 30, 30, 30));
        // A recorder's stop panel should not stretch across the whole window.
        card.setMaxWidth(440);

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

    // ------------------------------------------------------------------ actions

    private void pickRegion() {
        RegionSelector.pick(stage).ifPresentOrElse(r -> {
            region = r;
            updateRegionUi();
        }, () -> updateRegionUi());
    }

    private void updateRegionUi() {
        if (modeGroup.getSelectedToggle() == fullScreenToggle || region == null) {
            regionValue.setText("整个桌面（含全部显示器）");
            return;
        }
        regionValue.setText(region.width() + " × " + region.height()
                + "  @ (" + region.x() + ", " + region.y() + ")");
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
        recordButton.setText("下载 FFmpeg 后即可录制");
        recordButton.setOnAction(e -> downloadFfmpeg());
    }

    private void downloadFfmpeg() {
        recordButton.setDisable(true);
        recordButton.setText("正在下载…");
        CompletableFuture.runAsync(() -> FfmpegDownloader.install(new FfmpegDownloader.ProgressListener() {
            @Override
            public void onProgress(long downloaded, long total) {
                String text = total > 0
                        ? String.format("正在下载 %.0f%%", downloaded * 100.0 / total)
                        : "正在下载…";
                Platform.runLater(() -> {
                    ffmpegChip.setText(text);
                    recordButton.setText(text);
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
            recordButton.setText("开始录制");
            recordButton.setOnAction(e -> startRecording());
            loadOptionsAsync();
            toast("FFmpeg 安装完成", false);
        })).exceptionally(t -> {
            Platform.runLater(() -> {
                recordButton.setDisable(false);
                recordButton.setText("重试下载 FFmpeg");
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
            default -> "x264 软件编码（兼容性最好）";
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
            String prefix = device.isLoopback() ? "系统声音 · " : "麦克风 · ";
            items.add(new AudioChoice(prefix + device.label(), device));
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
        configView.setVisible(false);
        configView.setManaged(false);
        recordingView.setVisible(true);
        recordingView.setManaged(true);
        recordingCaption.setText("停止后自动保存为 MP4");
        if (recorder.config() != null) {
            recordingFileLabel.setText(recorder.config().outputPath().getFileName().toString());
        }

        wallClock = new Timeline(new KeyFrame(Duration.millis(200), e -> {
            long seconds = (System.nanoTime() - recordingStartedAt) / 1_000_000_000L;
            timerLabel.setText(String.format("%02d:%02d", seconds / 60, seconds % 60));
        }));
        wallClock.setCycleCount(Timeline.INDEFINITE);
        wallClock.play();
    }

    private void exitRecordingState() {
        if (wallClock != null) {
            wallClock.stop();
            wallClock = null;
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
        historyBox.getChildren().clear();
        Path dir = Path.of(dirField.getText());
        List<Path> files = listRecordings(dir);
        if (files.isEmpty()) {
            Label empty = new Label("还没有录制文件");
            empty.getStyleClass().add("file-meta");
            historyBox.getChildren().add(empty);
            return;
        }
        for (Path file : files) {
            historyBox.getChildren().add(historyRow(file));
        }
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
                .thenAccept(info -> Platform.runLater(() -> meta.setText(
                        info.durationText() + " · " + info.resolution()
                                + (info.hasAudio() ? " · 含音轨" : " · 无音轨")
                                + " · " + humanSize(info.sizeBytes()))))
                .exceptionally(t -> null);
        return row;
    }

    private static String humanSize(long bytes) {
        if (bytes < 0) {
            return "未知";
        }
        double mb = bytes / 1024.0 / 1024.0;
        return mb >= 1024 ? String.format("%.2f GB", mb / 1024) : String.format("%.1f MB", mb);
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
