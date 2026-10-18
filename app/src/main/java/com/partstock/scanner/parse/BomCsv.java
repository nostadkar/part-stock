package com.partstock.scanner.parse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a bill of materials CSV: KiCad's BOM export (Reference, Value, Footprint, Qty, DNP…, including the older
 * "grouped by value" plugin output with its preamble lines) or a generic CSV with columns for the MPN or value, the
 * quantity per board and the reference designators. Columns are found by header keywords; ',', ';' and tab
 * separators are accepted. Lines marked DNP are left out.
 */
public final class BomCsv {
    private BomCsv() {}

    public static final class Line {
        public String mpn = "";
        public String customerPn = "";
        public String distributorPn = "";
        public String manufacturer = "";
        public String value = "";
        public String footprint = "";
        public String description = "";
        /** Reference designators, space separated ("C1 C2 C3"). */
        public String refDes = "";
        public int perBoard;

        /** What to show for the line: MPN, else customer PN, else value + footprint. */
        public String label() {
            if (!mpn.isEmpty()) return mpn;
            if (!customerPn.isEmpty()) return customerPn;
            if (!distributorPn.isEmpty()) return distributorPn;
            String fp = shortFootprint();
            return (value + (fp.isEmpty() ? "" : " " + fp)).trim();
        }

        /** "Resistor_SMD:R_0603_1608Metric" → "R_0603_1608Metric". */
        public String shortFootprint() {
            int i = footprint.lastIndexOf(':');
            return i >= 0 ? footprint.substring(i + 1) : footprint;
        }

        /**
         * Search text to find this part in stock by hand: the value plus a package from the footprint
         * ("10k 0603"), for the inventory search that understands values and packages.
         */
        public String searchHint() {
            if (!mpn.isEmpty()) return mpn;
            String pkg = PartSpecs.fromDescription(shortFootprint().replace('_', ' ')).pkg;
            return (value + " " + (pkg == null ? "" : pkg)).trim();
        }
    }

    public static final class Bom {
        public final List<Line> lines = new ArrayList<>();
        /** Why the file couldn't be read, or null. */
        public String error;
        /** Lines left out because they're marked do-not-populate. */
        public int dnp;
    }

    private enum Col { REF, QTY, VALUE, FOOTPRINT, MPN, CUST_PN, DIST_PN, MFR, DESC, DNP }

    public static Bom parse(String text) {
        Bom bom = new Bom();
        List<List<String>> recs = InventoryCsv.parse(text, delimiter(text));
        int headerAt = -1;
        int[] cols = null;
        for (int i = 0; i < Math.min(recs.size(), 40) && headerAt < 0; i++) {
            int[] c = mapHeader(recs.get(i));
            boolean ids = c[Col.MPN.ordinal()] >= 0 || c[Col.VALUE.ordinal()] >= 0 || c[Col.CUST_PN.ordinal()] >= 0;
            boolean count = c[Col.QTY.ordinal()] >= 0 || c[Col.REF.ordinal()] >= 0;
            if (ids && count) {
                headerAt = i;
                cols = c;
            }
        }
        if (cols == null) {
            bom.error = "No BOM columns found. The file needs a header with a part number or value column and a "
                    + "quantity or reference column.";
            return bom;
        }
        for (int i = headerAt + 1; i < recs.size(); i++) {
            List<String> r = recs.get(i);
            if (isDnp(get(r, cols, Col.DNP))) {
                bom.dnp++;
                continue;
            }
            Line l = new Line();
            l.mpn = get(r, cols, Col.MPN);
            l.customerPn = get(r, cols, Col.CUST_PN);
            l.distributorPn = get(r, cols, Col.DIST_PN);
            l.value = get(r, cols, Col.VALUE);
            l.footprint = get(r, cols, Col.FOOTPRINT);
            l.manufacturer = get(r, cols, Col.MFR);
            l.description = get(r, cols, Col.DESC);
            if (l.mpn.isEmpty() && l.customerPn.isEmpty() && l.distributorPn.isEmpty() && l.value.isEmpty()) continue;
            List<String> refs = refDes(get(r, cols, Col.REF));
            l.refDes = String.join(" ", refs);
            Integer q = qty(get(r, cols, Col.QTY));
            l.perBoard = q != null ? q : refs.size();
            if (l.perBoard <= 0) {
                if (cols[Col.QTY.ordinal()] >= 0 && q != null) continue; // Qty 0: not fitted
                l.perBoard = 1;
            }
            bom.lines.add(l);
        }
        if (bom.lines.isEmpty()) bom.error = "No BOM lines found under the header.";
        return bom;
    }

    static int[] mapHeader(List<String> header) {
        int[] c = new int[Col.values().length];
        Arrays.fill(c, -1);
        for (int i = 0; i < header.size(); i++) {
            Col col = classify(norm(header.get(i)));
            if (col != null && c[col.ordinal()] < 0) c[col.ordinal()] = i;
        }
        return c;
    }

    private static Col classify(String h) {
        if (h.isEmpty()) return null;
        if (h.equals("dnp") || h.equals("dnf") || h.contains("donotpopulate") || h.contains("donotplace")
                || h.equals("exclude") || h.contains("excludefrombom")) return Col.DNP;
        if (h.startsWith("ref") || h.contains("designator") || h.equals("partreference")) {
            return Col.REF;
        }
        if (h.contains("mouser") || h.contains("digikey") || h.contains("lcsc") || h.contains("supplierpart")
                || h.contains("distributorpart") || h.contains("vendorpart")) return Col.DIST_PN;
        if (h.equals("cpn") || h.contains("customerpn") || h.contains("customerpart") || h.contains("internalpn")
                || h.contains("internalpart") || h.equals("companypn") || h.equals("pn")) return Col.CUST_PN;
        if (h.equals("mpn") || h.equals("partnumber") || h.equals("part#") || h.equals("partno")
                || ((h.startsWith("mfr") || h.startsWith("mfg") || h.startsWith("manufacturer"))
                && (h.contains("pn") || h.contains("part") || h.contains("no") || h.contains("#")
                || h.contains("number")))) return Col.MPN;
        if (h.equals("manufacturer") || h.equals("mfr") || h.equals("mfg") || h.equals("manufacturername")) {
            return Col.MFR;
        }
        if (h.startsWith("qty") || h.startsWith("quantity") || h.equals("qnty") || h.equals("count")
                || h.equals("amount")) return Col.QTY;
        if (h.equals("value") || h.equals("val") || h.equals("comment")) return Col.VALUE;
        if (h.startsWith("footprint") || h.equals("package") || h.equals("pcbfootprint") || h.equals("case")) {
            return Col.FOOTPRINT;
        }
        if (h.startsWith("desc")) return Col.DESC;
        return null;
    }

    private static char delimiter(String text) {
        if (text == null) return ',';
        // Look at the first few lines: the separator that shows up most outside quotes wins.
        int commas = 0, semis = 0, tabs = 0, lines = 0;
        boolean q = false;
        for (int i = 0; i < text.length() && lines < 10; i++) {
            char ch = text.charAt(i);
            if (ch == '"') q = !q;
            else if (!q && ch == ',') commas++;
            else if (!q && ch == ';') semis++;
            else if (!q && ch == '\t') tabs++;
            else if (ch == '\n') lines++;
        }
        if (tabs > commas && tabs >= semis) return '\t';
        if (semis > commas) return ';';
        return ',';
    }

    private static final Pattern RANGE = Pattern.compile("^([A-Za-z_]+)(\\d+)-(?:\\1)?(\\d+)$");

    /** "C1, C2,C3" / "R1-R4" / "D1 D2" → individual designators, in order, without repeats. */
    static List<String> refDes(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s == null) return new ArrayList<>(out);
        for (String tok : s.replaceAll("\\s*[-–]\\s*", "-").split("[,;\\s]+")) {
            String t = tok.trim();
            if (t.isEmpty()) continue;
            Matcher m = RANGE.matcher(t);
            if (m.matches()) {
                int a = Integer.parseInt(m.group(2)), b = Integer.parseInt(m.group(3));
                if (b >= a && b - a < 1000) {
                    for (int k = a; k <= b; k++) out.add(m.group(1) + k);
                    continue;
                }
            }
            out.add(t);
        }
        return new ArrayList<>(out);
    }

    private static boolean isDnp(String s) {
        String t = s.trim().toLowerCase(Locale.US);
        return t.equals("dnp") || t.equals("dnf") || t.equals("x") || t.equals("yes") || t.equals("y")
                || t.equals("1") || t.equals("true") || t.equals("excluded");
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US).replaceAll("[^a-z0-9#]", "");
    }

    private static String get(List<String> r, int[] cols, Col c) {
        int i = cols[c.ordinal()];
        if (i < 0 || i >= r.size()) return "";
        return r.get(i).trim();
    }

    /** Quantity cells: "4", "4.0", "1,000"; anything else is null. */
    static Integer qty(String s) {
        String t = s.replace(",", "").replace(" ", "");
        if (!t.matches("\\d+(\\.0+)?")) return null;
        try {
            return (int) Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
