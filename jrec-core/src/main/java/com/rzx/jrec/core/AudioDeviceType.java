package com.rzx.jrec.core;

/** Distinguishes a real microphone from a loopback device that carries system playback. */
public enum AudioDeviceType {
    /** A physical or virtual microphone. */
    MICROPHONE,
    /**
     * A loopback device: it captures whatever the machine is <em>playing</em>.
     * On Windows this is typically "Stereo Mix" or a virtual cable driver.
     */
    SYSTEM_LOOPBACK
}
