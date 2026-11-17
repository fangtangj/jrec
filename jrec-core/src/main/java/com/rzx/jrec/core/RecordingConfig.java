package com.rzx.jrec.core;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable recording parameters. Build with {@link #builder()}.
 */
public final class RecordingConfig {

    private final RecordingMode mode;
    private final Rect region;
    private final int fps;
    private final String videoCodec;
    private final int crf;
    private final String preset;
    private final boolean drawMouse;
    private final AudioDevice microphone;
    private final AudioDevice systemAudio;
    private final int audioBitrateKbps;
    private final Path outputPath;

    private RecordingConfig(Builder b) {
        this.mode = b.mode;
        this.region = b.region;
        this.fps = b.fps;
        this.videoCodec = b.videoCodec;
        this.crf = b.crf;
        this.preset = b.preset;
        this.drawMouse = b.drawMouse;
        this.microphone = b.microphone;
        this.systemAudio = b.systemAudio;
        this.audioBitrateKbps = b.audioBitrateKbps;
        this.outputPath = Objects.requireNonNull(b.outputPath, "outputPath");
    }

    public static Builder builder() {
        return new Builder();
    }

    public RecordingMode mode() {
        return mode;
    }

    /** Null unless {@link #mode()} is {@link RecordingMode#REGION}. */
    public Rect region() {
        return region;
    }

    public int fps() {
        return fps;
    }

    public String videoCodec() {
        return videoCodec;
    }

    public int crf() {
        return crf;
    }

    public String preset() {
        return preset;
    }

    public boolean drawMouse() {
        return drawMouse;
    }

    public AudioDevice microphone() {
        return microphone;
    }

    public AudioDevice systemAudio() {
        return systemAudio;
    }

    public int audioBitrateKbps() {
        return audioBitrateKbps;
    }

    public Path outputPath() {
        return outputPath;
    }

    public boolean hasAudio() {
        return microphone != null || systemAudio != null;
    }

    /** Number of audio inputs the command will open. */
    public int audioInputCount() {
        return (microphone != null ? 1 : 0) + (systemAudio != null ? 1 : 0);
    }

    /**
     * Rejects combinations that cannot produce a valid file, with a message meant
     * for the end user rather than the developer.
     */
    public void validate() {
        if (fps < 1 || fps > 120) {
            throw new RecorderException("帧率必须在 1~120 之间，当前为 " + fps);
        }
        if (crf < 0 || crf > 51) {
            throw new RecorderException("CRF 必须在 0~51 之间，当前为 " + crf);
        }
        if (mode == RecordingMode.REGION) {
            if (region == null) {
                throw new RecorderException("区域录制必须指定录制区域");
            }
            if (region.width() < 16 || region.height() < 16) {
                throw new RecorderException("录制区域太小（" + region + "），宽高至少 16 像素");
            }
        }
        if (outputPath.getFileName() == null) {
            throw new RecorderException("输出路径必须指向一个文件：" + outputPath);
        }
        if (microphone != null && microphone.equals(systemAudio)) {
            throw new RecorderException("麦克风和系统声音不能选择同一个设备：" + microphone.name());
        }
    }

    public static final class Builder {
        private RecordingMode mode = RecordingMode.FULL_SCREEN;
        private Rect region;
        private int fps = 30;
        private String videoCodec = "libx264";
        private int crf = 23;
        private String preset = "veryfast";
        private boolean drawMouse = true;
        private AudioDevice microphone;
        private AudioDevice systemAudio;
        private int audioBitrateKbps = 128;
        private Path outputPath;

        public Builder mode(RecordingMode mode) {
            this.mode = Objects.requireNonNull(mode);
            return this;
        }

        public Builder region(Rect region) {
            this.region = region;
            this.mode = region == null ? RecordingMode.FULL_SCREEN : RecordingMode.REGION;
            return this;
        }

        public Builder fps(int fps) {
            this.fps = fps;
            return this;
        }

        public Builder videoCodec(String videoCodec) {
            this.videoCodec = Objects.requireNonNull(videoCodec);
            return this;
        }

        public Builder crf(int crf) {
            this.crf = crf;
            return this;
        }

        public Builder preset(String preset) {
            this.preset = Objects.requireNonNull(preset);
            return this;
        }

        public Builder drawMouse(boolean drawMouse) {
            this.drawMouse = drawMouse;
            return this;
        }

        public Builder microphone(AudioDevice microphone) {
            this.microphone = microphone;
            return this;
        }

        public Builder systemAudio(AudioDevice systemAudio) {
            this.systemAudio = systemAudio;
            return this;
        }

        public Builder audioBitrateKbps(int audioBitrateKbps) {
            this.audioBitrateKbps = audioBitrateKbps;
            return this;
        }

        public Builder outputPath(Path outputPath) {
            this.outputPath = outputPath;
            return this;
        }

        public RecordingConfig build() {
            return new RecordingConfig(this);
        }
    }
}
