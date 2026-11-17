package com.rzx.jrec.core;

/** Lifecycle of a {@link Recorder}. */
public enum RecorderState {
    IDLE,
    STARTING,
    RECORDING,
    STOPPING,
    FAILED;

    public boolean isBusy() {
        return this == STARTING || this == RECORDING || this == STOPPING;
    }
}
