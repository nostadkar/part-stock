package com.partstock.scanner.parse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Inventory list order and layout (#44): the sort menu's choices with their comparators, and the List / Compact /
 * Grid views. Only {@link Sort#CATEGORY} groups the list under category headers; the others give one flat list.
 */
public final class PartSort {
    private PartSort() {}

    /** What a part offers for sorting (PartRow adapts to this). */
    public interface Item {
        /** Part number shown on the row (MPN, else customer / distributor PN). */
        String key();

        int quantity();

        /** Unit cost, null when unpriced. */
        Double unitCost();

        /** Minimum stock, null when not tracked. */
        Integer minQty();

        /** "yyyy-MM-dd HH:mm:ss" (sorts as text); blank when unknown. */
        String updatedAt();
    }

    public enum Sort {
        CATEGORY("Category"),
        NAME("Name"),
        QTY_HIGH("Quantity (high first)"),
        QTY_LOW("Quantity (low first)"),
        VALUE("Value"),
        RECENT("Recently updated"),
        LOWEST_STOCK("Lowest stock first");

        public final String label;

        Sort(String label) {
            this.label = label;
        }

        /** Grouped under sticky category headers. */
        public boolean grouped() {
            return this == CATEGORY;
        }

        public static Sort parse(String name) {
            if (name != null) for (Sort s : values()) if (s.name().equals(name)) return s;
            return CATEGORY;
        }
    }

    public enum View {
        LIST("List"),
        COMPACT("Compact"),
        GRID("Grid");

        public final String label;

        View(String label) {
            this.label = label;
        }

        public static View parse(String name) {
            if (name != null) for (View v : values()) if (v.name().equals(name)) return v;
            return LIST;
        }
    }

    /** Stock value: unit cost × pieces (0 when unpriced). */
    public static double value(Item p) {
        Double c = p.unitCost();
        return c == null ? 0 : c * Math.max(0, p.quantity());
    }

    private static String name(Item p) {
        String k = p.key();
        return k == null ? "" : k.toLowerCase(Locale.ROOT);
    }

    /**
     * The comparator for [sort]; ties go by part number. CATEGORY keeps the incoming order (the database already
     * sorts by category, then part number), so it returns null.
     */
    public static <T extends Item> Comparator<T> comparator(Sort sort) {
        Comparator<T> byName = (a, b) -> name(a).compareTo(name(b));
        switch (sort) {
            case NAME:
                return byName;
            case QTY_HIGH:
                return ((Comparator<T>) (a, b) -> Integer.compare(b.quantity(), a.quantity())).thenComparing(byName);
            case QTY_LOW:
                return ((Comparator<T>) (a, b) -> Integer.compare(a.quantity(), b.quantity())).thenComparing(byName);
            case VALUE:
                // Highest stock value first; unpriced parts after every priced one.
                return ((Comparator<T>) (a, b) -> {
                    boolean ua = a.unitCost() == null, ub = b.unitCost() == null;
                    if (ua != ub) return ua ? 1 : -1;
                    return Double.compare(value(b), value(a));
                }).thenComparing(byName);
            case RECENT:
                return ((Comparator<T>) (a, b) -> nz(b.updatedAt()).compareTo(nz(a.updatedAt()))).thenComparing(byName);
            case LOWEST_STOCK:
                // Nearly out first (quantity ÷ minimum), parts without a minimum last.
                return ((Comparator<T>) (a, b) -> Double.compare(
                        StockLevel.ratio(a.quantity(), a.minQty()), StockLevel.ratio(b.quantity(), b.minQty())))
                        .thenComparing(byName);
            default:
                return null;
        }
    }

    /** [parts] in [sort] order (a copy). */
    public static <T extends Item> List<T> sorted(List<T> parts, Sort sort) {
        List<T> out = new ArrayList<>(parts);
        Comparator<T> c = comparator(sort);
        if (c != null) out.sort(c);
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
