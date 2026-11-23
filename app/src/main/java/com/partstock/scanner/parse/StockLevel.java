package com.partstock.scanner.parse;

/**
 * Stock against a part's minimum, for the stock bars and the "Lowest stock first" sort (pure Java, unit tested).
 * The bar is filled to quantity / (2 × minimum), so the minimum tick sits in the middle; parts without a minimum
 * get no bar.
 */
public final class StockLevel {
    private StockLevel() {}

    /** How the bar is colored. */
    public enum Band {
        /** No minimum set: no bar. */
        NONE,
        /** Above minimum × 1.25. */
        OK,
        /** Above the minimum, but within 25% of it. */
        NEAR,
        /** At or below the minimum. */
        LOW,
    }

    /** True when the part has a minimum worth drawing a bar for. */
    public static boolean tracked(Integer minimum) {
        return minimum != null && minimum > 0;
    }

    public static Band band(int quantity, Integer minimum) {
        if (!tracked(minimum)) return Band.NONE;
        long q = Math.max(0, quantity), m = minimum;
        if (q <= m) return Band.LOW;
        // q ≤ 1.25 m, in whole numbers: 4q ≤ 5m.
        if (4 * q <= 5 * m) return Band.NEAR;
        return Band.OK;
    }

    /** The bar fill, 0…1: quantity / (2 × minimum). 0 without a minimum. */
    public static float fill(int quantity, Integer minimum) {
        if (!tracked(minimum)) return 0f;
        double f = Math.max(0, quantity) / (2.0 * minimum);
        return (float) Math.min(1.0, f);
    }

    /** Where the minimum tick goes on the bar. */
    public static final float TICK = 0.5f;

    /**
     * The sort key for "Lowest stock first": quantity ÷ minimum (0 = empty, 1 = at the minimum). Parts without a
     * minimum sort after all others (positive infinity).
     */
    public static double ratio(int quantity, Integer minimum) {
        if (!tracked(minimum)) return Double.POSITIVE_INFINITY;
        return Math.max(0, quantity) / (double) minimum;
    }

    /** "5 of minimum 10, low" — what TalkBack reads for the bar. */
    public static String describe(int quantity, Integer minimum) {
        if (!tracked(minimum)) return quantity + " in stock";
        String s = quantity + " in stock, minimum " + minimum;
        switch (band(quantity, minimum)) {
            case LOW: return s + ", low";
            case NEAR: return s + ", nearly low";
            default: return s;
        }
    }
}
