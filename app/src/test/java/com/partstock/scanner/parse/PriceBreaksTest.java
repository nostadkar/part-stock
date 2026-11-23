package com.partstock.scanner.parse;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PriceBreaksTest {

    @Test
    public void picksBreakForQuantity() {
        int[] q = {1, 10, 100, 1000};
        assertEquals(0, PriceBreaks.pick(q, 1));
        assertEquals(0, PriceBreaks.pick(q, 9));
        assertEquals(1, PriceBreaks.pick(q, 10));
        assertEquals(2, PriceBreaks.pick(q, 500));
        assertEquals(3, PriceBreaks.pick(q, 5000));
    }

    @Test
    public void belowFirstBreakOrUnsorted() {
        assertEquals(1, PriceBreaks.pick(new int[]{100, 10}, 5));
        assertEquals(0, PriceBreaks.pick(new int[]{100, 10}, 150));
        assertEquals(-1, PriceBreaks.pick(new int[0], 5));
    }

    @Test
    public void digikeyCategoryNamesFallInBuckets() {
        assertEquals(Categories.RESISTORS, Categories.classify("Chip Resistor - Surface Mount"));
        assertEquals(Categories.TRANSISTORS, Categories.classify("Single FETs, MOSFETs"));
        assertEquals(Categories.TRANSISTORS, Categories.classify("Single Bipolar Transistors"));
        assertEquals(Categories.CAPACITORS, Categories.classify("Ceramic Capacitors"));
        assertEquals(Categories.POWER, Categories.classify("Voltage Regulators - DC DC Switching Regulators"));
        assertEquals(Categories.CONNECTORS, Categories.classify("Rectangular Connectors - Headers, Male Pins"));
        assertEquals(Categories.PROTECTION, Categories.classify("TVS Diodes"));
        assertEquals(Categories.INDUCTORS, Categories.classify("Fixed Inductors"));
        assertEquals(Categories.MCU, Categories.classify("Microcontrollers"));
    }

    private static String fmt(double d) { return "$" + d; }

    @Test
    public void nextBreakHint() {
        int[] q = {1, 10, 100, 1000};
        double[] p = {0.10, 0.05, 0.02, 0.012};
        assertEquals("Buy 400 more to reach the 1,000+ break ($0.012 each)",
                PriceBreaks.nextBreakHint(q, p, 600, PriceBreaksTest::fmt));
        assertEquals("Buy 7 more to reach the 10+ break ($0.05 each)", PriceBreaks.nextBreakHint(q, p, 3, PriceBreaksTest::fmt));
        assertEquals("Buy 90 more to reach the 100+ break ($0.02 each)", PriceBreaks.nextBreakHint(q, p, 10, PriceBreaksTest::fmt));
        assertNull("already at the last break", PriceBreaks.nextBreakHint(q, p, 5000, PriceBreaksTest::fmt));
        assertNull("next break isn't cheaper", PriceBreaks.nextBreakHint(new int[] {1, 10}, new double[] {0.1, 0.1}, 2, PriceBreaksTest::fmt));
        assertNull("below the first break", PriceBreaks.nextBreakHint(new int[] {10, 100}, new double[] {0.1, 0.05}, 4, PriceBreaksTest::fmt));
        assertNull(PriceBreaks.nextBreakHint(new int[0], new double[0], 4, PriceBreaksTest::fmt));
        assertNull(PriceBreaks.nextBreakHint(q, new double[] {1}, 4, PriceBreaksTest::fmt));
        // Unsorted input.
        assertEquals("Buy 5 more to reach the 10+ break ($0.05 each)",
                PriceBreaks.nextBreakHint(new int[] {100, 1, 10}, new double[] {0.02, 0.1, 0.05}, 5, PriceBreaksTest::fmt));
    }

    @Test
    public void orderSortsByQuantity() {
        assertArrayEquals(new Object[] {1, 2, 0}, box(PriceBreaks.order(new int[] {100, 1, 10})));
    }

    private static Object[] box(int[] a) {
        Object[] o = new Object[a.length];
        for (int i = 0; i < a.length; i++) o[i] = a[i];
        return o;
    }
}
