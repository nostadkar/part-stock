package com.partstock.scanner

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.partstock.scanner.parse.AppShortcuts

/** The dynamic launcher shortcut "Scan into <bin>" (the static ones are in res/xml/shortcuts.xml). */
object Shortcuts {
    private var pushed: String? = null

    /** Points the shortcut at the bin the next scans go to (batch bin in batch mode); removes it when there is none. */
    fun update(context: Context, prefs: Prefs) {
        val bin = AppShortcuts.scanIntoBin(prefs.batchMode, prefs.batchBin, prefs.lastLocation)
        if (bin == pushed) return
        try {
            if (bin.isEmpty()) {
                ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(AppShortcuts.ID_SCAN_INTO))
            } else {
                val label = AppShortcuts.scanIntoLabel(bin)
                val intent = Intent(context, MainActivity::class.java)
                    .setAction(AppShortcuts.ACTION_SCAN_INTO)
                    .putExtra(AppShortcuts.EXTRA_BIN, bin)
                val info = ShortcutInfoCompat.Builder(context, AppShortcuts.ID_SCAN_INTO)
                    .setShortLabel(label)
                    .setLongLabel(label)
                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_bin))
                    .setIntent(intent)
                    .build()
                ShortcutManagerCompat.pushDynamicShortcut(context, info)
            }
            pushed = bin
        } catch (e: Exception) {
            // Launchers may refuse (rate limit, no shortcut support): the app works the same without it.
        }
    }
}
