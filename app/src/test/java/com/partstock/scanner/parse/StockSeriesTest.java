package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class StockSeriesTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 23);

    private static StockSeries.Entry e(String ts, Integer before, Integer after) {
        return new StockSeries.Entry(ts, before, after, false);
    }

    private static String s(double[] v) {
        StringBuilder b = new StringBuilder();
        for (double d : v) b.append(b.length() == 0 ? "" : " ").append((long) d);
        return b.toString();
    }

    @Test
    public void carriesForwardAndEndsAtCurrent() {
        // Newest first, like InventoryDb.history.
        double[] v = StockSeries.daily(Arrays.asList(
                e("2026-11-22 09:00:00", 8, 5),
                e("2026-11-20 18:00:00", 10, 8),
                e("2026-11-20 10:00:00", 0, 10)), TODAY, 0, 5);
        // 19th: 0 before the first scan; 20th: last change that day (8); 21st carried; 22nd: 5; today: current.
        assertEquals("0 8 8 5 5", s(v));
    }

    @Test
    public void windowStartsWithTheCountCarriedIn() {
        double[] v = StockSeries.daily(Arrays.asList(
                e("2026-11-22 09:00:00", 40, 12),
                e("2026-01-01 09:00:00", null, 40)), TODAY, 30, 12);
        assertEquals(30, v.length);
        assertEquals(40, v[0], 0);
        assertEquals(12, v[29], 0);
        assertEquals(12, v[28], 0);
        assertEquals(40, v[27], 0);
    }

    @Test
    public void skipsUndoneAndBadRows() {
        double[] v = StockSeries.daily(Arrays.asList(
                new StockSeries.Entry("2026-11-22 09:00:00", 10, 99, true),
                e("2026-11-22 10:00:00", 10, null),
                e("garbage", 1, 2),
                e("2026-11-21 10:00:00", null, 10)), TODAY, 90, 10);
        assertEquals("10 10 10", s(v));
    }

    @Test
    public void notEnoughHistory() {
        assertEquals(0, StockSeries.daily(Collections.emptyList(), TODAY, 90, 5).length);
        // One save today with nothing known before: a single point.
        assertEquals(1, StockSeries.daily(Collections.singletonList(e("2026-11-23 08:00:00", null, 5)), TODAY, 90, 5).length);
        // A future timestamp (clock change) counts as today.
        assertEquals("0 5", s(StockSeries.daily(Collections.singletonList(e("2027-01-01 08:00:00", 0, 5)), TODAY, 90, 5)));
    }

    @Test
    public void allIsCapped() {
        double[] v = StockSeries.daily(Collections.singletonList(e("1990-01-01 08:00:00", null, 5)), TODAY, 0, 5);
        assertEquals(StockSeries.MAX_DAYS, v.length);
    }
}
