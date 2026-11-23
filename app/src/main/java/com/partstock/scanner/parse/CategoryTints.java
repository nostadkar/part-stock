package com.partstock.scanner.parse;

/**
 * Stable per-category tints and the contrast math that keeps text and icons readable on them. Colors are ARGB ints so
 * this stays pure Java and unit tested; the Compose side ({@code CategoryTint.kt}) picks the palette and calls in.
 */
public final class CategoryTints {
    private CategoryTints() {}

    /** A stable slot for [category]: its place in {@link Categories#ALL}, or a hash for unknown names. */
    public static int index(String category) {
        if (category == null) category = "";
        int i = Categories.ALL.indexOf(category);
        if (i >= 0) return i;
        int h = 0;
        for (int k = 0; k < category.length(); k++) h = h * 31 + category.charAt(k);
        return Categories.ALL.size() + Math.floorMod(h, 997);
    }

    /** The color in [palette] for [category]; the same category always gets the same color. */
    public static int pick(int[] palette, String category) {
        return palette[Math.floorMod(index(category), palette.length)];
    }

    /**
     * Wallpaper-color mode: [primary] turned around the hue wheel by the golden angle per category slot, keeping its
     * saturation and lightness so the tints belong to the theme.
     */
    public static int derived(int primary, String category) {
        return rotateHue(primary, (float) ((index(category) * 137.508) % 360.0));
    }

    public static int rotateHue(int argb, float degrees) {
        float[] hsl = toHsl(argb);
        float h = (hsl[0] + degrees) % 360f;
        if (h < 0) h += 360f;
        // Very grey primaries would give grey tints; give them a little color.
        float s = Math.max(hsl[1], 0.35f);
        return fromHsl(h, s, hsl[2], (argb >>> 24) & 0xFF);
    }

    /** [a] moved towards [b] by [t] (0 = a, 1 = b), per channel. */
    public static int mix(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int ca = (a >>> shift) & 0xFF, cb = (b >>> shift) & 0xFF;
            int c = (int) Math.round(ca + (cb - ca) * t);
            out |= (c & 0xFF) << shift;
        }
        return out;
    }

    /** WCAG relative luminance, 0 (black) to 1 (white). */
    public static double luminance(int argb) {
        return 0.2126 * lin((argb >> 16) & 0xFF) + 0.7152 * lin((argb >> 8) & 0xFF) + 0.0722 * lin(argb & 0xFF);
    }

    /** WCAG contrast ratio between two opaque colors, 1 to 21. */
    public static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** [fg], moved towards [toward] in small steps until it has at least [min] contrast on [bg]. */
    public static int readable(int fg, int bg, int toward, double min) {
        for (int step = 0; step <= 10; step++) {
            int c = mix(fg, toward, step / 10.0);
            if (contrast(c, bg) >= min) return c;
        }
        return toward;
    }

    /**
     * Colors for a no-photo tile: { background, icon }. The background is [accent] washed into [surface]; the icon is
     * the accent pulled towards [onSurface] until it has 4.5:1 on the background (also used for text on it).
     */
    public static int[] tile(int accent, int surface, int onSurface) {
        int bg = mix(surface, accent, 0.30) | 0xFF000000;
        int fg = readable(mix(accent, onSurface, 0.35) | 0xFF000000, bg, onSurface | 0xFF000000, 4.5);
        return new int[] {bg, fg};
    }

    private static double lin(int c) {
        double s = c / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    private static float[] toHsl(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255f, g = ((argb >> 8) & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float l = (max + min) / 2f, d = max - min;
        float h = 0f, s = 0f;
        if (d > 0f) {
            s = d / (1f - Math.abs(2f * l - 1f));
            if (max == r) h = 60f * (((g - b) / d) % 6f);
            else if (max == g) h = 60f * ((b - r) / d + 2f);
            else h = 60f * ((r - g) / d + 4f);
            if (h < 0) h += 360f;
        }
        return new float[] {h, Math.min(1f, s), l};
    }

    private static int fromHsl(float h, float s, float l, int alpha) {
        float c = (1f - Math.abs(2f * l - 1f)) * s;
        float x = c * (1f - Math.abs((h / 60f) % 2f - 1f));
        float m = l - c / 2f;
        float r, g, b;
        if (h < 60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        int ri = Math.round((r + m) * 255f), gi = Math.round((g + m) * 255f), bi = Math.round((b + m) * 255f);
        return (alpha << 24) | (clamp(ri) << 16) | (clamp(gi) << 8) | clamp(bi);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
