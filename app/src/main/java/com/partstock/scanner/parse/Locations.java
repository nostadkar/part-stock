package com.partstock.scanner.parse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stock per storage location (bin). A part's stock is an ordered map bin → pieces; the part's total is the sum.
 * Pure Java so the bookkeeping (add, set, use, move, rename, the CSV "Locations" column) is unit-tested.
 */
public final class Locations {
    private Locations() {}

    /** Bin name used for stock saved without a bin. */
    public static final String UNASSIGNED = "Unassigned";

    /** Trimmed bin name; blank becomes {@link #UNASSIGNED}. */
    public static String normalize(String bin) {
        String b = bin == null ? "" : bin.trim();
        return b.isEmpty() ? UNASSIGNED : b;
    }

    /** The bin as stored in the part's plain "location" field: blank for {@link #UNASSIGNED}. */
    public static String plain(String bin) {
        String b = normalize(bin);
        return b.equalsIgnoreCase(UNASSIGNED) ? "" : b;
    }

    /** Same bin, ignoring case and surrounding spaces ("a3" = "A3 "). */
    public static boolean same(String a, String b) {
        return normalize(a).equalsIgnoreCase(normalize(b));
    }

    public static int total(Map<String, Integer> stock) {
        int t = 0;
        for (Integer q : stock.values()) if (q != null) t += Math.max(0, q);
        return t;
    }

    /** Key in [stock] that is the same bin as [bin], or null. */
    public static String keyOf(Map<String, Integer> stock, String bin) {
        for (String k : stock.keySet()) if (same(k, bin)) return k;
        return null;
    }

    public static int qtyIn(Map<String, Integer> stock, String bin) {
        String k = keyOf(stock, bin);
        Integer q = k == null ? null : stock.get(k);
        return q == null ? 0 : q;
    }

    /** Copy of [stock] with [bin] set to [qty] (keeps the existing spelling of the bin's name). */
    public static LinkedHashMap<String, Integer> set(Map<String, Integer> stock, String bin, int qty) {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>(stock);
        String k = keyOf(out, bin);
        out.put(k != null ? k : normalize(bin), Math.max(0, qty));
        return out;
    }

    /** Copy of [stock] with [qty] added to [bin]. */
    public static LinkedHashMap<String, Integer> add(Map<String, Integer> stock, String bin, int qty) {
        return set(stock, bin, qtyIn(stock, bin) + qty);
    }

    /**
     * Copy of [stock] with [qty] pieces taken out: from [preferred] first, then the other bins in order. Stops at 0
     * everywhere (taking more than the total empties every bin).
     */
    public static LinkedHashMap<String, Integer> take(Map<String, Integer> stock, String preferred, int qty) {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>(stock);
        int left = Math.max(0, qty);
        List<String> order = new ArrayList<>();
        String pk = preferred == null ? null : keyOf(out, preferred);
        if (pk != null) order.add(pk);
        for (String k : out.keySet()) if (!k.equals(pk)) order.add(k);
        for (String k : order) {
            if (left == 0) break;
            int have = out.get(k) == null ? 0 : out.get(k);
            int t = Math.min(have, left);
            out.put(k, have - t);
            left -= t;
        }
        return out;
    }

    /** Copy of [stock] with up to [qty] moved from bin [from] to bin [to] (never more than [from] holds). */
    public static LinkedHashMap<String, Integer> move(Map<String, Integer> stock, String from, String to, int qty) {
        int n = Math.min(Math.max(0, qty), qtyIn(stock, from));
        if (n == 0 || same(from, to)) return new LinkedHashMap<>(stock);
        return add(set(stock, from, qtyIn(stock, from) - n), to, n);
    }

    /** Copy of [stock] with bin [from] renamed to [to]; if the part already has [to], the counts are added. */
    public static LinkedHashMap<String, Integer> rename(Map<String, Integer> stock, String from, String to) {
        String fk = keyOf(stock, from);
        if (fk == null) return new LinkedHashMap<>(stock);
        String target = normalize(to);
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        String tk = same(from, to) ? null : keyOf(stock, to);
        int moved = stock.get(fk) == null ? 0 : stock.get(fk);
        for (Map.Entry<String, Integer> e : stock.entrySet()) {
            if (e.getKey().equals(fk)) {
                if (tk == null) out.put(target, moved); // takes the old bin's place in the order
            } else if (e.getKey().equals(tk)) {
                out.put(tk, (e.getValue() == null ? 0 : e.getValue()) + moved);
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /**
     * What gets stored: bins with stock, in order. A part with nothing left keeps one empty bin ([primary], else the
     * first bin it had) so it doesn't lose its place.
     */
    public static LinkedHashMap<String, Integer> compact(Map<String, Integer> stock, String primary) {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : stock.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) {
                String k = keyOf(out, e.getKey());
                if (k == null) out.put(normalize(e.getKey()), e.getValue());
                else out.put(k, out.get(k) + e.getValue());
            }
        }
        if (out.isEmpty()) {
            String keep = primary != null && !primary.trim().isEmpty() ? primary
                    : stock.isEmpty() ? UNASSIGNED : stock.keySet().iterator().next();
            out.put(normalize(keep), 0);
        }
        return out;
    }

    /** The CSV "Locations" column: {@code A3: 200; R2: 1000}. */
    public static String format(Map<String, Integer> stock) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : stock.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append(": ").append(e.getValue() == null ? 0 : e.getValue());
        }
        return sb.toString();
    }

    /**
     * Reads the "Locations" column back. Entries are split on ';' and the count is the number after the last ':'
     * (so a bin named "Shelf 2: left" still works). Entries without a count are skipped; repeated bins are added.
     */
    public static LinkedHashMap<String, Integer> parse(String text) {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String part : text.split(";")) {
            String p = part.trim();
            int c = p.lastIndexOf(':');
            if (c < 0) continue;
            String bin = p.substring(0, c).trim();
            String num = p.substring(c + 1).trim().replace(",", "");
            int q;
            try {
                q = Integer.parseInt(num);
            } catch (NumberFormatException e) {
                continue;
            }
            String k = keyOf(out, bin);
            if (k == null) out.put(normalize(bin), Math.max(0, q));
            else out.put(k, out.get(k) + Math.max(0, q));
        }
        return out;
    }

    /** Short summary for a list row: "A3" for one bin, "A3: 200 · R2: 1000" for several; blank when unassigned. */
    public static String summary(Map<String, Integer> stock) {
        if (stock.isEmpty()) return "";
        if (stock.size() == 1) return plain(stock.keySet().iterator().next());
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : stock.entrySet()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(e.getKey()).append(": ").append(e.getValue());
        }
        return sb.toString();
    }
}
