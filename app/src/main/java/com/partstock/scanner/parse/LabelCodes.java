package com.partstock.scanner.parse;

/**
 * The text inside Part Stock's own QR labels. Small and app-specific so other QR codes on a bag are ignored:
 * {@code PS:P:<part id>} for a part and {@code PS:L:<bin name>} for a storage location.
 */
public final class LabelCodes {
    private LabelCodes() {}

    public static final String PREFIX = "PS:";
    private static final String PART = "PS:P:";
    private static final String BIN = "PS:L:";

    /** What a scanned label points at: a part ({@link #partId}) or a bin ({@link #bin}). */
    public static final class Code {
        /** Part id, or -1 for a bin label. */
        public final long partId;
        /** Bin name, or null for a part label. */
        public final String bin;

        Code(long partId, String bin) {
            this.partId = partId;
            this.bin = bin;
        }

        public boolean isPart() {
            return bin == null;
        }
    }

    public static String part(long id) {
        return PART + id;
    }

    /** Bin label text; a blank bin is {@link Locations#UNASSIGNED}. Line breaks are flattened to spaces. */
    public static String bin(String name) {
        return BIN + Locations.normalize(name.replaceAll("[\\r\\n]+", " "));
    }

    /** True for any text that looks like one of the app's labels (even a damaged one), so it is never parsed as a part label. */
    public static boolean isOurs(String text) {
        return text != null && text.trim().toUpperCase(java.util.Locale.ROOT).startsWith(PREFIX);
    }

    /** Reads a label; null when it isn't one of the app's labels or is malformed. */
    public static Code decode(String text) {
        if (text == null) return null;
        String t = text.trim();
        String up = t.toUpperCase(java.util.Locale.ROOT);
        if (up.startsWith(PART)) {
            String n = t.substring(PART.length()).trim();
            if (n.isEmpty() || n.length() > 18) return null;
            for (int i = 0; i < n.length(); i++) if (!Character.isDigit(n.charAt(i))) return null;
            long id = Long.parseLong(n);
            return id > 0 ? new Code(id, null) : null;
        }
        if (up.startsWith(BIN)) {
            String b = t.substring(BIN.length()).trim();
            return b.isEmpty() ? null : new Code(-1, b);
        }
        return null;
    }

    /** The first of the app's labels among the scanned codes, or null. */
    public static Code first(Iterable<String> codes) {
        if (codes == null) return null;
        for (String c : codes) {
            Code d = decode(c);
            if (d != null) return d;
        }
        return null;
    }
}
