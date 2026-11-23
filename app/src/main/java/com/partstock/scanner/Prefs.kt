package com.partstock.scanner

import android.content.Context
import com.partstock.scanner.parse.Locations

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("partstock", Context.MODE_PRIVATE)

    /** Bin/drawer used on the last save, pre-filled on the next scan. */
    var lastLocation: String
        get() = sp.getString("lastLocation", "") ?: ""
        set(v) {
            sp.edit().putString("lastLocation", v.trim()).apply()
            noteBin(v)
        }

    /** Bins used most recently, newest first (at most 5), offered as chips in the bin picker. */
    var recentBins: List<String>
        get() = (sp.getString("recentBins", "") ?: "").split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("recentBins", v.joinToString("\n")).apply()

    /** Records that [bin] was just used (blank and "Unassigned" are ignored). */
    fun noteBin(bin: String) {
        recentBins = Locations.remember(recentBins, bin.replace('\n', ' '), 5)
    }

    /** Last time the app checked GitHub for a newer build (epoch ms). */
    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0L)
        set(v) = sp.edit().putLong("lastUpdateCheck", v).apply()

    /** Build the user tapped "Later" on; the automatic check stays quiet until a newer one appears. */
    var dismissedBuild: Int
        get() = sp.getInt("dismissedBuild", 0)
        set(v) = sp.edit().putInt("dismissedBuild", v).apply()

    /** Folder (SAF tree URI) for automatic inventory backups; blank = off. */
    var autoBackupUri: String
        get() = sp.getString("autoBackupUri", "") ?: ""
        set(v) = sp.edit().putString("autoBackupUri", v).apply()

    /** The Inventory Overview card (category donut) is open; collapsed when false. */
    var overviewOpen: Boolean
        get() = sp.getBoolean("overviewOpen", true)
        set(v) = sp.edit().putBoolean("overviewOpen", v).apply()

    /** The Overview donut shows value (true) or pieces (false). */
    var overviewByValue: Boolean
        get() = sp.getBoolean("overviewByValue", false)
        set(v) = sp.edit().putBoolean("overviewByValue", v).apply()

    /** Look the part up on Mouser as soon as the review screen opens (only when a key is saved). */
    var autoMouserLookup: Boolean
        get() = sp.getBoolean("autoMouserLookup", true)
        set(v) = sp.edit().putBoolean("autoMouserLookup", v).apply()

    /** Also read the label's barcodes on each scan and prefer them over OCR where they disagree. */
    var readBarcodes: Boolean
        get() = sp.getBoolean("readBarcodes", true)
        set(v) = sp.edit().putBoolean("readBarcodes", v).apply()

    /** Label size last used for QR labels ([LabelSize] name); blank = pick by count. */
    var labelSize: String
        get() = sp.getString("labelSize", "") ?: ""
        set(v) = sp.edit().putString("labelSize", v).apply()

    /** Outcome of the last automatic backup, shown in Settings. */
    var autoBackupStatus: String
        get() = sp.getString("autoBackupStatus", "") ?: ""
        set(v) = sp.edit().putString("autoBackupStatus", v).apply()

    /** When the last automatic backup was written (epoch ms); 0 = not recorded yet. */
    var autoBackupLastOk: Long
        get() = sp.getLong("autoBackupLastOk", 0L)
        set(v) = sp.edit().putLong("autoBackupLastOk", v).apply()

    /** Newest build seen on GitHub by an update check (for the "Update available" badge); 0 = never checked. */
    var latestKnownBuild: Int
        get() = sp.getInt("latestKnownBuild", 0)
        set(v) = sp.edit().putInt("latestKnownBuild", v).apply()

    /** Settings cards the user opened or closed, by id ("open" / "closed"); missing = the card's default. */
    fun settingsCardOpen(id: String): Boolean? = sp.getString("settingsCard_$id", null)?.let { it == "open" }

    fun setSettingsCardOpen(id: String, open: Boolean) {
        sp.edit().putString("settingsCard_$id", if (open) "open" else "closed").apply()
    }

    /** "Sync to a file": the picked file (SAF document URI) rewritten after every change; blank = off. */
    var syncFileUri: String
        get() = sp.getString("syncFileUri", "") ?: ""
        set(v) = sp.edit().putString("syncFileUri", v).apply()

    /** The sync file's display name, e.g. "part-stock.csv". */
    var syncFileName: String
        get() = sp.getString("syncFileName", "") ?: ""
        set(v) = sp.edit().putString("syncFileName", v).apply()

    /** When the sync file was last written successfully (epoch ms); 0 = not yet. */
    var syncLastOk: Long
        get() = sp.getLong("syncLastOk", 0L)
        set(v) = sp.edit().putLong("syncLastOk", v).apply()

    /** Why the last sync failed; blank when it worked. */
    var syncError: String
        get() = sp.getString("syncError", "") ?: ""
        set(v) = sp.edit().putString("syncError", v).apply()

    /** A change hasn't reached the sync file yet (retried at the next start or change). */
    var syncPending: Boolean
        get() = sp.getBoolean("syncPending", false)
        set(v) = sp.edit().putBoolean("syncPending", v).apply()

    /** Scan screen in batch mode: each bag is queued and looked up in the background instead of opening review. */
    var batchMode: Boolean
        get() = sp.getBoolean("batchMode", false)
        set(v) = sp.edit().putBoolean("batchMode", v).apply()

    /** Bin every bag of the current batch goes into (set once at the top of the scan screen). */
    var batchBin: String
        get() = sp.getString("batchBin", "") ?: ""
        set(v) {
            sp.edit().putString("batchBin", v.trim()).apply()
            noteBin(v)
        }

    /** Android 12+: color the app from the wallpaper (dynamic color); off = the app's own brand colors. */
    var dynamicColor: Boolean
        get() = sp.getBoolean("dynamicColor", true)
        set(v) = sp.edit().putBoolean("dynamicColor", v).apply()

    /** The "Swipe a row to use or add one" tip was dismissed (or a row was swiped): never show it again. */
    var swipeHintSeen: Boolean
        get() = sp.getBoolean("swipeHintSeen", false)
        set(v) = sp.edit().putBoolean("swipeHintSeen", v).apply()

    /** Projects list order: a ProjectStatus.Sort name (default most recently changed). */
    var projectSort: String
        get() = sp.getString("projectSort", "RECENT") ?: "RECENT"
        set(v) = sp.edit().putString("projectSort", v).apply()

    /** Inventory list order: a PartSort.Sort name (default by category). */
    var inventorySort: String
        get() = sp.getString("inventorySort", "CATEGORY") ?: "CATEGORY"
        set(v) = sp.edit().putString("inventorySort", v).apply()

    /** Inventory layout: a PartSort.View name (List, Compact or Grid). */
    var inventoryView: String
        get() = sp.getString("inventoryView", "LIST") ?: "LIST"
        set(v) = sp.edit().putString("inventoryView", v).apply()

    /** Locations layout (#59): "LIST" or "GRID" (bins named like A3 as a cabinet of drawers). */
    var locationsView: String
        get() = sp.getString("locationsView", "LIST") ?: "LIST"
        set(v) = sp.edit().putString("locationsView", v).apply()

    /** What shades the Locations grid cells: a BinStats.Shade name (PIECES or PARTS). */
    var locationsShade: String
        get() = sp.getString("locationsShade", "PIECES") ?: "PIECES"
        set(v) = sp.edit().putString("locationsShade", v).apply()

    /** Settings → Appearance → Theme: a ThemeMode name (System, Light, Dark, Black). */
    var themeMode: String
        get() = sp.getString("themeMode", "SYSTEM") ?: "SYSTEM"
        set(v) = sp.edit().putString("themeMode", v).apply()

    /** Getting started card: "" = not decided yet, "active" = shown until complete, "off" = never (GettingStarted). */
    var gettingStarted: String
        get() = sp.getString("gettingStarted", "") ?: ""
        set(v) = sp.edit().putString("gettingStarted", v).apply()

    /** Getting started items the user skipped (GettingStarted.Item names). */
    var gettingStartedSkipped: Set<String>
        get() = sp.getStringSet("gettingStartedSkipped", null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("gettingStartedSkipped", v.toSet()).apply()
}
