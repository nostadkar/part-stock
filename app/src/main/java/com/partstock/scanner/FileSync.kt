package com.partstock.scanner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Sync to a file": after every change, rewrites one file the user picked (Storage Access Framework, persisted URI
 * permission) with the inventory CSV. Picking a file in Google Drive puts it there with no login or API key in the
 * app; Drive uploads it (and queues the upload while offline). If a write fails, the error is shown in Settings and
 * the next change, or the next app start, tries again.
 */
object FileSync {
    private val worker = Executors.newSingleThreadExecutor()
    /** A sync is waiting in the queue; changes meanwhile are covered by it (it reads the database when it runs). */
    private val queued = AtomicBoolean(false)

    /** Called after each status change (from the worker thread), so an open Settings screen can refresh. */
    @Volatile var onStatus: (() -> Unit)? = null

    const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** Queues a rewrite of the sync file if one is set. Runs off the main thread; bursts of changes write once. */
    fun schedule(context: Context, prefs: Prefs, db: InventoryDb) {
        if (prefs.syncFileUri.isBlank()) return
        prefs.syncPending = true
        if (!queued.compareAndSet(false, true)) return
        val app = context.applicationContext
        worker.execute {
            queued.set(false)
            runNow(app, prefs, db)
        }
    }

    /** Writes the file now (call off the main thread). Records the outcome in [Prefs]. */
    fun runNow(context: Context, prefs: Prefs, db: InventoryDb): Boolean {
        val uri = prefs.syncFileUri.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return false
        val ok = try {
            write(context, uri, db.inventoryCsv())
            prefs.syncLastOk = System.currentTimeMillis()
            prefs.syncError = ""
            prefs.syncPending = false
            true
        } catch (e: SecurityException) {
            prefs.syncError = "lost access to the file, pick it again"
            false
        } catch (e: FileNotFoundException) {
            prefs.syncError = "file not found (deleted or moved?), pick it again"
            false
        } catch (e: Exception) {
            prefs.syncError = e.message?.takeIf { it.isNotBlank() }?.take(120) ?: e.javaClass.simpleName
            false
        }
        onStatus?.invoke()
        return ok
    }

    /** Replaces the file's contents. Some providers don't support "wt" (truncate), so "w" is the fallback. */
    private fun write(context: Context, uri: Uri, csv: String) {
        val bytes = csv.toByteArray(Charsets.UTF_8)
        val cr = context.contentResolver
        val out = try {
            cr.openOutputStream(uri, "wt")
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: FileNotFoundException) {
            null // some providers report an unsupported mode this way; "w" below tells a missing file apart
        } catch (e: UnsupportedOperationException) {
            null
        } ?: cr.openOutputStream(uri, "w") ?: error("couldn't open the file")
        out.use { it.write(bytes) }
    }

    /**
     * Starts syncing to [uri] (picked or created with the system file picker): keeps the permission across restarts,
     * releases the previous file's, and remembers the file name for display.
     */
    fun choose(context: Context, prefs: Prefs, uri: Uri) {
        val cr = context.contentResolver
        try {
            cr.takePersistableUriPermission(uri, FLAGS)
        } catch (_: Exception) {
            // Some providers can't persist; the write reports it if access is lost later.
        }
        val old = prefs.syncFileUri
        if (old.isNotBlank() && old != uri.toString()) release(context, old)
        prefs.syncFileUri = uri.toString()
        prefs.syncFileName = displayName(context, uri)
        prefs.syncError = ""
        prefs.syncLastOk = 0
    }

    /** Stops syncing (the file itself stays where it is). */
    fun turnOff(context: Context, prefs: Prefs) {
        release(context, prefs.syncFileUri)
        prefs.syncFileUri = ""
        prefs.syncFileName = ""
        prefs.syncError = ""
        prefs.syncLastOk = 0
        prefs.syncPending = false
    }

    private fun release(context: Context, uri: String) {
        if (uri.isBlank()) return
        try {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), FLAGS)
        } catch (_: Exception) {
        }
    }

    private fun displayName(context: Context, uri: Uri): String = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    } ?: "the chosen file"
}
