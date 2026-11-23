package com.partstock.scanner.parse;

import java.util.List;
import java.util.Locale;

/** Status, counts and totals for the order import page (pure Java, unit tested). */
public final class OrderLines {
    private OrderLines() {}

    /** NEW: no such part yet; RESTOCK: adds to a part in stock; IMPORTED: this order line was imported before. */
    public enum Status { NEW, RESTOCK, IMPORTED }

    /** A line imported before counts as IMPORTED even when the part is in stock. */
    public static Status status(boolean inStock, boolean importedBefore) {
        if (importedBefore) return Status.IMPORTED;
        return inStock ? Status.RESTOCK : Status.NEW;
    }

    /** How many lines have {@code s}. */
    public static int count(List<Status> statuses, Status s) {
        int n = 0;
        for (Status x : statuses) if (x == s) n++;
        return n;
    }

    /**
     * Sum of quantity × unit price over the lines where {@code include} is true (all lines when it is null).
     * Lines without a price add nothing.
     */
    public static double total(List<OrderCsv.Line> lines, boolean[] include) {
        double t = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (include != null && (i >= include.length || !include[i])) continue;
            OrderCsv.Line l = lines.get(i);
            if (l.unitPrice != null) t += l.unitPrice * l.quantity;
        }
        return t;
    }

    /** "$12.34" (always two decimals). */
    public static String money(double d) {
        return "$" + String.format(Locale.US, "%,.2f", d);
    }

    /** "1 line" / "28 lines". */
    public static String lines(int n) {
        return n + (n == 1 ? " line" : " lines");
    }
}
