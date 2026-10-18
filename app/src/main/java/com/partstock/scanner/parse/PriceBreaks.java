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
}
