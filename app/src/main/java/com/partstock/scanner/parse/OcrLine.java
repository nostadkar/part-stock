package com.partstock.scanner.parse;

/** One line of recognized text with its bounding box in upright image coordinates. */
public final class OcrLine {
    public final String text;
    public final int left, top, right, bottom;

    public OcrLine(String text, int left, int top, int right, int bottom) {
        this.text = text == null ? "" : text.trim();
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public int height() { return Math.max(1, bottom - top); }

    public int width() { return Math.max(1, right - left); }

    public double centerY() { return (top + bottom) / 2.0; }

    /** Vertical overlap with another line, in pixels (negative when apart). */
    public int verticalOverlap(OcrLine o) {
        return Math.min(bottom, o.bottom) - Math.max(top, o.top);
    }

    @Override
    public String toString() {
        return text + " [" + left + "," + top + "," + right + "," + bottom + "]";
    }
}
