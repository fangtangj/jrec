package com.rzx.jrec.core;

import com.rzx.jrec.core.backend.CaptureBackend;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the FFmpeg argument vector.
 * <p>
 * Kept as a pure function of (config, backend) so it can be unit-tested without
 * spawning a process or owning a screen. Every platform-specific decision is
 * delegated to the {@link CaptureBackend}.
 */
public final class CommandBuilder {

    private CommandBuilder() {
    }

    public static List<String> build(String ffmpegExecutable, RecordingConfig config, CaptureBackend backend) {
        config.validate();

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegExecutable);
        cmd.add("-hide_banner");
        cmd.add("-y");
        cmd.add("-loglevel");
        cmd.add("info");
        cmd.add("-stats_period");
        cmd.add("0.5");

        cmd.addAll(backend.videoInputArgs(config));

        List<AudioDevice> audioInputs = new ArrayList<>(2);
        if (config.microphone() != null) {
            audioInputs.add(config.microphone());
        }
        if (config.systemAudio() != null) {
            audioInputs.add(config.systemAudio());
        }
        for (AudioDevice device : audioInputs) {
            cmd.addAll(backend.audioInputArgs(device));
        }

        String videoFilter = backend.videoFilter(config);
        if (videoFilter != null) {
            cmd.add("-vf");
            cmd.add(videoFilter);
        }

        cmd.add("-map");
        cmd.add("0:v");

        switch (audioInputs.size()) {
            case 0 -> cmd.add("-an");
            case 1 -> {
                cmd.add("-map");
                cmd.add("1:a");
            }
            default -> {
                // Mix mic and system sound into one track. normalize=0 keeps the
                // original loudness instead of halving each source.
                cmd.add("-filter_complex");
                cmd.add("[1:a][2:a]amix=inputs=2:duration=longest:dropout_transition=2:normalize=0[aout]");
                cmd.add("-map");
                cmd.add("[aout]");
            }
        }

        cmd.addAll(videoEncoderArgs(config));

        if (!audioInputs.isEmpty()) {
            cmd.add("-c:a");
            cmd.add("aac");
            cmd.add("-b:a");
            cmd.add(config.audioBitrateKbps() + "k");
            cmd.add("-ar");
            cmd.add("48000");
            cmd.add("-ac");
            cmd.add("2");
        }

        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add("-f");
        cmd.add("mp4");
        cmd.add(config.outputPath().toAbsolutePath().toString());
        return cmd;
    }

    /**
     * Quality knobs differ per encoder: CRF only exists on x264/x265, hardware
     * encoders each invented their own name for the same idea.
     */
    private static List<String> videoEncoderArgs(RecordingConfig config) {
        List<String> args = new ArrayList<>();
        String codec = config.videoCodec();
        args.add("-c:v");
        args.add(codec);

        switch (codec) {
            case "libx264", "libx265" -> {
                args.add("-preset");
                args.add(config.preset());
                args.add("-crf");
                args.add(String.valueOf(config.crf()));
            }
            case "h264_nvenc" -> {
                args.add("-preset");
                args.add("p5");
                args.add("-rc");
                args.add("vbr");
                args.add("-cq");
                args.add(String.valueOf(config.crf()));
                args.add("-b:v");
                args.add("0");
            }
            case "h264_qsv" -> {
                args.add("-global_quality");
                args.add(String.valueOf(config.crf()));
            }
            case "h264_amf" -> {
                args.add("-rc");
                args.add("cqp");
                args.add("-qp_i");
                args.add(String.valueOf(config.crf()));
                args.add("-qp_p");
                args.add(String.valueOf(config.crf()));
            }
            default -> {
                // Let FFmpeg apply the encoder's own defaults.
            }
        }

        args.add("-pix_fmt");
        args.add("yuv420p");
        // Keyframe every two seconds keeps seeking responsive in players.
        args.add("-g");
        args.add(String.valueOf(config.fps() * 2));
        return args;
    }

    /** Shell-quoted rendering, for logs and bug reports. */
    public static String toDisplayString(List<String> cmd) {
        StringBuilder sb = new StringBuilder();
        for (String part : cmd) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (part.contains(" ") || part.contains("\"")) {
                sb.append('"').append(part.replace("\"", "\\\"")).append('"');
            } else {
                sb.append(part);
            }
        }
        return sb.toString();
    }
}
