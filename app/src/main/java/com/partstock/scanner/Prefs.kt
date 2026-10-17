package com.partstock.scanner

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("partstock", Context.MODE_PRIVATE)

    /** Bin/drawer used on the last save, pre-filled on the next scan. */
    var lastLocation: String
        get() = sp.getString("lastLocation", "") ?: ""
        set(v) = sp.edit().putString("lastLocation", v.trim()).apply()
}
