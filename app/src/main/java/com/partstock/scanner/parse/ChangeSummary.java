package com.partstock.scanner.parse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One line saying what a change did to a part, for its History: {@code bin A3 → R2; price 0.12 → 0.1; description}.
 * Works on column → value maps of the part before and after (the "stock" key holds {@link Locations#format}).
 * The quantity is shown separately (old → new), and bookkeeping columns (scan count, timestamps) are left out.
 */
public final class ChangeSummary {
    private ChangeSummary() {}

    /** Column → label, in display order. Short ones show old → new; long ones just say they changed. */
    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    private static final java.util.Set<String> SHORT = new java.util.HashSet<>();

    static {
        String[][] cols = {
                {"stock", "bins", "s"}, {"location", "main bin", "s"}, {"unit_cost", "price", "s"},
                {"min_qty", "minimum", "s"}, {"category", "category", "s"}, {"mpn", "MPN", "s"},
                {"manufacturer", "manufacturer", "s"}, {"distributor_pn", "distributor PN", "s"},
                {"customer_pn", "customer PN", "s"}, {"spec_value", "value", "s"}, {"tolerance", "tolerance", "s"},
                {"voltage", "voltage", "s"}, {"package", "package", "s"}, {"date_code", "date code", "s"},
                {"distributor", "distributor", "s"}, {"description", "description", ""}, {"product_url", "link", ""},
                {"datasheet_url", "datasheet", ""}, {"notes", "notes", ""}, {"source", "source", ""},
                {"ref_des", "ref des", ""},
        };
        for (String[] c : cols) {
            LABELS.put(c[0], c[1]);
            if (!c[2].isEmpty()) SHORT.add(c[0]);
        }
    }

    public static String describe(Map<String, String> before, Map<String, String> after) {
        if (before == null || before.isEmpty()) return after == null || after.isEmpty() ? "" : "New part";
        if (after == null || after.isEmpty()) return "Deleted";
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : LABELS.entrySet()) {
            String col = e.getKey();
            String a = clean(before.get(col));
            String b = clean(after.get(col));
            if (a.equals(b)) continue;
            if (col.equals("location") && Locations.same(a, b)) continue;
            if (SHORT.contains(col) && a.length() <= 40 && b.length() <= 40) {
                out.add(e.getValue() + " " + (a.isEmpty() ? "–" : a) + " → " + (b.isEmpty() ? "–" : b));
            } else {
                out.add(e.getValue());
            }
        }
        return String.join("; ", out);
    }

    private static String clean(String s) {
        if (s == null || s.equals("null")) return "";
        String t = s.trim();
        // 0.1200 and 0.12 are the same price
        if (t.matches("-?\\d+\\.\\d*?0+")) t = t.replaceAll("\\.?0+$", "");
        return t;
    }
}
