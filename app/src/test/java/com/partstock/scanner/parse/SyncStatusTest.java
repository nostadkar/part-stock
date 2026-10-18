package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SyncStatusTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final long MIN = 60_000L;

    @Test
    public void ago() {
        assertEquals("never", SyncStatus.ago(0, NOW));
        assertEquals("just now", SyncStatus.ago(NOW - 5_000, NOW));
        assertEquals("just now", SyncStatus.ago(NOW + 5_000, NOW)); // clock skew
        assertEquals("2 min ago", SyncStatus.ago(NOW - 2 * MIN - 10_000, NOW));
        assertEquals("59 min ago", SyncStatus.ago(NOW - 59 * MIN, NOW));
        assertEquals("1 h ago", SyncStatus.ago(NOW - 60 * MIN, NOW));
        assertEquals("23 h ago", SyncStatus.ago(NOW - 23 * 60 * MIN, NOW));
        assertEquals("yesterday", SyncStatus.ago(NOW - 30 * 60 * MIN, NOW));
        assertEquals("5 days ago", SyncStatus.ago(NOW - 5 * 24 * 60 * MIN, NOW));
    }

    @Test
    public void line() {
        assertEquals("Not synced yet", SyncStatus.line(0, "", NOW));
        assertEquals("Last synced 2 min ago", SyncStatus.line(NOW - 2 * MIN, null, NOW));
        assertEquals("Not synced: no connection (last synced 3 h ago). Retries on the next change.",
                SyncStatus.line(NOW - 3 * 60 * MIN, "no connection", NOW));
        assertEquals("Not synced: lost access to the file. Retries on the next change.",
                SyncStatus.line(0, " lost access to the file ", NOW));
    }
}
