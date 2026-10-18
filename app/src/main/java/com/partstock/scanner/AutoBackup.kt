package com.partstock.scanner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Writes the inventory CSV into a folder the user picked (Storage Access Framework, persisted permission) after each
 * change, keeping the newest [KEEP] backup files. The folder can be on the phone, an SD card or a cloud drive app.
 */
object AutoBackup {
    const val KEEP = 5
    private const val PREFIX = "part-stock-backup-"
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")
    private val worker = Executors.newSingleThreadExecutor()

    /** Queues a backup if a folder is set. Runs off the main thread, one at a time. */
    fun schedule(context: Context, prefs: Prefs, db: InventoryDb) {
        if (prefs.autoBackupUri.isBlank()) return
        val app = context.applicationContext
        worker.execute { runNow(app, prefs, db) }
    }

    /** Writes one backup now (call off the main thread). Records the outcome in [Prefs.autoBackupStatus]. */
    fun runNow(context: Context, prefs: Prefs, db: InventoryDb): Boolean {
        val tree = prefs.autoBackupUri.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return false
        return try {
            val name = write(context, tree, db.inventoryCsv())
            prefs.autoBackupStatus = "Last backup: $name"
            true
        } catch (e: Exception) {
            prefs.autoBackupStatus = "Last backup failed: ${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    /** Creates a new timestamped CSV in the folder and deletes older backups beyond [KEEP]. Returns the file name. */
    fun write(context: Context, tree: Uri, csv: String): String {
        val cr = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        val name = PREFIX + LocalDateTime.now().format(STAMP) + ".csv"
        val doc = DocumentsContract.createDocument(cr, dir, "text/csv", name)
            ?: error("Couldn't create a file in the backup folder")
        cr.openOutputStream(doc, "wt")?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
            ?: error("Couldn't write the backup file")

        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val ours = cr.query(children, cols, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val n = c.getString(1) ?: continue
                    if (n.startsWith(PREFIX) && n.endsWith(".csv")) add(id to n)
                }
            }
        }.orEmpty()
        // Names carry the timestamp, so sorting by name sorts by age.
        ours.sortedByDescending { it.second }.drop(KEEP).forEach { (id, _) ->
            try {
                DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, id))
            } catch (_: Exception) {
                // An old backup that can't be deleted is harmless.
            }
        }
        return name
    }

    /** Short folder name for display, e.g. "Documents/PartStock". */
    fun folderLabel(uri: String): String =
        try {
            DocumentsContract.getTreeDocumentId(Uri.parse(uri)).substringAfter(':').ifBlank { "selected folder" }
        } catch (_: Exception) {
            "selected folder"
        }
}
