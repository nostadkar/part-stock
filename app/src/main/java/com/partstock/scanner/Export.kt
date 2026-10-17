package com.partstock.scanner

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate

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

    /** Writes the CSV to a location the user picked with the system file picker. */
    fun writeTo(context: Context, uri: Uri, csv: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
            ?: error("Couldn't open file")
    }
}
