package com.partstock.scanner.parse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Combines several reads of the same label (a short burst of photos) into one result.
 * OCR slips differ from frame to frame (0/O, 1/I, a dropped character), so each field takes the
 * value most frames agree on. When no value wins, the most complete frame's value is kept.
 */
public final class ScanMerger {
    private ScanMerger() {}

    public static ParsedPart merge(List<ParsedPart> reads) {
        List<ParsedPart> parts = new ArrayList<>();
        if (reads != null) for (ParsedPart p : reads) if (p != null && !p.rawText.trim().isEmpty()) parts.add(p);
        if (parts.isEmpty()) return reads == null || reads.isEmpty() || reads.get(0) == null ? new ParsedPart() : reads.get(0);
        if (parts.size() == 1) return parts.get(0);

        // Frames that found the most label types are the reference (e.g. "Mouser + Entrust kit" beats "Mouser").
        String type = vote(parts, p -> ParsedPart.UNKNOWN.equals(p.labelType) ? null : p.labelType, null);
        List<ParsedPart> same = new ArrayList<>();
        for (ParsedPart p : parts) if (type == null || type.equals(p.labelType)) same.add(p);
        if (same.isEmpty()) same = parts;

        ParsedPart best = same.get(0);
        for (ParsedPart p : same) if (score(p) > score(best)) best = p;

        ParsedPart out = new ParsedPart();
        out.labelType = best.labelType;
        out.rawText = best.rawText;
        out.notes = best.notes;
        out.distributor = vote(same, p -> p.distributor, best.distributor);

        // MPN and its "derived" flag travel together.
        out.mpn = vote(same, p -> p.mpn, best.mpn);
        out.mpnDerived = best.mpnDerived;
        for (ParsedPart p : same) {
            if (Objects.equals(p.mpn, out.mpn)) {
                out.mpnDerived = p.mpnDerived;
                if (!p.mpnDerived) break;
            }
        }
        out.distributorPn = vote(same, p -> p.distributorPn, best.distributorPn);
        out.customerPn = vote(same, p -> p.customerPn, best.customerPn);
        out.description = vote(same, p -> p.description, best.description);
        out.manufacturer = vote(same, p -> p.manufacturer, best.manufacturer);
        out.quantity = vote(same, p -> p.quantity, best.quantity);
        out.kitQuantity = vote(same, p -> p.kitQuantity, best.kitQuantity);
        out.kitPerBoard = vote(same, p -> p.kitPerBoard, best.kitPerBoard);
        out.dateCode = vote(same, p -> p.dateCode, best.dateCode);

        // The ref des list and its count travel together.
        out.refDes = vote(same, p -> p.refDes, best.refDes);
        out.refDesCount = best.refDesCount;
        for (ParsedPart p : same) {
            if (Objects.equals(p.refDes, out.refDes)) {
                out.refDesCount = p.refDesCount;
                break;
            }
        }
        return out;
    }

    /**
     * Most common non-blank value. A value seen in two or more frames wins outright; otherwise the
     * fallback (the most complete frame's value) is kept, or the first value found if it has none.
     */
    static <T> T vote(List<ParsedPart> parts, Function<ParsedPart, T> field, T fallback) {
        Map<T, Integer> counts = new LinkedHashMap<>();
        for (ParsedPart p : parts) {
            T v = field.apply(p);
            if (v == null || (v instanceof String && ((String) v).trim().isEmpty())) continue;
            counts.merge(v, 1, Integer::sum);
        }
        if (counts.isEmpty()) return fallback;
        T top = null;
        int topCount = 0;
        for (Map.Entry<T, Integer> e : counts.entrySet()) {
            int c = e.getValue();
            if (c > topCount || (c == topCount && e.getKey().equals(fallback))) {
                top = e.getKey();
                topCount = c;
            }
        }
        if (topCount >= 2) return top;
        return fallback != null && !(fallback instanceof String && ((String) fallback).trim().isEmpty())
                ? fallback : counts.keySet().iterator().next();
    }

    /** How much of the label a read got: filled fields, plus how much text it found. */
    static int score(ParsedPart p) {
        int s = 0;
        if (ParsedPart.notBlank(p.mpn)) s += 3;
        if (ParsedPart.notBlank(p.distributorPn)) s += 3;
        if (ParsedPart.notBlank(p.customerPn)) s += 2;
        if (p.quantity != null) s += 2;
        if (ParsedPart.notBlank(p.description)) s += 2;
        if (ParsedPart.notBlank(p.manufacturer)) s += 1;
        if (ParsedPart.notBlank(p.dateCode)) s += 1;
        if (p.kitQuantity != null) s += 1;
        if (p.kitPerBoard != null) s += 1;
        if (p.refDesCount > 0) s += 1;
        return s * 1000 + Math.min(999, p.rawText.length() / 10);
    }
}
