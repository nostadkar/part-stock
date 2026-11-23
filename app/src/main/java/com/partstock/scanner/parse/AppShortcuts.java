package com.partstock.scanner.parse;

/**
 * Launcher shortcuts (long-press the app icon): the intent actions they send and where each one starts the app.
 * Shared by res/xml/shortcuts.xml (static ones), the dynamic "Scan into A3" shortcut and MainActivity.
 */
public final class AppShortcuts {
    private AppShortcuts() {}

    public static final String ACTION_SCAN = "com.partstock.scanner.action.SCAN";
    public static final String ACTION_INVENTORY = "com.partstock.scanner.action.INVENTORY";
    public static final String ACTION_LOW_STOCK = "com.partstock.scanner.action.LOW_STOCK";
    public static final String ACTION_SEARCH = "com.partstock.scanner.action.SEARCH";
    public static final String ACTION_SCAN_INTO = "com.partstock.scanner.action.SCAN_INTO";
    /** The bin for {@link #ACTION_SCAN_INTO}. */
    public static final String EXTRA_BIN = "bin";

    /** Id of the dynamic "Scan into <bin>" shortcut. */
    public static final String ID_SCAN_INTO = "scan_into";

    public enum Start { SCAN, INVENTORY, LOW_STOCK, SEARCH, SCAN_INTO }

    /**
     * Where an incoming intent starts. Null for a plain launch (no shortcut, keep the saved screen); anything
     * unknown, like a pinned shortcut from an older version or "Scan into" without a bin, falls back to Scan.
     */
    public static Start resolve(String action, String bin) {
        if (action == null || action.isEmpty() || action.equals("android.intent.action.MAIN")) return null;
        switch (action) {
            case ACTION_INVENTORY: return Start.INVENTORY;
            case ACTION_LOW_STOCK: return Start.LOW_STOCK;
            case ACTION_SEARCH: return Start.SEARCH;
            case ACTION_SCAN_INTO:
                return bin == null || bin.trim().isEmpty() ? Start.SCAN : Start.SCAN_INTO;
            default: return Start.SCAN;
        }
    }

    /** The bin the dynamic shortcut offers: the batch bin in batch mode, else the last bin saved to; "" = none. */
    public static String scanIntoBin(boolean batchMode, String batchBin, String lastLocation) {
        String b = batchMode ? batchBin : lastLocation;
        return Locations.plain(b);
    }

    /** Launcher label, short enough for the shortcut list ("Scan into A3"). */
    public static String scanIntoLabel(String bin) {
        return "Scan into " + bin.trim();
    }
}
