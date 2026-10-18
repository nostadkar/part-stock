package com.partstock.scanner.parse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one field from a close-up photo of part of a label (the review screen's per-field rescan). The text is
 * cleaned with the same rules as the label parser: field names are dropped, split part numbers are joined, OCR
 * letter/digit slips in quantities are fixed. Pure Java so it is unit tested.
 */
public final class FieldReader {
    private FieldReader() {}

    public enum Field { MPN, DISTRIBUTOR_PN, CUSTOMER_PN, QUANTITY, DESCRIPTION }

    private static final int CI = Pattern.CASE_INSENSITIVE;
    /** Field names printed before a value ("Mfg P/N:", "Cust Ref", "QTY", "Desc:"), dropped from the start. */
    private static final Pattern LABEL = Pattern.compile(
            "^[^A-Z0-9]*(?:"
                    + "(?:(?:MFG|MFR|MANUFACTURER|MOUSER|DIGI-?KEY|CUST(?:OMER|\\.)?|ALT\\.?)\\s*)?"
                    + "(?:PART\\s*(?:NUMBER|NO\\.?|#)|P\\s?/\\s?N|PN|REF(?:ERENCE|\\.)?)"
                    + "|CUSTOMER|COMPONENT|QUANTITY|QTY|(?:PART\\s*)?DESC(?:RIPTION)?|COMMENT"
                    + ")(?=[\\s:;.#=]|$)[\\s:;.#=]*",
            CI);

    /** Lines whose centre is inside the box, in reading order. */
    public static List<OcrLine> inBox(List<OcrLine> lines, double left, double top, double right, double bottom) {
        List<OcrLine> out = new ArrayList<>();
        if (lines == null) return out;
        for (OcrLine l : lines) {
            double cx = (l.left + l.right) / 2.0, cy = l.centerY();
            if (cx >= left && cx <= right && cy >= top && cy <= bottom) out.add(l);
        }
        return out;
    }

    /** The field's value, or null when nothing usable was read. */
    public static String read(Field field, List<OcrLine> lines) {
        String text = readingOrder(lines);
        if (text.isEmpty()) return null;
        switch (field) {
            case QUANTITY:
                return quantity(text);
            case DESCRIPTION: {
                String t = stripLabels(text).replaceAll("\\s+", " ").trim();
                return t.length() >= 2 && t.matches(".*[A-Za-z0-9].*") ? t : null;
            }
            case CUSTOMER_PN: {
                String t = stripLabels(text);
                if (t.isEmpty()) return null;
                String pn = LabelParser.normalizeCustPn(t);
                return pn != null && pn.length() >= 3 ? pn : null;
            }
            default: {
                String t = stripLabels(text);
                if (t.isEmpty()) return null;
                String pn = LabelParser.joinPnFragments(t);
                return pn != null && pn.length() >= 3 && pn.matches(".*\\d.*") ? pn : null;
            }
        }
    }

    static String stripLabels(String s) {
        String t = s.trim();
        for (int i = 0; i < 3; i++) {
            Matcher m = LABEL.matcher(t);
            if (!m.find() || m.end() == 0) break;
            t = t.substring(m.end()).trim();
        }
        return t;
    }

    private static String quantity(String text) {
        String t = stripLabels(text);
        for (String tok : t.split("\\s+")) {
            String fixed = LabelParser.fixDigits(tok).replaceFirst("(?i)(PCS|PC|EA|X)$", "");
            if (fixed.matches("[0-9][0-9,]*")) {
                Integer n = LabelParser.toInt(fixed);
                if (n != null) return n.toString();
            }
        }
        return null;
    }

    /** Lines grouped into rows (by vertical overlap), rows top to bottom, each row left to right, joined by spaces. */
    static String readingOrder(List<OcrLine> lines) {
        if (lines == null || lines.isEmpty()) return "";
        List<OcrLine> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingDouble(OcrLine::centerY));
        List<List<OcrLine>> rows = new ArrayList<>();
        for (OcrLine l : sorted) {
            List<OcrLine> last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
            if (last != null && l.verticalOverlap(last.get(0)) > Math.min(l.height(), last.get(0).height()) / 2) {
                last.add(l);
            } else {
                List<OcrLine> row = new ArrayList<>();
                row.add(l);
                rows.add(row);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (List<OcrLine> row : rows) {
            row.sort(Comparator.comparingInt(o -> o.left));
            for (OcrLine l : row) {
                if (l.text.isEmpty()) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(l.text);
            }
        }
        return sb.toString().trim();
    }

    /** Short name for messages ("quantity", "MPN"). */
    public static String label(Field f) {
        switch (f) {
            case MPN: return "MPN";
            case DISTRIBUTOR_PN: return "distributor PN";
            case CUSTOMER_PN: return "customer PN";
            case QUANTITY: return "quantity";
            default: return "description";
        }
    }
}
