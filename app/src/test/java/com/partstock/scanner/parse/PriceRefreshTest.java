package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
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
}
