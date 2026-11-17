package com.rzx.jrec.core.backend;

import com.rzx.jrec.core.AudioDevice;
import com.rzx.jrec.core.Rect;
import com.rzx.jrec.core.RecordingConfig;
import com.rzx.jrec.core.RecordingMode;

import java.util.ArrayList;
import java.util.List;

/**
 * macOS capture via {@code avfoundation}.
 * <p>
 * Two platform quirks are handled here:
 * <ul>
 *   <li>avfoundation identifies screens by <em>index</em>, not by name, and the index
 *       shifts when cameras are plugged in. Override with {@code -Djrec.macos.screenIndex=N}.</li>
 *   <li>avfoundation cannot crop, so the region is applied as a {@code crop} filter —
 *       the full screen is still read and encoded-then-discarded, which costs CPU.
 *       This is why region recording is cheaper on Windows/Linux than on macOS.</li>
 * </ul>
 */
public final class MacBackend implements CaptureBackend {

    private static final int DEFAULT_SCREEN_INDEX = 1;

    @Override
    public String id() {
        return "macos";
    }

    @Override
    public boolean supportsSystemAudio() {
        // System playback is not exposed to avfoundation without a kernel-level driver
        // such as BlackHole. Reported as unsupported so the UI does not promise it.
        return false;
    }

    @Override
    public List<String> videoInputArgs(RecordingConfig config) {
        int index = Integer.getInteger("jrec.macos.screenIndex", DEFAULT_SCREEN_INDEX);
        List<String> args = new ArrayList<>(List.of(
                "-f", "avfoundation",
                "-thread_queue_size", "1024",
                "-framerate", String.valueOf(config.fps()),
                "-capture_cursor", config.drawMouse() ? "1" : "0",
                "-capture_mouse_clicks", "1",
                "-i", index + ":none"
        ));
        return args;
    }

    @Override
    public List<String> audioInputArgs(AudioDevice device) {
        // Audio-only avfoundation input uses the "<video>:<audio>" syntax with no video part.
        return List.of(
                "-f", "avfoundation",
                "-thread_queue_size", "1024",
                "-i", "none:" + device.name()
        );
    }

    @Override
    public String videoFilter(RecordingConfig config) {
        if (config.mode() != RecordingMode.REGION) {
            return null;
        }
        Rect r = config.region().toEven();
        return "crop=" + r.width() + ":" + r.height() + ":" + r.x() + ":" + r.y();
    }

    @Override
    public String systemAudioHint() {
        return """
                macOS 默认不允许应用录制系统播放的声音。需要安装 BlackHole 或 Soundflower
                这类虚拟声卡，并把系统输出同时送到它，之后在设备列表里选择它作为音源。""";
    }
}
