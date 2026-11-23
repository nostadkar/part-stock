package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class PartSortTest {

    private static final class P implements PartSort.Item {
        final String key, updated;
        final int qty;
        final Double cost;
        final Integer min;

        P(String key, int qty, Double cost, Integer min, String updated) {
            this.key = key;
            this.qty = qty;
            this.cost = cost;
            this.min = min;
            this.updated = updated;
        }

        @Override public String key() { return key; }
        @Override public int quantity() { return qty; }
        @Override public Double unitCost() { return cost; }
        @Override public Integer minQty() { return min; }
        @Override public String updatedAt() { return updated; }
    }

    // In "database" (category) order.
    private static final List<P> PARTS = Arrays.asList(
            new P("rc0603", 500, 0.01, null, "2026-01-02 09:00:00"), // value 5.00
            new P("LM358", 3, 0.40, 10, "2026-01-05 09:00:00"), // value 1.20, ratio 0.3
            new P("BAT54", 50, null, 100, "2026-01-01 09:00:00"), // unpriced, ratio 0.5
            new P("ATmega328", 3, 2.50, null, ""), // value 7.50
            new P("C0402", 0, 0.02, 5, "2026-01-03 09:00:00")); // value 0, ratio 0

    private static String keys(PartSort.Sort s) {
        StringBuilder sb = new StringBuilder();
        for (P p : PartSort.sorted(PARTS, s)) sb.append(sb.length() == 0 ? "" : ",").append(p.key);
        return sb.toString();
    }

    @Test
    public void categoryKeepsTheIncomingOrder() {
        assertEquals("rc0603,LM358,BAT54,ATmega328,C0402", keys(PartSort.Sort.CATEGORY));
        assertTrue(PartSort.Sort.CATEGORY.grouped());
        assertFalse(PartSort.Sort.NAME.grouped());
    }

    @Test
    public void nameIgnoresCase() {
        assertEquals("ATmega328,BAT54,C0402,LM358,rc0603", keys(PartSort.Sort.NAME));
    }

    @Test
    public void quantityBothWaysTiesByName() {
        assertEquals("rc0603,BAT54,ATmega328,LM358,C0402", keys(PartSort.Sort.QTY_HIGH));
        assertEquals("C0402,ATmega328,LM358,BAT54,rc0603", keys(PartSort.Sort.QTY_LOW));
    }

    @Test
    public void valueHighestFirstUnpricedLast() {
        assertEquals("ATmega328,rc0603,LM358,C0402,BAT54", keys(PartSort.Sort.VALUE));
        assertEquals(7.5, PartSort.value(PARTS.get(3)), 1e-9);
        assertEquals(0, PartSort.value(PARTS.get(2)), 0);
    }

    @Test
    public void recentNewestFirstUnknownLast() {
        assertEquals("LM358,C0402,rc0603,BAT54,ATmega328", keys(PartSort.Sort.RECENT));
    }

    @Test
    public void lowestStockFirstUntrackedLast() {
        assertEquals("C0402,LM358,BAT54,ATmega328,rc0603", keys(PartSort.Sort.LOWEST_STOCK));
    }

    @Test
    public void parseFallsBack() {
        assertEquals(PartSort.Sort.CATEGORY, PartSort.Sort.parse(null));
        assertEquals(PartSort.Sort.VALUE, PartSort.Sort.parse("VALUE"));
        assertEquals(PartSort.View.LIST, PartSort.View.parse("nope"));
        assertEquals(PartSort.View.GRID, PartSort.View.parse("GRID"));
    }
}
