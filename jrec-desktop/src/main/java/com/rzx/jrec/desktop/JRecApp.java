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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;

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
        try (java.io.InputStream icon = getClass().getResourceAsStream("/com/rzx/jrec/desktop/app-icon.png")) {
            if (icon != null) {
                stage.getIcons().add(new Image(icon));
            }
        } catch (java.io.IOException ignored) {
            // The icon is cosmetic; never block startup for it.
        }
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
        root.setPadding(new Insets(18, 26, 14, 26));

        HBox history = historySection();
        configView = new VBox(0);
        configView.getChildren().addAll(
                header(),
                regionSection(),
                soundSection(),
                qualitySection(),
                outputSection(),
                recordArea(),
                history
        );
        VBox.setVgrow(history, Priority.ALWAYS);

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

        HBox topRow = new HBox(12, left, spacer, ffmpegChip);
        topRow.setAlignment(Pos.CENTER_LEFT);

        // A short signal bar under the wordmark: the one loud gesture in the header.
        Region rule = new Region();
        rule.getStyleClass().add("mark-rule");
        rule.setMinSize(30, 2);
        rule.setPrefSize(30, 2);
        rule.setMaxSize(30, 2);

        VBox box = new VBox(9, topRow, rule);
        box.getStyleClass().add("app-header");
        return box;
    }

    private HBox regionSection() {
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

        HBox modes = new HBox(26, fullScreenToggle, regionToggle);

        regionValue = new Label("整个桌面（含全部显示器）");
        regionValue.getStyleClass().add("readout");

        pickRegionButton = new Button("重新框选");
        pickRegionButton.getStyleClass().add("btn-ghost");
        pickRegionButton.setOnAction(e -> {
            pickRegion();
            if (region != null) {
                modeGroup.selectToggle(regionToggle);
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox body = new HBox(12, modes, spacer, regionValue, pickRegionButton);
        body.setAlignment(Pos.CENTER_LEFT);
        return section("01", "录制范围", "CAPTURE", body, false);
    }

    private HBox soundSection() {
        micCombo = audioCombo();
        systemCombo = audioCombo();

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
        return section("02", "声音", "AUDIO", body, false);
    }

    private HBox qualitySection() {
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
            // Without this a combo refuses to shrink and the row overflows its column.
            combo.setMinWidth(0);
            combo.setMaxWidth(Double.MAX_VALUE);
        }

        GridPane grid = grid(3,
                field("帧率", fpsCombo),
                field("画质", qualityCombo),
                field("编码器", encoderCombo));
        grid.setMinWidth(0);
        return section("03", "画质", "ENCODE", grid, false);
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
        dirField = new TextField(defaultOutputDir().toString());
        dirField.getStyleClass().add("text-field");
        dirField.setMinWidth(0);
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

        HBox body = new HBox(9, dirField, browse, openDir);
        body.setAlignment(Pos.CENTER_LEFT);
        return section("04", "保存位置", "OUTPUT", body, false);
    }

    private VBox recordArea() {
        recordButton = new Button("开始录制");
        recordButton.getStyleClass().add("btn-record");
        // Fixed width on purpose: a full-bleed bar turned the one accent colour into a
        // background wash. Sized like a physical key, it stays the thing the eye lands on.
        recordButton.setPrefWidth(300);
        recordButton.setMinWidth(240);
        recordButton.setOnAction(e -> startRecording());

        Label spec = new Label("MP4 · H.264 · AAC · 可直接播放");
        spec.getStyleClass().add("hint");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(16, recordButton, spacer, spec);
        row.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(row);
        body.setPadding(new Insets(16, 0, 16, 0));
        return body;
    }

    private HBox historySection() {
        historyBox = new VBox(0);

        ScrollPane scroll = new ScrollPane(historyBox);
        scroll.getStyleClass().add("history-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        // A VBox child only takes leftover vertical space when its max height allows it.
        scroll.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        HBox sec = section("05", "最近录制", "HISTORY", scroll, true);
        sec.getStyleClass().add("sec-end");
        sec.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(sec, Priority.ALWAYS);
        return sec;
    }

    private VBox buildRecordingView() {
        recDot = new Circle(4, Color.web("#FF5A22"));
        Label pill = new Label("REC");
        pill.getStyleClass().add("rec-pill");
        HBox pillRow = new HBox(8, recDot, pill);
        pillRow.setAlignment(Pos.CENTER);

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

        VBox card = new VBox(15, pillRow, timerLabel, meter, recordingCaption, recordingFileLabel, stop);
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
