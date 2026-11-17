package com.rzx.jrec.core.backend;

import com.rzx.jrec.core.AudioDevice;
import com.rzx.jrec.core.Rect;
import com.rzx.jrec.core.RecordingConfig;
import com.rzx.jrec.core.RecordingMode;

import java.util.ArrayList;
import java.util.List;

/**
 * Windows capture via {@code gdigrab} and {@code dshow}.
 * <p>
 * {@code gdigrab} can crop at capture time, so region recording costs nothing
 * extra: {@code -offset_x/-offset_y/-video_size} tell the grabber to read only the
 * rectangle we care about, and the encoder never sees the rest of the desktop.
 */
public final class WindowsBackend implements CaptureBackend {

    @Override
    public String id() {
        return "windows";
    }

    @Override
    public boolean supportsSystemAudio() {
        // Technically true, but only when a loopback device exists (Stereo Mix or a
        // virtual cable). Presence is decided by AudioDevices scanning, not here.
        return true;
    }

    @Override
    public List<String> videoInputArgs(RecordingConfig config) {
        List<String> args = new ArrayList<>(List.of(
                "-f", "gdigrab",
                "-thread_queue_size", "1024",
                "-framerate", String.valueOf(config.fps()),
                "-draw_mouse", config.drawMouse() ? "1" : "0"
        ));
        if (config.mode() == RecordingMode.REGION) {
            Rect r = config.region().toEven();
            args.add("-offset_x");
            args.add(String.valueOf(r.x()));
            args.add("-offset_y");
            args.add(String.valueOf(r.y()));
            args.add("-video_size");
            args.add(r.toVideoSize());
        }
        args.add("-i");
        args.add("desktop");
        return args;
    }

    @Override
    public List<String> audioInputArgs(AudioDevice device) {
        // Deliberately unquoted. ProcessBuilder hands each element to CreateProcess as its
        // own argv entry, so no shell ever sees this string; wrapping the name in quotes
        // makes FFmpeg treat the quote characters as part of the device name and open
        // fails with "I/O error". Verified against a device named "麦克风 (ToDesk Virtual Audio)".
        return List.of(
                "-f", "dshow",
                "-thread_queue_size", "1024",
                "-i", "audio=" + device.name()
        );
    }

    @Override
    public String systemAudioHint() {
        return """
                Windows 不会默认开放系统声音的录制通道，需要满足其中一条：
                  1. 在「声音设置 → 更多声音设置 → 录制」中右键空白处，勾选「显示已禁用的设备」，
                     再启用「立体声混音 / Stereo Mix」；
                  2. 或者安装虚拟声卡（VB-Audio Virtual Cable、VoiceMeeter 等），
                     并把系统播放输出指向它。
                找不到以上任一设备时，只能录制麦克风。""";
    }
}
