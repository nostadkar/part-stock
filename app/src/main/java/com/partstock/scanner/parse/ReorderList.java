package com.partstock.scanner.parse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Builds the reorder list: parts whose stock is below their minimum, with how many to order. The first two columns
 * (Mouser Part Number, Quantity) are what Mouser's BOM tool asks for, so the file can be pasted or uploaded there.
 */
public final class ReorderList {
    private ReorderList() {}

    public static final List<String> HEADER = Collections.unmodifiableList(Arrays.asList(
            "Mouser Part Number", "Quantity", "DigiKey Part Number", "Manufacturer Part Number", "Manufacturer",
            "Description", "On Hand", "Minimum", "Last Unit Price", "Link"));

    /** One part as stored; strings may be null or blank. */
    public static final class Item {
        public String distributor = "", distributorPn = "", mpn = "", manufacturer = "", description = "", link = "";
        public int onHand;
        public Integer minimum;
        public Double unitCost;
    }

    /** True when the part has a minimum set and stock is at or below it (the "Low stock" filter). */
    public static boolean isLow(int onHand, Integer minimum) {
        return minimum != null && minimum > 0 && onHand <= minimum;
    }

    /** How many to order to get back to the minimum, rounded up to a sensible amount; 0 when not below it. */
    public static int orderQuantity(int onHand, Integer minimum) {
        if (minimum == null || minimum <= 0) return 0;
        int need = minimum - Math.max(0, onHand);
        return need <= 0 ? 0 : roundUp(need);
    }

    /**
     * Rounds an order up so it isn't an odd number like 37: exact below 10, then to the next 5 (below 100),
     * 10 (below 500), 50 (below 1000), 100 (below 5000) and 500 after that.
     */
    public static int roundUp(int need) {
        if (need <= 0) return 0;
        int step;
        if (need < 10) step = 1;
        else if (need < 100) step = 5;
        else if (need < 500) step = 10;
        else if (need < 1000) step = 50;
        else if (need < 5000) step = 100;
        else step = 500;
        return ((need + step - 1) / step) * step;
    }

    static boolean isMouser(Item it) {
        return lower(it.distributor).contains("mouser") || lower(it.link).contains("mouser.");
    }

    static boolean isDigiKey(Item it) {
        String d = lower(it.distributor).replace("-", "").replace(" ", "");
        return d.contains("digikey") || lower(it.link).contains("digikey.") || upper(it.distributorPn).endsWith("-ND");
    }

    /** Rows for every part below its minimum, in the given order. */
    public static List<List<String>> rows(List<Item> items) {
        List<List<String>> out = new ArrayList<>();
        for (Item it : items) {
            int qty = orderQuantity(it.onHand, it.minimum);
            if (qty <= 0) continue;
            String pn = nz(it.distributorPn).trim();
            boolean mouser = isMouser(it);
            boolean digikey = !mouser && isDigiKey(it);
            out.add(Arrays.asList(
                    mouser ? pn : "",
                    Integer.toString(qty),
                    digikey ? pn : "",
                    nz(it.mpn).trim(),
                    nz(it.manufacturer).trim(),
                    nz(it.description).trim(),
                    Integer.toString(it.onHand),
                    Integer.toString(it.minimum),
                    it.unitCost == null ? "" : price(it.unitCost),
                    nz(it.link).trim()));
        }
        return out;
    }

    /** The reorder CSV (header plus one line per part to order). */
    public static String csv(List<Item> items) {
        return InventoryCsv.write(HEADER, rows(items));
    }

    static String price(double d) {
        return BigDecimal.valueOf(d).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String lower(String s) {
        return nz(s).toLowerCase(Locale.ROOT);
    }

    private static String upper(String s) {
        return nz(s).toUpperCase(Locale.ROOT).trim();
    }
}
