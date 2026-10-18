package com.partstock.scanner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.partstock.scanner.parse.InventoryCsv
import java.io.File
import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Opens a web link in the browser (or the Mouser/DigiKey app if installed). */
fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(context, "No app can open this link", Toast.LENGTH_LONG).show()
    }
}

object Export {
    fun fileName(kind: String) = "part-stock-$kind-${LocalDate.now()}.csv"

    /** Opens the share sheet (Drive, Gmail, Sheets, etc.) with the CSV attached. */
    fun share(context: Context, name: String, csv: String) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, name)
        file.writeText(csv, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Export $name"))
    }

    fun zipName() = "part-stock-inventory-photos-${LocalDate.now()}.zip"

    /**
     * Inventory CSV plus every part photo, as a zip: inventory.csv, photos/<part number>-<n>.jpg, and photos.csv
     * saying which photo belongs to which part. Returns the number of photos written. Call off the main thread.
     */
    fun writeZip(context: Context, db: InventoryDb, out: OutputStream): Int {
        val parts = db.all().associateBy { it.id }
        val photos = db.allPhotos()
        val index = mutableListOf<List<String>>()
        var n = 0
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("inventory.csv"))
            zip.write(db.inventoryCsv().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            val used = HashSet<String>()
            for ((partId, files) in photos) {
                val p = parts[partId] ?: continue
                val key = p.mpn.ifBlank { p.customerPn.ifBlank { p.distributorPn } }.ifBlank { "part-$partId" }
                val base = key.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(60)
                files.forEachIndexed { i, name ->
                    val f = PartPhotos.file(context, name)
                    if (!f.isFile) return@forEachIndexed
                    var entry = "photos/$base-${i + 1}.jpg"
                    var k = 2
                    while (!used.add(entry)) entry = "photos/$base-${i + 1}-${k++}.jpg"
                    zip.putNextEntry(ZipEntry(entry))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    index += listOf(p.mpn, p.customerPn, p.distributorPn, p.location, entry)
                    n++
                }
            }
            zip.putNextEntry(ZipEntry("photos.csv"))
            zip.write(
                InventoryCsv.write(listOf("MPN", "Customer PN", "Distributor PN", "Location", "Photo"), index)
                    .toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
        }
        return n
    }

    /** Builds the inventory + photos zip (call off the main thread) and returns the share sheet intent for it. */
    fun shareZip(context: Context, db: InventoryDb): Intent {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.filter { it.name.endsWith(".zip") }?.forEach { it.delete() } // only the newest zip is kept
        val file = File(dir, zipName())
        file.outputStream().use { writeZip(context, db, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Export ${file.name}")
    }

    /** Writes the inventory + photos zip to a file the user picked. Call off the main thread. */
    fun saveZip(context: Context, uri: Uri, db: InventoryDb): Int =
        context.contentResolver.openOutputStream(uri, "wt")?.use { writeZip(context, db, it) } ?: error("Couldn't open file")

    /** Writes the CSV to a location the user picked with the system file picker. */
    fun writeTo(context: Context, uri: Uri, csv: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
            ?: error("Couldn't open file")
    }
}
