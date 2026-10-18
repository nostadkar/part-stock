package com.partstock.scanner

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("partstock", Context.MODE_PRIVATE)

    /** Bin/drawer used on the last save, pre-filled on the next scan. */
    var lastLocation: String
        get() = sp.getString("lastLocation", "") ?: ""
        set(v) = sp.edit().putString("lastLocation", v.trim()).apply()

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
}
