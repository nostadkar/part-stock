package com.partstock.scanner.parse;

import java.util.List;
import java.util.Locale;

/**
 * What the review screen says about one field: read and confirmed by the label's barcode, looked up on Mouser or
 * DigiKey, worth a second look, or edited by hand. Only describes the value; it never changes what is saved.
 */
public final class FieldStatus {
    public enum Kind {
        /** Printed text and barcode agree, or the barcode filled a field the text didn't show. */
        CONFIRMED,
        /** Filled in by a Mouser / DigiKey lookup. */
        LOOKED_UP,
        /** Worth checking: worked out from another number, or the barcode and printed text differ. */
        CHECK,
        /** Typed in by the user. */
        EDITED,
        /** Read from the label text with nothing to cross-check it against, or blank. */
        PLAIN,
    }

    /** Where the current value came from when it wasn't the scan: a lookup ("Mouser", "DigiKey") or a re-read. */
    public static final String REREAD = "close-up";

    public final Kind kind;
    /** Short line shown under the field; null when there is nothing to say. */
    public final String text;

    private FieldStatus(Kind kind, String text) {
        this.kind = kind;
        this.text = text;
    }

    public static final FieldStatus NONE = new FieldStatus(Kind.PLAIN, null);

    /**
     * @param field     one of the {@link LabelBarcode} F_* names ({@code mpn}, {@code quantity}...) or another key
     * @param p         the scan, after {@link LabelBarcode#apply}
     * @param current   what the field holds now
     * @param initial   what it held when the review screen opened
     * @param setValue  value last put there by a lookup or re-read (null if none)
     * @param setBy     who set it: "Mouser", "DigiKey" or {@link #REREAD}
     */
    public static FieldStatus of(String field, ParsedPart p, String current, String initial, String setValue,
                                 String setBy) {
        String cur = norm(current);
        if (cur.isEmpty()) return NONE;
        if (setValue != null && cur.equals(norm(setValue))) {
            if (REREAD.equals(setBy)) return new FieldStatus(Kind.PLAIN, "Re-read from a close-up photo");
            return new FieldStatus(Kind.LOOKED_UP, "Looked up on " + setBy);
        }
        if (!cur.equals(norm(initial))) return new FieldStatus(Kind.EDITED, "Edited by you");
        if (p == null) return NONE;
        if (LabelBarcode.F_MPN.equals(field) && p.mpnDerived) {
            return new FieldStatus(Kind.CHECK, "Worked out from the " + (p.distributor == null ? "distributor" : p.distributor)
                    + " P/N — check it");
        }
        String before = p.ocrBeforeBarcode.get(field);
        if (before != null) {
            return new FieldStatus(Kind.CHECK, "From barcode; printed text read \"" + before + "\" — check it");
        }
        if (p.barcodeAgrees.contains(field)) return new FieldStatus(Kind.CONFIRMED, "Read + barcode agree");
        if (p.fromBarcode.contains(field)) return new FieldStatus(Kind.CONFIRMED, "From barcode");
        return NONE;
    }

    /** "7 fields · 5 confirmed · 2 to check"; "" when no field has a value. */
    public static String summary(List<FieldStatus> fields, int filled) {
        if (filled <= 0) return "";
        int ok = 0, check = 0;
        for (FieldStatus f : fields) {
            if (f.kind == Kind.CONFIRMED || f.kind == Kind.LOOKED_UP) ok++;
            else if (f.kind == Kind.CHECK) check++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(filled).append(filled == 1 ? " field" : " fields");
        sb.append(" · ").append(ok).append(" confirmed");
        if (check > 0) sb.append(" · ").append(check).append(" to check");
        return sb.toString();
    }

    /** Index of the first field to check, or -1. */
    public static int firstToCheck(List<FieldStatus> fields) {
        for (int i = 0; i < fields.size(); i++) if (fields.get(i).kind == Kind.CHECK) return i;
        return -1;
    }

    /**
     * What saving does to a field of a part already in stock. {@code overwrites}: the save replaces the stored value
     * (a looked-up description, the bin count in "Set" mode); otherwise a stored value is kept and only a blank one is
     * filled. Returns the old value to show as "was …" / "keeps …", or null when nothing differs.
     */
    public static Change change(String stored, String scanned, boolean overwrites) {
        String a = norm(stored), b = norm(scanned);
        if (a.isEmpty() || b.isEmpty() || a.equals(b)) return null;
        String old = stored.trim();
        return new Change(old, overwrites);
    }

    public static final class Change {
        public final String old;
        /** True: the save replaces {@link #old}. False: the saved part keeps it. */
        public final boolean replaces;

        Change(String old, boolean replaces) {
            this.old = old;
            this.replaces = replaces;
        }

        public String text() {
            String o = old.length() > 40 ? old.substring(0, 39) + "…" : old;
            return replaces ? "was " + o : "saved part keeps " + o;
        }
    }

    private static String norm(String s) {
        if (s == null) return "";
        return s.trim().replaceAll("\\s+", " ").toUpperCase(Locale.US);
    }
}
