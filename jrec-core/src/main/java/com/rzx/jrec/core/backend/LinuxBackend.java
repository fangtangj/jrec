package com.rzx.jrec.core.backend;

import com.rzx.jrec.core.AudioDevice;
import com.rzx.jrec.core.Rect;
import com.rzx.jrec.core.RecordingConfig;
import com.rzx.jrec.core.RecordingMode;

import java.util.ArrayList;
import java.util.List;

/**
 * Linux capture via {@code x11grab} and {@code pulse}.
 * <p>
 * PulseAudio/PipeWire exposes every playback sink as a {@code .monitor} source, so
 * system audio is genuinely available here — no virtual driver needed, unlike Windows.
 */
public final class LinuxBackend implements CaptureBackend {

    @Override
    public String id() {
        return "linux";
    }

    @Override
    public boolean supportsSystemAudio() {
        return true;
    }

    @Override
    public List<String> videoInputArgs(RecordingConfig config) {
        List<String> args = new ArrayList<>(List.of(
                "-f", "x11grab",
                "-thread_queue_size", "1024",
                "-framerate", String.valueOf(config.fps()),
                "-draw_mouse", config.drawMouse() ? "1" : "0"
        ));
        String display = System.getenv().getOrDefault("DISPLAY", ":0.0");
        if (config.mode() == RecordingMode.REGION) {
            Rect r = config.region().toEven();
            args.add("-video_size");
            args.add(r.toVideoSize());
            args.add("-i");
            args.add(display + "+" + r.x() + "," + r.y());
        } else {
            args.add("-i");
            args.add(display);
        }
        return args;
    }

    @Override
    public List<String> audioInputArgs(AudioDevice device) {
        return List.of(
                "-f", "pulse",
                "-thread_queue_size", "1024",
                "-i", device.name()
        );
    }

    @Override
    public String systemAudioHint() {
        return """
                用 `pactl list sources short` 查看可用音源，形如
                alsa_output.pci-0000_00_1f.3.analog-stereo.monitor 的就是系统声音。
                纯 ALSA 环境（无 PulseAudio/PipeWire）需要在录音设置里手动选设备。""";
    }
}
