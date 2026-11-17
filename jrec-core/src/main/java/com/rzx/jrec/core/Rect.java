package com.rzx.jrec.core;

/**
 * A rectangle in <em>physical</em> pixels, relative to the virtual desktop origin.
 * <p>
 * On a HiDPI display the logical coordinates reported by the UI toolkit must be
 * multiplied by the output scale before they reach this class, because FFmpeg's
 * capture backends all work in device pixels.
 */
public record Rect(int x, int y, int width, int height) {

    public Rect {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be positive: " + width + "x" + height);
        }
    }

    /**
     * H.264 requires both dimensions to be even. Cropping by one pixel is visually
     * invisible and far cheaper than having the encoder reject the frame size.
     */
    public Rect toEven() {
        int w = width - (width % 2);
        int h = height - (height % 2);
        return (w == width && h == height) ? this : new Rect(x, y, w, h);
    }

    /** FFmpeg's {@code video_size} syntax, e.g. {@code 1280x720}. */
    public String toVideoSize() {
        return width + "x" + height;
    }

    @Override
    public String toString() {
        return width + "x" + height + "@" + x + "," + y;
    }
}
