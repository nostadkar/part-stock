package com.partstock.scanner.parse;

/**
 * One-line preview of what the review screen's Save does, shown in the sticky bar above the button:
 * "Add 6 → 18 in bin A3", "New part · 6 in A3", "Set count to 6 in A3 → 14 total".
 */
public final class SaveOutcome {
    private SaveOutcome() {}

    /**
     * @param hasKey     a part number is filled in (otherwise there's nothing to save)
     * @param qty        the quantity typed, or null if it isn't a whole number
     * @param addMode    "Add to stock" (true) or "Set count to this" (false)
     * @param bin        the bin typed (blank = the part's main bin, or none for a new part)
     * @param stockTotal the saved part's total across bins, or null for a new part
     * @param binQty     the saved part's count in that bin (ignored for a new part)
     * @param mainBin    the saved part's main bin, used when [bin] is blank
     */
    public static String preview(boolean hasKey, Integer qty, boolean addMode, String bin,
                                 Integer stockTotal, int binQty, String mainBin) {
        if (!hasKey) return "Enter a part number to save";
        if (qty == null || qty < 0) return "Enter a quantity to save";
        String typed = Locations.plain(bin);
        if (stockTotal == null) {
            return "New part · " + qty + (typed.isEmpty() ? ", no bin" : " in " + typed);
        }
        String where = typed.isEmpty() ? Locations.plain(mainBin) : typed;
        String inBin = where.isEmpty() ? "" : " in bin " + where;
        if (addMode) {
            return "Add " + qty + " → " + (stockTotal + qty) + inBin;
        }
        int after = stockTotal - binQty + qty;
        String s = "Set count to " + qty + (where.isEmpty() ? "" : " in " + where);
        return after == qty ? s : s + " → " + after + " total";
    }
}
