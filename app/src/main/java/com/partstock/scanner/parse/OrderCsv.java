package com.partstock.scanner.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an order exported from Mouser (order history / cart) or DigiKey (order / cart) as CSV.
 *
 * <p>The exact column names differ between export pages and change over time, so columns are found by keywords in
 * the header row ("Mouser No" / "Mouser #", "Mfr. No" / "Manufacturer Part Number", "Digi-Key Part Number" /
 * "DigiKey Part #", "Quantity" / "Order Qty.", "Unit Price" / "Price (USD)"…). Which distributor it is comes from
 * the part-number column. Lines without a part number or quantity (totals, notes) are skipped.
 */
public final class OrderCsv {
    private OrderCsv() {}

    public static final class Line {
        /** Line number in the order: the file's line / index column, else the row's position (1, 2, 3…). */
        public String line;
        public String distributorPn = "";
        public String mpn = "";
        public String manufacturer = "";
        public String description = "";
        public String customerPn = "";
        public int quantity;
        public Double unitPrice;

        /** Identifies this order line so importing the order twice can be caught. */
        public String fingerprint(String distributor, String orderNumber) {
            String order = orderNumber == null ? "" : squash(orderNumber);
            String key = order.isEmpty() ? squash(distributorPn.isEmpty() ? mpn : distributorPn) + "|" + quantity : order;
            return "ORDER|" + squash(distributor) + "|" + key + "|" + squash(line).replaceFirst("^0+(?=\\d)", "");
        }
    }

    public static final class Order {
        /** {@link ParsedPart#MOUSER} or {@link ParsedPart#DIGIKEY}; null when the file wasn't recognised. */
        public String distributor;
        /** From an order-number column, else digits in the file name; may be blank. */
        public String orderNumber = "";
        public final List<Line> lines = new ArrayList<>();
        /** Why the file couldn't be read, or null. */
        public String error;
    }

    /** GENERIC_PN: a plain "Part Number" column, the distributor's own number in DigiKey's cart export. */
    private enum Col { DIST_PN, MPN, MFR, DESC, CUST_PN, QTY, PRICE, ORDER, LINE, GENERIC_PN }

    public static Order parse(String text, String fileName) {
        Order o = new Order();
        List<List<String>> recs = InventoryCsv.parse(text);
        int headerAt = -1;
        int[] cols = null;
        // The header may come after a few lines of order details; take the first row that names the columns.
        for (int i = 0; i < Math.min(recs.size(), 30) && headerAt < 0; i++) {
            int[] c = mapHeader(recs.get(i));
            if (c[Col.DIST_PN.ordinal()] < 0) c[Col.DIST_PN.ordinal()] = c[Col.GENERIC_PN.ordinal()];
            if (c[Col.QTY.ordinal()] >= 0 && (c[Col.DIST_PN.ordinal()] >= 0 || c[Col.MPN.ordinal()] >= 0)) {
                headerAt = i;
                cols = c;
            }
        }
        if (cols == null) {
            o.error = "No part number and quantity columns found. Export the order as CSV from Mouser or DigiKey.";
            return o;
        }
        List<String> header = recs.get(headerAt);
        o.distributor = distributorOf(header, cols);
        if (o.distributor == null) o.distributor = distributorFromValues(recs.subList(headerAt + 1, recs.size()), cols);
        if (o.distributor == null) {
            o.error = "This doesn't look like a Mouser or DigiKey order (no Mouser or DigiKey part number column).";
            return o;
        }
        int n = 0;
        for (int i = headerAt + 1; i < recs.size(); i++) {
            List<String> r = recs.get(i);
            Line l = new Line();
            l.distributorPn = get(r, cols, Col.DIST_PN);
            l.mpn = get(r, cols, Col.MPN);
            if (l.distributorPn.isEmpty() && l.mpn.isEmpty()) continue;
            Integer q = quantity(get(r, cols, Col.QTY));
            if (q == null || q <= 0) continue;
            l.quantity = q;
            l.manufacturer = get(r, cols, Col.MFR);
            l.description = get(r, cols, Col.DESC).toUpperCase(Locale.US);
            l.customerPn = get(r, cols, Col.CUST_PN);
            l.unitPrice = price(get(r, cols, Col.PRICE));
            n++;
            String line = get(r, cols, Col.LINE);
            l.line = line.isEmpty() ? String.valueOf(n) : line;
            if (o.orderNumber.isEmpty()) o.orderNumber = get(r, cols, Col.ORDER);
            o.lines.add(l);
        }
        if (o.orderNumber.isEmpty()) o.orderNumber = orderFromFileName(fileName);
        if (o.lines.isEmpty()) o.error = "No order lines with a part number and quantity were found.";
        return o;
    }

    /** Column index per {@link Col}, -1 when absent. Earlier, more specific matches win. */
    static int[] mapHeader(List<String> header) {
        int[] c = new int[Col.values().length];
        java.util.Arrays.fill(c, -1);
        int shippedQty = -1;
        for (int i = 0; i < header.size(); i++) {
            String h = norm(header.get(i));
            if (h.isEmpty()) continue;
            Col col = classify(h);
            if (col == null) continue;
            if (col == Col.QTY && h.contains("ship")) shippedQty = i;
            if (c[col.ordinal()] < 0) c[col.ordinal()] = i;
        }
        // An order history with both ordered and shipped quantities: what arrived is what's in the bags.
        if (shippedQty >= 0) c[Col.QTY.ordinal()] = shippedQty;
        return c;
    }

    private static Col classify(String h) {
        boolean pnWord = h.contains("no") || h.contains("#") || h.contains("number") || h.contains("pn")
                || h.contains("part");
        if (h.contains("description") || h.equals("desc")) return Col.DESC;
        if (h.contains("price") || h.contains("cost")) {
            if (h.contains("ext") || h.contains("total") || h.contains("subtotal")) return null;
            return Col.PRICE;
        }
        if (h.contains("mouser") || h.contains("digikey")) return pnWord || h.equals("mouser") || h.equals("digikey")
                ? Col.DIST_PN : null;
        if (h.equals("mpn")) return Col.MPN;
        if ((h.startsWith("mfr") || h.startsWith("mfg") || h.startsWith("manufacturer")) && pnWord
                && !h.equals("manufacturername")) return Col.MPN;
        if (h.equals("manufacturer") || h.equals("mfr") || h.equals("mfg") || h.equals("manufacturername")
                || h.equals("brand")) return Col.MFR;
        if (h.startsWith("cust")) {
            if (h.contains("po") && !h.contains("pn") && !h.contains("part")) return null; // Cust PO is not a part
            return Col.CUST_PN;
        }
        if (h.contains("backorder") || h.contains("minimum") || h.contains("avail") || h.contains("stock")
                || h.contains("mult")) return null;
        if (h.contains("quantity") || h.contains("qty")) return Col.QTY;
        if (h.contains("salesorder") || h.contains("weborder") || h.equals("order") || h.equals("order#")
                || h.equals("orderno") || h.equals("ordernumber") || h.equals("invoice")) return Col.ORDER;
        if (h.equals("partnumber") || h.equals("part#") || h.equals("partno") || h.equals("productnumber")
                || h.equals("pn")) return Col.GENERIC_PN;
        if (h.equals("line") || h.equals("line#") || h.equals("lineno") || h.equals("lineitem")
                || h.equals("linenumber") || h.equals("index") || h.equals("item") || h.equals("item#")) return Col.LINE;
        return null;
    }

    private static String distributorOf(List<String> header, int[] cols) {
        int i = cols[Col.DIST_PN.ordinal()];
        if (i >= 0) {
            String h = norm(header.get(i));
            if (h.contains("mouser")) return ParsedPart.MOUSER;
            if (h.contains("digikey")) return ParsedPart.DIGIKEY;
        }
        for (String s : header) {
            String h = norm(s);
            if (h.contains("mouser")) return ParsedPart.MOUSER;
            if (h.contains("digikey")) return ParsedPart.DIGIKEY;
        }
        return null;
    }

    /**
     * A plain "Part Number" column: DigiKey numbers end in "-ND", Mouser's start with a 2–4 digit prefix and a dash.
     * Most rows have to agree.
     */
    private static String distributorFromValues(List<List<String>> rows, int[] cols) {
        if (cols[Col.DIST_PN.ordinal()] < 0) return null;
        int dk = 0, mo = 0, all = 0;
        for (List<String> r : rows) {
            String v = get(r, cols, Col.DIST_PN).toUpperCase(Locale.US);
            if (v.isEmpty()) continue;
            all++;
            if (v.endsWith("-ND")) dk++;
            else if (v.matches("\\d{2,4}-.{3,}")) mo++;
        }
        if (all == 0) return null;
        if (dk * 2 > all) return ParsedPart.DIGIKEY;
        if (mo * 2 > all) return ParsedPart.MOUSER;
        return null;
    }

    /** Lower case with only letters, digits and '#' kept: "Digi-Key Part Number" → "digikeypartnumber". */
    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US).replaceAll("[^a-z0-9#]", "");
    }

    private static String get(List<String> r, int[] cols, Col c) {
        int i = cols[c.ordinal()];
        if (i < 0 || i >= r.size()) return "";
        return r.get(i).trim();
    }

    static Integer quantity(String s) {
        String t = s.replace(",", "").replace(" ", "").trim();
        if (!t.matches("\\d+(\\.0+)?")) return null;
        try {
            return (int) Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final Pattern NUMBER = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?|\\.\\d+)");

    /** "$0.123", "0.123 USD", "1,234.50" → number; blank or unreadable → null. */
    static Double price(String s) {
        if (s == null) return null;
        Matcher m = NUMBER.matcher(s);
        if (!m.find()) return null;
        try {
            double d = Double.parseDouble(m.group(1).replace(",", ""));
            return d < 0 ? null : d;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "84046368.csv", "Order_84046368 (1).csv" → "84046368"; the longest run of 6+ digits. */
    static String orderFromFileName(String name) {
        if (name == null) return "";
        Matcher m = Pattern.compile("\\d{6,}").matcher(name);
        String best = "";
        while (m.find()) if (m.group().length() > best.length()) best = m.group();
        return best;
    }

    private static String squash(String s) {
        return s == null ? "" : s.toUpperCase(Locale.US).replaceAll("\\s+", "");
    }
}
