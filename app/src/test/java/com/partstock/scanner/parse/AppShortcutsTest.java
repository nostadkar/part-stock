package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Launcher shortcut intents → start screen. */
public class AppShortcutsTest {

    @Test
    public void plainLaunchKeepsTheSavedScreen() {
        assertNull(AppShortcuts.resolve(null, null));
        assertNull(AppShortcuts.resolve("android.intent.action.MAIN", null));
    }

    @Test
    public void staticShortcuts() {
        assertEquals(AppShortcuts.Start.SCAN, AppShortcuts.resolve(AppShortcuts.ACTION_SCAN, null));
        assertEquals(AppShortcuts.Start.INVENTORY, AppShortcuts.resolve(AppShortcuts.ACTION_INVENTORY, null));
        assertEquals(AppShortcuts.Start.LOW_STOCK, AppShortcuts.resolve(AppShortcuts.ACTION_LOW_STOCK, null));
        assertEquals(AppShortcuts.Start.SEARCH, AppShortcuts.resolve(AppShortcuts.ACTION_SEARCH, null));
    }

    @Test
    public void unknownOrRemovedFallsBackToScan() {
        assertEquals(AppShortcuts.Start.SCAN, AppShortcuts.resolve("com.partstock.scanner.action.OLD_THING", null));
        assertEquals(AppShortcuts.Start.SCAN, AppShortcuts.resolve(AppShortcuts.ACTION_SCAN_INTO, " "));
        assertEquals(AppShortcuts.Start.SCAN_INTO, AppShortcuts.resolve(AppShortcuts.ACTION_SCAN_INTO, "A3"));
    }

    @Test
    public void scanIntoUsesTheBatchBinInBatchMode() {
        assertEquals("B2", AppShortcuts.scanIntoBin(true, "B2", "A3"));
        assertEquals("A3", AppShortcuts.scanIntoBin(false, "B2", " A3 "));
        assertEquals("", AppShortcuts.scanIntoBin(false, "B2", ""));
        assertEquals("", AppShortcuts.scanIntoBin(false, "", "Unassigned"));
        assertEquals("Scan into A3", AppShortcuts.scanIntoLabel("A3"));
    }
}
