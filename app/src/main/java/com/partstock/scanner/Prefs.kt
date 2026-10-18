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

    /** Outcome of the last automatic backup, shown in Settings. */
    var autoBackupStatus: String
        get() = sp.getString("autoBackupStatus", "") ?: ""
        set(v) = sp.edit().putString("autoBackupStatus", v).apply()
}
