package com.partstock.scanner.parse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Barcodes on distributor labels, used as a cross-check for OCR (barcodes are read exactly, OCR swaps look-alikes).
 *
 * <p>DigiKey (and newer Mouser) labels carry a DataMatrix in the ECIA / ANSI MH10.8.2 format:
 * {@code [)>} RS {@code 06} GS {@code P811755-101} GS {@code 1P2SK3019-TP} GS {@code 30P2SK3019-TPMSCT-ND} GS
 * {@code Q500} … RS EOT, where each field starts with a data identifier (digits + one letter). Mouser's 1D barcodes
 * hold one value each (P/N, quantity, PO); those are matched to the OCR field they look like.
 */
public final class LabelBarcode {
    private LabelBarcode() {}

    static final char RS = '\u001e';
    static final char GS = '\u001d';
    static final char EOT = '\u0004';

    /** Field names used in {@link ParsedPart#fromBarcode}. */
    public static final String F_MPN = "mpn";
    public static final String F_DIST_PN = "distributorPn";
    public static final String F_CUST_PN = "customerPn";
    public static final String F_QTY = "quantity";
    public static final String F_PO = "purchaseOrder";
    public static final String F_LINE = "lineItem";
    public static final String F_DATE = "dateCode";

    private static final Pattern DI = Pattern.compile("^(\\d{0,3}[A-Z])(.*)$", Pattern.DOTALL);
    private static final Pattern MOUSER_PN = Pattern.compile("^(\\d{2,4})-([A-Z0-9][A-Z0-9\\-./#+]{2,})$");
    private static final Pattern QTY_1D = Pattern.compile("^Q?(\\d{1,7})$");

    /** What the barcodes on one label say. Null fields weren't in any barcode. */
    public static final class Read {
        public String distributor;
        public String mpn;
        public String distributorPn;
        public String customerPn;
        public Integer quantity;
        public String purchaseOrder;
        public String lineItem;
        public String dateCode;
        public String lot;
        public String countryOfOrigin;
        /** Values of barcodes that aren't MH10.8.2 (Mouser's 1D codes), matched to OCR fields later. */
        public final List<String> plain = new ArrayList<>();

        public boolean isEmpty() {
            return mpn == null && distributorPn == null && customerPn == null && quantity == null
                    && purchaseOrder == null && lineItem == null && dateCode == null && plain.isEmpty();
        }
    }

    /**
     * Splits an MH10.8.2 / ECIA message into data identifier → value (first value wins), or null when the text isn't
     * one. Accepts the real control characters, their visible Unicode pictures (␞ ␝ ␄), and "{GS}"-style
     * placeholders some readers produce.
     */
    public static Map<String, String> decodeMh10(String raw) {
        if (raw == null) return null;
        String s = raw
                .replace('␞', RS).replace('␝', GS).replace('␄', EOT)
                .replace("{RS}", String.valueOf(RS)).replace("{GS}", String.valueOf(GS))
                .replace("{EOT}", String.valueOf(EOT))
                .replace("<RS>", String.valueOf(RS)).replace("<GS>", String.valueOf(GS))
                .replace("<EOT>", String.valueOf(EOT));
        int start = s.indexOf("[)>");
        if (start < 0) return null;
        s = s.substring(start + 3);
        // Header: RS "06" (format) then GS before the first field. Some readers drop the RS.
        if (!s.isEmpty() && s.charAt(0) == RS) s = s.substring(1);
        if (s.length() < 2 || !Character.isDigit(s.charAt(0)) || !Character.isDigit(s.charAt(1))) return null;
        s = s.substring(2);
        int end = s.indexOf(EOT);
        if (end >= 0) s = s.substring(0, end);
        if (s.indexOf(GS) < 0) return null; // without separators the fields can't be told apart
        Map<String, String> out = new LinkedHashMap<>();
        for (String f : s.split("[\\u001d\\u001e]")) {
            if (f.isEmpty()) continue;
            Matcher m = DI.matcher(f);
            if (!m.matches()) continue;
            String v = m.group(2).trim();
            if (!out.containsKey(m.group(1))) out.put(m.group(1), v);
        }
        return out.isEmpty() ? null : out;
    }

    /** Reads every barcode value found on the label (duplicates from a burst of photos are fine). */
    public static Read decode(List<String> raws) {
        Read r = new Read();
        if (raws == null) return r;
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : raws) {
            if (raw == null || raw.trim().isEmpty() || !seen.add(raw)) continue;
            Map<String, String> f = decodeMh10(raw);
            if (f == null) {
                String v = raw.trim().toUpperCase(Locale.US);
                if (!r.plain.contains(v)) r.plain.add(v);
                continue;
            }
            String dk = blankToNull(f.get("30P"));
            if (dk != null && r.distributorPn == null) {
                r.distributorPn = dk;
                r.distributor = ParsedPart.DIGIKEY;
            }
            if (r.mpn == null) r.mpn = blankToNull(f.get("1P"));
            // DigiKey puts its own part number in P when the order had no customer reference.
            String p = blankToNull(f.get("P"));
            if (p != null && r.customerPn == null && !p.equalsIgnoreCase(dk == null ? "" : dk)
                    && !p.equalsIgnoreCase(r.mpn == null ? "" : r.mpn)) {
                r.customerPn = p;
            }
            if (r.quantity == null) r.quantity = toInt(f.get("Q"));
            if (r.purchaseOrder == null) r.purchaseOrder = blankToNull(f.get("K"));
            if (r.lineItem == null) r.lineItem = blankToNull(first(f, "4K", "14K"));
            if (r.dateCode == null) r.dateCode = blankToNull(first(f, "10D", "9D"));
            if (r.lot == null) r.lot = blankToNull(f.get("1T"));
            if (r.countryOfOrigin == null) r.countryOfOrigin = blankToNull(f.get("4L"));
        }
        return r;
    }

    /**
     * Cross-checks the OCR result against the label's barcodes: a barcode value replaces the OCR value when they
     * disagree, or fills a field OCR missed, and the field is listed in {@link ParsedPart#fromBarcode}.
     * Returns the same part.
     */
    public static ParsedPart apply(ParsedPart p, List<String> raws) {
        Read r = decode(raws);
        if (r.isEmpty()) return p;
        if (r.distributor != null && p.distributor == null) p.distributor = r.distributor;

        if (r.mpn != null) {
            if (set(p, F_MPN, p.mpn, r.mpn)) p.mpn = r.mpn;
            p.mpnDerived = false; // printed in the barcode, not worked out
        }
        if (r.distributorPn != null && set(p, F_DIST_PN, p.distributorPn, r.distributorPn)) p.distributorPn = r.distributorPn;
        if (r.customerPn != null && set(p, F_CUST_PN, p.customerPn, r.customerPn)) p.customerPn = r.customerPn;
        if (r.quantity != null && !r.quantity.equals(p.quantity)) {
            p.quantity = r.quantity;
            p.fromBarcode.add(F_QTY);
        }
        if (r.purchaseOrder != null) {
            String old = p.purchaseOrder;
            if (set(p, F_PO, old, r.purchaseOrder)) {
                p.purchaseOrder = r.purchaseOrder;
                if (p.notes != null && old != null && p.notes.contains("PO " + old)) {
                    p.notes = p.notes.replace("PO " + old, "PO " + r.purchaseOrder);
                } else if (p.notes == null || p.notes.trim().isEmpty()) {
                    p.notes = "PO " + r.purchaseOrder;
                }
            }
        }
        if (r.lineItem != null && !sameNumber(p.lineItem, r.lineItem) && set(p, F_LINE, p.lineItem, r.lineItem)) {
            p.lineItem = r.lineItem;
        }
        if (r.dateCode != null && set(p, F_DATE, p.dateCode, r.dateCode)) p.dateCode = r.dateCode;

        for (String v : r.plain) applyPlain(p, v);

        // Keep what the barcodes said with the OCR text (review screen, scan log).
        StringBuilder sb = new StringBuilder(p.rawText == null ? "" : p.rawText);
        if (sb.length() > 0) sb.append('\n');
        sb.append("[barcodes]");
        for (String raw : new LinkedHashSet<>(raws)) {
            if (raw != null && !raw.trim().isEmpty()) sb.append('\n').append(visible(raw));
        }
        p.rawText = sb.toString();
        return p;
    }

    /**
     * A 1D barcode holds one value with no field name. Use it where it looks like what OCR read (same text apart
     * from look-alike characters or one dropped/extra character), so a misread is corrected but a PO number never
     * lands in the part number field. A "Q"-prefixed number is the quantity.
     */
    static void applyPlain(ParsedPart p, String v) {
        String[] cands = {v, stripDi(v)};
        for (String c : cands) {
            if (c == null || c.length() < 3) continue;
            if (close(p.distributorPn, c)) {
                if (set(p, F_DIST_PN, p.distributorPn, c)) {
                    String oldPn = p.distributorPn;
                    p.distributorPn = c;
                    rederiveMouserMpn(p, oldPn);
                }
                return;
            }
            if (close(p.mpn, c)) {
                if (set(p, F_MPN, p.mpn, c)) p.mpn = c;
                return;
            }
            if (close(p.customerPn, c)) {
                if (set(p, F_CUST_PN, p.customerPn, c)) p.customerPn = c;
                return;
            }
            if (close(p.purchaseOrder, c)) {
                if (set(p, F_PO, p.purchaseOrder, c)) p.purchaseOrder = c;
                return;
            }
        }
        if (v.startsWith("Q")) {
            Matcher q = QTY_1D.matcher(v);
            if (q.matches()) {
                Integer n = toInt(q.group(1));
                if (n != null && !n.equals(p.quantity)) {
                    p.quantity = n;
                    p.fromBarcode.add(F_QTY);
                }
                return;
            }
        }
        // A Mouser P/N barcode when OCR missed the P/N line entirely.
        if (ParsedPart.MOUSER.equals(p.distributor) && blank(p.distributorPn)) {
            Matcher m = MOUSER_PN.matcher(v);
            if (m.matches() && !v.equals(p.purchaseOrder)) {
                p.distributorPn = v;
                p.fromBarcode.add(F_DIST_PN);
                if (blank(p.mpn)) {
                    p.mpn = m.group(2);
                    p.mpnDerived = true;
                }
            }
        }
    }

    /** When the Mouser P/N is corrected and the MPN was worked out from it, work it out again. */
    private static void rederiveMouserMpn(ParsedPart p, String oldPn) {
        if (!p.mpnDerived || oldPn == null) return;
        Matcher o = MOUSER_PN.matcher(oldPn.toUpperCase(Locale.US));
        Matcher n = MOUSER_PN.matcher(p.distributorPn);
        if (o.matches() && n.matches() && o.group(2).equalsIgnoreCase(p.mpn == null ? "" : p.mpn)) {
            p.mpn = n.group(2);
            p.fromBarcode.add(F_MPN);
        }
    }

    /** Records the field as from-barcode when the barcode value differs from OCR's; true if it should be taken. */
    private static boolean set(ParsedPart p, String field, String ocr, String code) {
        if (ocr != null && squash(ocr).equals(squash(code))) return false;
        p.fromBarcode.add(field);
        return true;
    }

    /** Same text apart from look-alike characters, or within one edit for longer values. */
    static boolean close(String ocr, String code) {
        if (blank(ocr) || blank(code)) return false;
        String a = squash(ocr), b = squash(code);
        if (a.equals(b)) return true;
        if (lookAlike(a).equals(lookAlike(b))) return true;
        return Math.min(a.length(), b.length()) >= 6 && PartNumberVariants.distance(a, b) <= 1;
    }

    private static String lookAlike(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case 'O': case 'Q': case 'D': sb.append('0'); break;
                case 'I': case 'L': case '|': sb.append('1'); break;
                case 'S': sb.append('5'); break;
                case 'B': sb.append('8'); break;
                case 'Z': sb.append('2'); break;
                case 'G': sb.append('6'); break;
                case 'F': sb.append('E'); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String stripDi(String v) {
        Matcher m = Pattern.compile("^(?:1P|30P|P|K|Q|1T)(.+)$").matcher(v);
        return m.matches() ? m.group(1) : null;
    }

    private static boolean sameNumber(String a, String b) {
        if (a == null || b == null) return false;
        String x = a.trim().replaceFirst("^0+(?=\\d)", ""), y = b.trim().replaceFirst("^0+(?=\\d)", "");
        return x.equals(y);
    }

    private static String first(Map<String, String> f, String... keys) {
        for (String k : keys) if (f.get(k) != null && !f.get(k).trim().isEmpty()) return f.get(k);
        return null;
    }

    private static Integer toInt(String s) {
        if (s == null) return null;
        String d = s.trim().replaceAll("[,\\s]", "");
        if (!d.matches("\\d{1,9}")) return null;
        try {
            return Integer.parseInt(d);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String squash(String s) {
        return s.toUpperCase(Locale.US).replaceAll("\\s+", "");
    }

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    private static String blankToNull(String s) { return blank(s) ? null : s.trim(); }

    /** The raw value with control characters shown, for the "text read from label" view and the scan log. */
    public static String visible(String raw) {
        return raw.replace(String.valueOf(RS), "<RS>").replace(String.valueOf(GS), "<GS>")
                .replace(String.valueOf(EOT), "<EOT>").replaceAll("[\\u0000-\\u001f]", "?");
    }
}
