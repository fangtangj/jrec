package com.rzx.jrec.core.backend;

import com.rzx.jrec.core.AudioDevice;
import com.rzx.jrec.core.RecordingConfig;

import java.util.List;

/**
 * Platform-specific knowledge about how to address the screen and the audio
 * devices from FFmpeg. Everything that differs between Windows, Linux and macOS
 * lives behind this interface so the recorder core stays platform-agnostic.
 */
public interface CaptureBackend {

    /** Stable identifier, e.g. {@code windows}. */
    String id();

    /** True when the platform exposes a loopback device that carries system playback. */
    boolean supportsSystemAudio();

    /**
     * Arguments that configure and open the screen input, ending with
     * {@code -i <source>}. Region cropping is done here where the backend supports
     * it natively (gdigrab, x11grab).
     */
    List<String> videoInputArgs(RecordingConfig config);

    /** Arguments that configure and open one audio input, ending with {@code -i <device>}. */
    List<String> audioInputArgs(AudioDevice device);

    /**
     * A video filter required to realise the region, or {@code null} when the
     * backend already cropped at capture time.
     */
    default String videoFilter(RecordingConfig config) {
        return null;
    }

    /**
     * Actionable guidance shown to the user when no loopback device was found.
     * The usual cause is a disabled or missing virtual audio device, not a bug.
     */
    String systemAudioHint();
}
