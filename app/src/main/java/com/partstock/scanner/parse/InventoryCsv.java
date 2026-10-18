package com.partstock.scanner.parse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads and writes the inventory CSV (RFC 4180). Reading is forgiving: columns are found by header name in any order,
 * missing columns (older exports had no Category / Unit Cost / Link / Minimum) are left blank, quoted fields may hold commas,
 * quotes and line breaks, and CRLF or LF line endings both work.
 */
public final class InventoryCsv {
    private InventoryCsv() {}

    /** Column order written by the app's inventory export. */
    public static final List<String> HEADER = Collections.unmodifiableList(Arrays.asList(
            "Category", "MPN", "Description", "Manufacturer", "Quantity", "Unit Cost", "Total Value", "Location",
            "Distributor", "Distributor PN", "Customer PN", "Link", "Source", "Notes", "Date Code", "Ref Des",
            "Scans", "Last Updated", "Minimum"));

    /** One part read from the file. Strings are never null; blank means the file had nothing there. */
    public static final class Row {
        public String category = "", mpn = "", description = "", manufacturer = "", location = "",
                distributor = "", distributorPn = "", customerPn = "", link = "", source = "", notes = "",
                dateCode = "", refDes = "", updatedAt = "";
        public int quantity;
        public Double unitCost;
        public Integer scans;
        /** Low-stock minimum; null when the file has none. */
        public Integer minimum;
    }

    public static final class Result {
        public final List<Row> rows;
        /** Lines that had none of MPN / Customer PN / Distributor PN, so can't be matched to a part. */
        public final int skipped;

        Result(List<Row> rows, int skipped) {
            this.rows = rows;
            this.skipped = skipped;
        }
    }

    // ---- writing ----

    public static String cell(String s) {
        if (s == null) return "";
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ',' || c == '"' || c == '\n' || c == '\r') return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /** CSV text with CRLF line ends; imports cleanly into Google Sheets and Excel. */
    public static String write(List<String> header, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        List<List<String>> all = new ArrayList<>();
        all.add(header);
        all.addAll(rows);
        for (List<String> r : all) {
            for (int i = 0; i < r.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(cell(r.get(i)));
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    // ---- reading ----

    /** Splits CSV text into records. Blank lines are dropped. */
    public static List<List<String>> parse(String text) {
        List<List<String>> out = new ArrayList<>();
        if (text == null) return out;
        if (text.startsWith("﻿")) text = text.substring(1); // Excel's UTF-8 BOM
        List<String> rec = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean quoted = false, fieldStarted = false;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        f.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    f.append(c);
                }
            } else if (c == '"' && f.length() == 0 && !fieldStarted) {
                quoted = true;
                fieldStarted = true;
            } else if (c == ',') {
                rec.add(f.toString());
                f.setLength(0);
                fieldStarted = false;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') i++;
                rec.add(f.toString());
                f.setLength(0);
                fieldStarted = false;
                addRecord(out, rec);
                rec = new ArrayList<>();
            } else {
                f.append(c);
                fieldStarted = true;
            }
        }
        if (fieldStarted || f.length() > 0 || !rec.isEmpty()) {
            rec.add(f.toString());
            addRecord(out, rec);
        }
        return out;
    }

    private static void addRecord(List<List<String>> out, List<String> rec) {
        for (String s : rec) {
            if (!s.trim().isEmpty()) {
                out.add(rec);
                return;
            }
        }
    }

    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        String[][] names = {
                {"category", "category", "bucket"},
                {"mpn", "mpn", "manufacturerpartnumber", "mfrpartnumber", "mfrpn"},
                {"description", "description", "desc"},
                {"manufacturer", "manufacturer", "mfr"},
                {"quantity", "quantity", "qty"},
                {"unitcost", "unitcost", "unitprice", "cost", "price"},
                {"location", "location", "bin"},
                {"distributor", "distributor"},
                {"distributorpn", "distributorpn", "distributorpartnumber"},
                {"customerpn", "customerpn", "customerreference", "custpn"},
                {"link", "link", "url", "productlink", "producturl"},
                {"source", "source"},
                {"notes", "notes"},
                {"datecode", "datecode"},
                {"refdes", "refdes"},
                {"scans", "scans"},
                {"lastupdated", "lastupdated", "updated", "updatedat"},
                {"minimum", "minimum", "min", "minqty", "minimumqty", "minimumquantity", "reorderpoint"},
        };
        for (String[] group : names) for (int i = 1; i < group.length; i++) ALIASES.put(group[i], group[0]);
    }

    private static String key(String header) {
        return header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * Reads an inventory CSV exported by the app (any version).
     *
     * @throws IllegalArgumentException if the file has no part-number column, so it isn't an inventory export
     */
    public static Result read(String text) {
        List<List<String>> recs = parse(text);
        if (recs.isEmpty()) throw new IllegalArgumentException("The file is empty");
        List<String> header = recs.get(0);
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String k = ALIASES.get(key(header.get(i)));
            if (k != null && !col.containsKey(k)) col.put(k, i);
        }
        if (!col.containsKey("mpn") && !col.containsKey("customerpn") && !col.containsKey("distributorpn")) {
            throw new IllegalArgumentException("No MPN / Customer PN / Distributor PN column: not an inventory CSV");
        }
        List<Row> rows = new ArrayList<>();
        int skipped = 0;
        for (int r = 1; r < recs.size(); r++) {
            List<String> rec = recs.get(r);
            Row row = new Row();
            row.category = get(rec, col, "category");
            row.mpn = get(rec, col, "mpn");
            row.description = get(rec, col, "description");
            row.manufacturer = get(rec, col, "manufacturer");
            row.location = get(rec, col, "location");
            row.distributor = get(rec, col, "distributor");
            row.distributorPn = get(rec, col, "distributorpn");
            row.customerPn = get(rec, col, "customerpn");
            row.link = get(rec, col, "link");
            row.source = get(rec, col, "source");
            row.notes = get(rec, col, "notes");
            row.dateCode = get(rec, col, "datecode");
            row.refDes = get(rec, col, "refdes");
            row.updatedAt = get(rec, col, "lastupdated");
            row.quantity = Math.max(0, parseInt(get(rec, col, "quantity"), 0));
            row.unitCost = parseCost(get(rec, col, "unitcost"));
            String s = get(rec, col, "scans");
            row.scans = s.isEmpty() ? null : parseInt(s, 1);
            String m = get(rec, col, "minimum");
            int min = m.isEmpty() ? 0 : parseInt(m, 0);
            row.minimum = min > 0 ? min : null;
            if (row.mpn.isEmpty() && row.customerPn.isEmpty() && row.distributorPn.isEmpty()) {
                skipped++;
                continue;
            }
            rows.add(row);
        }
        return new Result(rows, skipped);
    }

    private static String get(List<String> rec, Map<String, Integer> col, String k) {
        Integer i = col.get(k);
        if (i == null || i >= rec.size()) return "";
        return rec.get(i).trim();
    }

    static int parseInt(String s, int fallback) {
        String t = s.replace(",", "").replace(" ", "");
        if (t.isEmpty()) return fallback;
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            try {
                return (int) Math.round(Double.parseDouble(t)); // "29.0" from a spreadsheet
            } catch (NumberFormatException e2) {
                return fallback;
            }
        }
    }

    static Double parseCost(String s) {
        String t = s.replace("$", "").replace(",", "").trim();
        if (t.isEmpty()) return null;
        try {
            double d = Double.parseDouble(t);
            return d < 0 ? null : d;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
