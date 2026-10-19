package com.partstock.scanner.parse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Batch scanning: decides whether a queued bag is confident enough to be saved with "Save all confident", or needs
 * the normal review screen (pure Java, unit tested). A bag is confident only when every rule passes: a part number
 * and a bag quantity were read, a Mouser/DigiKey lookup found exactly that part number, and the bag is not a
 * duplicate (saved before, or scanned twice in this batch).
 */
public final class BatchRules {
    private BatchRules() {}

    /** No lookup was possible (no API key, or automatic lookup turned off). */
    public static final String LOOKUP_NONE = "none";
    /** Waiting in the lookup queue, or running. */
    public static final String LOOKUP_PENDING = "pending";
    /** The distributor listed exactly the part number that was read. */
    public static final String LOOKUP_EXACT = "exact";
    /** Found, but only after correcting the part number (a likely misread): check it. */
    public static final String LOOKUP_CORRECTED = "corrected";
    public static final String LOOKUP_NOT_FOUND = "not_found";
    /** Network error, bad key and so on. */
    public static final String LOOKUP_FAILED = "failed";

    /** {@link #sameBag} results. */
    public static final int DIFFERENT = 0;
    /** Same part and quantity, but neither label shows order details to tell the bags apart. */
    public static final int MAYBE_SAME = 1;
    /** Same order details (PO + line item, or DigiKey PN + PO + qty): the same physical bag. */
    public static final int SAME = 2;

    /**
     * Why the bag can't be saved without review; empty = confident. [savedBefore]: this bag's fingerprint is in the
     * scan log already. [sameBagInBatch]: {@link #sameBag} against the earlier bags still in the batch (the highest).
     */
    public static List<String> reasons(ParsedPart p, String lookup, boolean savedBefore, int sameBagInBatch) {
        if (p == null) return Collections.singletonList("Nothing read");
        List<String> out = new ArrayList<>();
        if (savedBefore) out.add("This bag was saved before");
        if (sameBagInBatch == SAME) out.add("Same bag scanned twice in this batch");
        else if (sameBagInBatch == MAYBE_SAME) out.add("Same part and quantity as another bag in this batch");
        if (p.inventoryKey() == null) out.add("No part number read");
        if (p.quantity == null || p.quantity <= 0) out.add("No quantity read");
        else if (p.kitPerBoard != null && !ParsedPart.notBlank(p.distributorPn)) {
            // A kit label alone prints the per-board quantity, not what's in the bag.
            out.add("Kit label quantity is per board");
        }
        String l = lookup == null ? LOOKUP_NONE : lookup;
        switch (l) {
            case LOOKUP_EXACT:
                break;
            case LOOKUP_PENDING:
                out.add("Still looking up");
                break;
            case LOOKUP_CORRECTED:
                out.add("Lookup corrected the part number");
                break;
            case LOOKUP_NOT_FOUND:
                out.add("Not found on Mouser/DigiKey");
                break;
            case LOOKUP_FAILED:
                out.add("Lookup failed");
                break;
            default:
                out.add("Not looked up (add a Mouser or DigiKey key in Settings)");
        }
        return out;
    }

    public static boolean isDuplicate(boolean savedBefore, int sameBagInBatch) {
        return savedBefore || sameBagInBatch == SAME;
    }

    /** Compares two scanned bags: {@link #SAME}, {@link #MAYBE_SAME} or {@link #DIFFERENT}. */
    public static int sameBag(ParsedPart a, ParsedPart b) {
        if (a == null || b == null) return DIFFERENT;
        String fa = a.bagFingerprint();
        String fb = b.bagFingerprint();
        if (fa != null && fb != null) return fa.equals(fb) ? SAME : DIFFERENT;
        if (fa != null || fb != null) return DIFFERENT; // one label shows order details, the other doesn't
        String ka = a.inventoryKey();
        String kb = b.inventoryKey();
        if (ka == null || kb == null || a.quantity == null || !a.quantity.equals(b.quantity)) return DIFFERENT;
        return squash(ka).equals(squash(kb)) ? MAYBE_SAME : DIFFERENT;
    }

    /**
     * True when a lookup result's part numbers ([foundA], [foundB]: e.g. Mouser PN and MPN) include the one that was
     * searched for, ignoring case and spaces.
     */
    public static boolean exactHit(String searched, String foundA, String foundB) {
        if (searched == null || searched.trim().isEmpty()) return false;
        String s = squash(searched);
        return (foundA != null && squash(foundA).equals(s)) || (foundB != null && squash(foundB).equals(s));
    }

    private static String squash(String s) {
        return s.toUpperCase(Locale.US).replaceAll("\\s+", "");
    }
}
