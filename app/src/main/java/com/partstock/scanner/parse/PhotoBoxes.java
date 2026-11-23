package com.partstock.scanner.parse;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The OCR boxes drawn over the review screen's photo: boxes kept in a size-independent form, which line each field's
 * value came from, the {@code ContentScale.Fit} math to place them over the image, and hit-testing a tap. Pure Java so
 * it is unit tested.
 */
public final class PhotoBoxes {
    private PhotoBoxes() {}

    /** Boxes are stored as fractions of the photo's width and height, times this (so any resized copy fits). */
    public static final int SCALE = 10000;

    /** Order fields are matched in: a longer value that contains a shorter one goes first (DigiKey PN has the MPN). */
    private static final FieldReader.Field[] ORDER = {
        FieldReader.Field.DISTRIBUTOR_PN, FieldReader.Field.MPN, FieldReader.Field.CUSTOMER_PN,
        FieldReader.Field.DESCRIPTION, FieldReader.Field.QUANTITY,
    };

    private static final Pattern QTY_WORD = Pattern.compile("(?i)\\b(QTY|QUANTITY|QUAN)\\b");

    /**
     * [lines] from a photo [width] × [height] px (upright) as size-independent boxes. Empty for a missing size.
     */
    public static List<OcrLine> normalize(List<OcrLine> lines, int width, int height) {
        List<OcrLine> out = new ArrayList<>();
        if (lines == null || width <= 0 || height <= 0) return out;
        for (OcrLine l : lines) {
            out.add(new OcrLine(l.text,
                    clamp(Math.round(l.left * (float) SCALE / width)), clamp(Math.round(l.top * (float) SCALE / height)),
                    clamp(Math.round(l.right * (float) SCALE / width)), clamp(Math.round(l.bottom * (float) SCALE / height))));
        }
        return out;
    }

    private static int clamp(int v) { return Math.max(0, Math.min(SCALE, v)); }

    /**
     * True when boxes read on an image [srcW] × [srcH] can be drawn on a photo [photoW] × [photoH]: both upright the same
     * way (a photo that came out sideways, e.g. EXIF not applied, would put the boxes in the wrong places).
     */
    public static boolean sameOrientation(int srcW, int srcH, int photoW, int photoH) {
        if (srcW <= 0 || srcH <= 0 || photoW <= 0 || photoH <= 0) return false;
        double a = (double) srcW / srcH, b = (double) photoW / photoH;
        return Math.abs(a - b) / Math.max(a, b) < 0.05;
    }

    /**
     * Where an image [imgW] × [imgH] lands inside a [boxW] × [boxH] area with ContentScale.Fit, centred:
     * {left, top, width, height}.
     */
    public static double[] fit(double imgW, double imgH, double boxW, double boxH) {
        if (imgW <= 0 || imgH <= 0 || boxW <= 0 || boxH <= 0) return new double[] {0, 0, 0, 0};
        double s = Math.min(boxW / imgW, boxH / imgH);
        double w = imgW * s, h = imgH * s;
        return new double[] {(boxW - w) / 2, (boxH - h) / 2, w, h};
    }

    /**
     * Which line each field's current value was read from: field → indexes into [lines] (several for a description
     * that wraps). A line goes to one field at most; fields whose value isn't on any line are left out.
     */
    public static Map<FieldReader.Field, List<Integer>> sources(List<OcrLine> lines, Map<FieldReader.Field, String> values) {
        Map<FieldReader.Field, List<Integer>> out = new EnumMap<>(FieldReader.Field.class);
        if (lines == null || values == null) return out;
        Set<Integer> taken = new HashSet<>();
        List<String> squashed = new ArrayList<>();
        for (OcrLine l : lines) squashed.add(squash(l.text));
        for (FieldReader.Field f : ORDER) {
            String raw = values.get(f);
            if (raw == null || raw.trim().isEmpty()) continue;
            List<Integer> hits = f == FieldReader.Field.QUANTITY ? quantityLine(lines, raw, taken)
                    : f == FieldReader.Field.DESCRIPTION ? descriptionLines(lines, squashed, squash(raw), taken)
                    : pnLine(squashed, squash(raw), f, values, taken);
            if (!hits.isEmpty()) {
                out.put(f, hits);
                taken.addAll(hits);
            }
        }
        return out;
    }

    /** The line holding the part number with the least other text, skipping lines that hold a longer PN containing it. */
    private static List<Integer> pnLine(List<String> squashed, String v, FieldReader.Field f,
                                        Map<FieldReader.Field, String> values, Set<Integer> taken) {
        List<Integer> out = new ArrayList<>();
        if (v.length() < 3) return out;
        int best = -1, bestExtra = Integer.MAX_VALUE;
        for (int i = 0; i < squashed.size(); i++) {
            String s = squashed.get(i);
            if (taken.contains(i) || !s.contains(v)) continue;
            if (hasLongerContaining(s, v, f, values)) continue;
            int extra = s.length() - v.length();
            if (extra < bestExtra) {
                best = i;
                bestExtra = extra;
            }
        }
        if (best >= 0) out.add(best);
        return out;
    }

    private static boolean hasLongerContaining(String line, String v, FieldReader.Field f, Map<FieldReader.Field, String> values) {
        for (Map.Entry<FieldReader.Field, String> e : values.entrySet()) {
            if (e.getKey() == f || e.getValue() == null) continue;
            String w = squash(e.getValue());
            if (w.length() > v.length() && w.contains(v) && line.contains(w)) return true;
        }
        return false;
    }

    /** Lines that are part of the description (it may wrap onto several lines), or the one line containing all of it. */
    private static List<Integer> descriptionLines(List<OcrLine> lines, List<String> squashed, String v, Set<Integer> taken) {
        List<Integer> out = new ArrayList<>();
        if (v.length() < 4) return out;
        for (int i = 0; i < squashed.size(); i++) {
            if (taken.contains(i)) continue;
            String s = squashed.get(i);
            String body = squash(FieldReader.stripLabels(lines.get(i).text));
            if (s.contains(v) || (body.length() >= 4 && v.contains(body))) out.add(i);
        }
        return out;
    }

    /**
     * The line with the quantity as a whole number: one that says QTY / Quantity first, else a line that is only that
     * number (a bare number elsewhere on the label is too likely to be something else).
     */
    private static List<Integer> quantityLine(List<OcrLine> lines, String raw, Set<Integer> taken) {
        List<Integer> out = new ArrayList<>();
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return out;
        Pattern number = Pattern.compile("(?<![0-9.,])" + numberPattern(digits) + "(?![0-9])");
        int bare = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (taken.contains(i)) continue;
            String t = lines.get(i).text;
            Matcher m = number.matcher(t);
            if (!m.find()) continue;
            if (QTY_WORD.matcher(t).find()) {
                out.add(i);
                return out;
            }
            if (bare < 0 && t.replaceAll("(?i)[\\s,]|PCS?|EA", "").equals(digits)) bare = i;
        }
        if (bare >= 0) out.add(bare);
        return out;
    }

    /** "1000" also matches "1,000". */
    private static String numberPattern(String digits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) sb.append(",?");
            sb.append(digits.charAt(i));
        }
        return sb.toString();
    }

    /**
     * The box under a tap at ([x], [y]) in box units: the one containing it (the smallest, where boxes overlap), else
     * the nearest within [slop]. -1 when none is close.
     */
    public static int hit(List<OcrLine> boxes, double x, double y, double slop) {
        if (boxes == null) return -1;
        int best = -1;
        double bestDist = Double.MAX_VALUE, bestArea = Double.MAX_VALUE;
        for (int i = 0; i < boxes.size(); i++) {
            OcrLine b = boxes.get(i);
            double dx = Math.max(Math.max(b.left - x, 0), x - b.right);
            double dy = Math.max(Math.max(b.top - y, 0), y - b.bottom);
            double d = Math.sqrt(dx * dx + dy * dy);
            if (d > slop) continue;
            double area = (double) Math.max(1, b.right - b.left) * Math.max(1, b.bottom - b.top);
            if (d < bestDist || (d == bestDist && area < bestArea)) {
                best = i;
                bestDist = d;
                bestArea = area;
            }
        }
        return best;
    }

    /** Short tag drawn on a box ("MPN", "Qty"), so the mapping isn't shown by color alone. */
    public static String tag(FieldReader.Field f) {
        switch (f) {
            case MPN: return "MPN";
            case DISTRIBUTOR_PN: return "Dist PN";
            case CUSTOMER_PN: return "Cust PN";
            case QUANTITY: return "Qty";
            default: return "Desc";
        }
    }

    static String squash(String s) {
        return s == null ? "" : s.toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
    }
}
