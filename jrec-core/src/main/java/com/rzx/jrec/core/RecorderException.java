package com.rzx.jrec.core;

/** A recording failure with a message safe to show to the end user. */
public class RecorderException extends RuntimeException {

    public RecorderException(String message) {
        super(message);
    }

    public RecorderException(String message, Throwable cause) {
        super(message, cause);
    }
}
