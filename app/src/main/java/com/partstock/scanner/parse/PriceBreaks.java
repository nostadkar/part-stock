package com.partstock.scanner.parse;

/** Picks the price break that applies to a quantity (shared by the Mouser and DigiKey lookups). */
public final class PriceBreaks {
    private PriceBreaks() {}

    /**
     * Index of the largest break quantity that is at most {@code qty}; if {@code qty} is below every break, the
     * smallest break. -1 when there are no breaks.
     */
    public static int pick(int[] breakQty, int qty) {
        int best = -1, lowest = -1;
        for (int i = 0; i < breakQty.length; i++) {
            int q = breakQty[i];
            if (lowest < 0 || q < breakQty[lowest]) lowest = i;
            if (q <= qty && (best < 0 || q > breakQty[best])) best = i;
        }
        return best >= 0 ? best : lowest;
    }

    /**
     * "Buy 400 more to reach the 1,000+ break (0.012 each)": the next break above the one that applies to
     * {@code qty}, or null when {@code qty} is already at the last break, the next one isn't cheaper, or the input
     * is unusable. Prices are per piece, in the order of {@code breakQty}; {@code fmt} formats a price.
     */
    public static String nextBreakHint(int[] breakQty, double[] price, int qty, java.util.function.DoubleFunction<String> fmt) {
        if (breakQty == null || price == null || breakQty.length != price.length || breakQty.length == 0) return null;
        int cur = pick(breakQty, qty);
        int next = -1;
        for (int i = 0; i < breakQty.length; i++) {
            int q = breakQty[i];
            if (q > Math.max(qty, 0) && (next < 0 || q < breakQty[next])) next = i;
        }
        if (next < 0 || next == cur) return null;
        if (cur >= 0 && breakQty[cur] <= qty && !(price[next] < price[cur])) return null;
        int more = breakQty[next] - Math.max(qty, 0);
        return "Buy " + String.format(java.util.Locale.US, "%,d", more) + " more to reach the "
                + String.format(java.util.Locale.US, "%,d", breakQty[next]) + "+ break (" + fmt.apply(price[next]) + " each)";
    }

    /** Indexes of the breaks sorted by quantity (APIs usually list them in order, but don't promise it). */
    public static int[] order(int[] breakQty) {
        Integer[] idx = new Integer[breakQty.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Integer.compare(breakQty[a], breakQty[b]));
        int[] out = new int[idx.length];
        for (int i = 0; i < idx.length; i++) out[i] = idx[i];
        return out;
    }
}
