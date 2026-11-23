package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class PriceRefreshTest {

    @Test
    public void picksPartsWithMouserNumbers() {
        assertTrue(PriceRefresh.hasMouserPn("Mouser", "603-RC0603FR-0710KL", ""));
        assertTrue(PriceRefresh.hasMouserPn("", "603-RC0603FR-0710KL",
                "https://www.mouser.com/ProductDetail/603-RC0603FR-0710KL"));
        assertFalse(PriceRefresh.hasMouserPn("DigiKey", "2SK3019-TPMSCT-ND", ""));
        assertFalse(PriceRefresh.hasMouserPn("Mouser", " ", ""));
        assertFalse(PriceRefresh.hasMouserPn("", "811755-101", "https://www.mouser.com/c/?q=2SK3019"));
    }

    @Test
    public void summaryText() {
        assertEquals("Updated 38, not found 2", PriceRefresh.summary(38, 2, 0, null, 0));
        assertEquals("Updated 10, not found 0, failed 1. Stopped: Mouser rate limit. 5 not checked",
                PriceRefresh.summary(10, 0, 1, "Mouser rate limit", 5));
    }

    @Test
    public void labelShowsSourceAndDate() {
        LocalDate today = LocalDate.of(2026, 10, 18);
        assertEquals("Price from Mouser, Oct 18", PriceRefresh.label("Mouser", "2026-10-18 09:12:00", today));
        assertEquals("Price from DigiKey, Dec 3, 2025", PriceRefresh.label("DigiKey", "2025-12-03 10:00:00", today));
        assertEquals("", PriceRefresh.label("", "2026-10-18 09:12:00", today));
        assertEquals("", PriceRefresh.label("Mouser", "", today));
    }

    @Test
    public void rateLimitDetection() {
        assertTrue(PriceRefresh.isRateLimit(429, ""));
        assertTrue(PriceRefresh.isRateLimit(200, "Maximum calls per minute exceeded."));
        assertFalse(PriceRefresh.isRateLimit(200, "Invalid unique identifier."));
    }

    @Test
    public void freshnessBands() {
        LocalDate today = LocalDate.of(2026, 11, 23);
        assertEquals(PriceRefresh.Freshness.FRESH, PriceRefresh.freshness("Mouser", "2026-11-23 08:00:00", today));
        assertEquals(PriceRefresh.Freshness.FRESH, PriceRefresh.freshness("Mouser", "2026-10-25 08:00:00", today));
        assertEquals(PriceRefresh.Freshness.AGING, PriceRefresh.freshness("DigiKey", "2026-10-24 08:00:00", today));
        assertEquals(PriceRefresh.Freshness.AGING, PriceRefresh.freshness("Mouser", "2026-08-26 08:00:00", today));
        assertEquals(PriceRefresh.Freshness.OLD, PriceRefresh.freshness("Mouser", "2026-08-25 08:00:00", today));
        assertEquals(PriceRefresh.Freshness.OLD, PriceRefresh.freshness("Mouser", "garbage", today));
        assertEquals(PriceRefresh.Freshness.NEVER, PriceRefresh.freshness("", "2026-11-23 08:00:00", today));
        assertEquals(PriceRefresh.Freshness.NEVER, PriceRefresh.freshness("Mouser", " ", today));
        assertEquals(PriceRefresh.Freshness.NEVER, PriceRefresh.freshness(null, null, today));
        assertEquals("30+ days old", PriceRefresh.freshnessText(PriceRefresh.Freshness.AGING));
        assertEquals("Never checked", PriceRefresh.freshnessText(PriceRefresh.Freshness.NEVER));
    }

    @Test
    public void kindsOfChange() {
        assertEquals(PriceRefresh.Kind.UP, PriceRefresh.kind(0.10, 0.12));
        assertEquals(PriceRefresh.Kind.DOWN, PriceRefresh.kind(0.10, 0.08));
        assertEquals(PriceRefresh.Kind.SAME, PriceRefresh.kind(0.1, 0.10001));
        assertEquals(PriceRefresh.Kind.SAME, PriceRefresh.kind(0.1, null));
        assertEquals(PriceRefresh.Kind.NEW_PRICE, PriceRefresh.kind(null, 0.5));
        assertEquals(PriceRefresh.Kind.NO_PRICE, PriceRefresh.kind(null, null));
        assertEquals(20.0, PriceRefresh.percent(0.10, 0.12), 1e-9);
        assertEquals(null, PriceRefresh.percent(0.0, 0.12));
        assertEquals(null, PriceRefresh.percent(null, 0.12));
    }

    @Test
    public void moversRankedByPercent() {
        List<PriceRefresh.Change> all = Arrays.asList(
                new PriceRefresh.Change(1, "R1", 0.10, 0.11),   // +10%
                new PriceRefresh.Change(2, "C1", 1.00, 0.50),   // -50%
                new PriceRefresh.Change(3, "U1", 2.00, 2.00),   // same
                new PriceRefresh.Change(4, "U2", null, 3.00),   // newly priced
                new PriceRefresh.Change(5, "Q1", 0.20, 0.30),   // +50%, bigger abs than C1? no: 0.1 < 0.5
                new PriceRefresh.Change(6, "L1", 4.00, 4.40));  // +10%, bigger abs than R1
        List<PriceRefresh.Change> m = PriceRefresh.movers(all, 3);
        assertEquals(3, m.size());
        assertEquals("C1", m.get(0).key); // 50%, abs 0.5
        assertEquals("Q1", m.get(1).key); // 50%, abs 0.1
        assertEquals("L1", m.get(2).key); // 10%, abs 0.4 beats R1
        assertEquals(Arrays.asList(1L, 2L, 4L, 5L, 6L), PriceRefresh.changedIds(all));
        assertEquals("3 up, 1 down \u00B7 1 newly priced \u00B7 1 unchanged \u00B7 2 not found",
                PriceRefresh.resultCounts(all, 2, 0));
        assertEquals("No price changes \u00B7 1 unchanged \u00B7 1 failed",
                PriceRefresh.resultCounts(Arrays.asList(new PriceRefresh.Change(3, "U1", 2.0, 2.0)), 0, 1));
    }

    @Test
    public void moverFormatting() {
        assertEquals("\u25B2 12% \u00B7 0.105 \u2192 0.1176",
                PriceRefresh.moverText(new PriceRefresh.Change(1, "R1", 0.105, 0.1176)));
        assertEquals("\u25BC 2.5% \u00B7 4.00 \u2192 3.90",
                PriceRefresh.moverText(new PriceRefresh.Change(1, "R1", 4.0, 3.9)));
        assertEquals("", PriceRefresh.moverText(new PriceRefresh.Change(1, "R1", null, 3.9)));
        assertEquals("<0.1%", PriceRefresh.percentText(0.01));
        assertEquals("0.10", PriceRefresh.cost(0.1));
        assertEquals("0.1046", PriceRefresh.cost(0.10456));
    }
}
