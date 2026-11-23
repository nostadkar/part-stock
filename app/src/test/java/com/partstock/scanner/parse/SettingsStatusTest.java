package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.partstock.scanner.parse.SettingsStatus.Level;
import com.partstock.scanner.parse.SettingsStatus.Status;
import org.junit.Test;

public class SettingsStatusTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final long HOUR = 60 * 60_000L;
    private static final long DAY = 24 * HOUR;

    @Test
    public void keys() {
        assertEquals("OK: Key saved", SettingsStatus.key(true, "Key saved").toString());
        assertEquals("OFF: Not set", SettingsStatus.key(false, "Key saved").toString());
        assertFalse(SettingsStatus.key(false, "Saved").isProblem());
    }

    @Test
    public void backupBands() {
        assertEquals("OFF: Off", SettingsStatus.backup(false, NOW - HOUR, false, NOW).toString());
        assertEquals("ERROR: Backup failed", SettingsStatus.backup(true, NOW - HOUR, true, NOW).toString());
        assertEquals("WORKING: Waiting for the next save", SettingsStatus.backup(true, 0, false, NOW).toString());
        assertEquals("OK: Last backup 2 h ago", SettingsStatus.backup(true, NOW - 2 * HOUR, false, NOW).toString());
        assertEquals("OK: Last backup 7 days ago", SettingsStatus.backup(true, NOW - 7 * DAY, false, NOW).toString());
        Status stale = SettingsStatus.backup(true, NOW - 8 * DAY, false, NOW);
        assertEquals("WARN: Last backup 8 days ago", stale.toString());
        assertTrue(stale.isProblem());
        assertTrue(SettingsStatus.backup(true, 0, true, NOW).isProblem());
    }

    @Test
    public void backupFailedLine() {
        assertTrue(SettingsStatus.backupFailed("Last backup failed: no space"));
        assertFalse(SettingsStatus.backupFailed("Last backup: part-stock-2026.csv"));
        assertFalse(SettingsStatus.backupFailed(""));
        assertFalse(SettingsStatus.backupFailed(null));
    }

    @Test
    public void sync() {
        assertEquals("OFF: Off", SettingsStatus.sync(false, 0, "", NOW).toString());
        assertEquals("ERROR: Not synced", SettingsStatus.sync(true, NOW - HOUR, "no connection", NOW).toString());
        assertEquals("WORKING: Not synced yet", SettingsStatus.sync(true, 0, " ", NOW).toString());
        assertEquals("OK: Synced just now", SettingsStatus.sync(true, NOW - 1000, null, NOW).toString());
    }

    @Test
    public void app() {
        assertEquals("WARN: Update available", SettingsStatus.app(40, 42, "build 40").toString());
        assertEquals("OK: Up to date", SettingsStatus.app(42, 42, "build 42").toString());
        assertEquals("OK: build 42", SettingsStatus.app(42, 0, "build 42").toString());
        assertEquals(Level.OK, SettingsStatus.app(43, 42, "build 43").level);
    }
}
