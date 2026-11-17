package com.rzx.jrec.core;

/**
 * An audio capture device as reported by the platform backend.
 *
 * @param name  the exact token FFmpeg must receive to open this device: a device
 *              name on Windows, a PulseAudio source name on Linux, an index on macOS
 * @param type  microphone or system loopback
 * @param label what to show a human; differs from {@code name} on macOS, where the
 *              device is addressed by index but should be presented by its real name
 */
public record AudioDevice(String name, AudioDeviceType type, String label) {

    public AudioDevice {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("device name must not be blank");
        }
        if (label == null || label.isBlank()) {
            label = name;
        }
    }

    /** Convenience constructor for platforms where the addressable id is readable. */
    public AudioDevice(String name, AudioDeviceType type) {
        this(name, type, name);
    }

    public boolean isLoopback() {
        return type == AudioDeviceType.SYSTEM_LOOPBACK;
    }

    @Override
    public String toString() {
        return label;
    }
}
