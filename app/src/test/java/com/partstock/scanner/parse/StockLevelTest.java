package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StockLevelTest {

    @Test
    public void noMinimumMeansNoBar() {
        assertFalse(StockLevel.tracked(null));
        assertFalse(StockLevel.tracked(0));
        assertEquals(StockLevel.Band.NONE, StockLevel.band(5, null));
        assertEquals(StockLevel.Band.NONE, StockLevel.band(0, 0));
        assertEquals(0f, StockLevel.fill(5, 0), 0f);
        assertTrue(Double.isInfinite(StockLevel.ratio(5, null)));
        assertTrue(Double.isInfinite(StockLevel.ratio(5, 0)));
    }

    @Test
    public void bandsAroundTheMinimum() {
        assertEquals(StockLevel.Band.LOW, StockLevel.band(0, 10));
        assertEquals(StockLevel.Band.LOW, StockLevel.band(10, 10));
        assertEquals(StockLevel.Band.NEAR, StockLevel.band(11, 10));
        assertEquals(StockLevel.Band.NEAR, StockLevel.band(12, 10));
        assertEquals(StockLevel.Band.OK, StockLevel.band(13, 10));
        // 25% above 4 is exactly 5.
        assertEquals(StockLevel.Band.NEAR, StockLevel.band(5, 4));
        assertEquals(StockLevel.Band.OK, StockLevel.band(6, 4));
        assertEquals(StockLevel.Band.LOW, StockLevel.band(-3, 10));
    }

    @Test
    public void fillIsQuantityOverTwiceMinimum() {
        assertEquals(0f, StockLevel.fill(0, 10), 1e-6);
        assertEquals(0.25f, StockLevel.fill(5, 10), 1e-6);
        assertEquals(StockLevel.TICK, StockLevel.fill(10, 10), 1e-6);
        assertEquals(1f, StockLevel.fill(20, 10), 1e-6);
        assertEquals(1f, StockLevel.fill(Integer.MAX_VALUE, 10), 1e-6);
        assertEquals(0f, StockLevel.fill(-5, 10), 1e-6);
    }

    @Test
    public void hugeNumbersDontOverflow() {
        assertEquals(StockLevel.Band.OK, StockLevel.band(Integer.MAX_VALUE, Integer.MAX_VALUE / 2));
        assertEquals(StockLevel.Band.LOW, StockLevel.band(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(StockLevel.Band.NEAR, StockLevel.band(Integer.MAX_VALUE - 1, Integer.MAX_VALUE - 100));
        assertEquals(1.0, StockLevel.ratio(Integer.MAX_VALUE, Integer.MAX_VALUE), 1e-9);
    }

    @Test
    public void ratioSortsEmptyFirst() {
        assertEquals(0.0, StockLevel.ratio(0, 10), 0);
        assertEquals(0.5, StockLevel.ratio(5, 10), 1e-9);
        assertTrue(StockLevel.ratio(5, 10) < StockLevel.ratio(3, 4));
        assertTrue(StockLevel.ratio(1000, 1) < StockLevel.ratio(0, null));
    }

    @Test
    public void spokenDescription() {
        assertEquals("5 in stock, minimum 10, low", StockLevel.describe(5, 10));
        assertEquals("12 in stock, minimum 10, nearly low", StockLevel.describe(12, 10));
        assertEquals("40 in stock, minimum 10", StockLevel.describe(40, 10));
        assertEquals("7 in stock", StockLevel.describe(7, null));
    }
}
