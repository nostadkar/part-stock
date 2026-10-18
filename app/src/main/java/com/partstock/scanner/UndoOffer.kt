package com.partstock.scanner

import android.content.Context
import android.widget.Toast

/**
 * After a save, shows a snackbar with Undo for a few seconds (set up by MainActivity). Screens call [offer] with the
 * change group from [InventoryDb.lastChange]; without a snackbar host it falls back to a toast.
 */
object UndoOffer {
    @Volatile var show: ((message: String, group: Long) -> Unit)? = null

    fun offer(context: Context, message: String, group: Long) {
        val s = show
        if (s != null) s(message, group) else Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
