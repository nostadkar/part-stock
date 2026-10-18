package com.partstock.scanner

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.partstock.scanner.parse.LabelCodes
import com.partstock.scanner.parse.Locations
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** One label: the QR text ([LabelCodes]) plus a bold title and up to two short lines. */
data class LabelItem(val code: String, val title: String, val lines: List<String>)

/** Label stock the PDF is laid out for. Sizes in PDF points (1/72 in). */
enum class LabelSize(val title: String, val labelW: Float, val labelH: Float, val sheet: Boolean, val milsW: Int, val milsH: Int) {
    BROTHER_62X29("62 × 29 mm (Brother DK)", mm(62f), mm(29f), false, 2441, 1142),
    THERMAL_2X1("2 × 1 in thermal", 144f, 72f, false, 2000, 1000),
    LETTER_SHEET("Letter sheet, 30 per page (Avery 5160)", 189f, 72f, true, 8500, 11000),
}

private fun mm(v: Float) = v / 25.4f * 72f

fun partLabel(p: PartRow): LabelItem {
    val bins = p.bins().keys.filter { it != Locations.UNASSIGNED }
    return LabelItem(
        code = LabelCodes.part(p.id),
        title = p.mpn.ifBlank { p.customerPn.ifBlank { p.distributorPn } },
        lines = listOf(
            p.description,
            listOf(if (bins.isNotEmpty()) "Bin ${bins.joinToString(", ")}" else "", p.manufacturer)
                .filter { it.isNotBlank() }.joinToString(" · "),
        ).filter { it.isNotBlank() },
    )
}

fun binLabel(name: String, parts: Int): LabelItem =
    LabelItem(LabelCodes.bin(name), name, listOf("Storage bin · $parts part${if (parts == 1) "" else "s"}"))

/**
 * Renders labels into a PDF with Android's PdfDocument (no network, no extra services). Label-printer sizes get one
 * label per page, which is what label printers expect; the Letter sheet packs 30 per page.
 */
object LabelPdf {
    // Avery 5160 / 8160: 3 × 10 labels of 2.625 × 1 in on Letter.
    private const val SHEET_W = 612
    private const val SHEET_H = 792
    private const val SHEET_LEFT = 13.5f
    private const val SHEET_TOP = 36f
    private const val SHEET_PITCH_X = 198f
    private const val SHEET_PITCH_Y = 72f
    private const val SHEET_COLS = 3
    private const val SHEET_ROWS = 10

    fun write(file: File, items: List<LabelItem>, size: LabelSize) {
        val doc = PdfDocument()
        try {
            if (size.sheet) {
                val perPage = SHEET_COLS * SHEET_ROWS
                items.chunked(perPage).forEachIndexed { pageNo, chunk ->
                    val page = doc.startPage(PdfDocument.PageInfo.Builder(SHEET_W, SHEET_H, pageNo + 1).create())
                    chunk.forEachIndexed { i, item ->
                        val x = SHEET_LEFT + (i % SHEET_COLS) * SHEET_PITCH_X
                        val y = SHEET_TOP + (i / SHEET_COLS) * SHEET_PITCH_Y
                        draw(page.canvas, item, x, y, size.labelW, size.labelH)
                    }
                    doc.finishPage(page)
                }
            } else {
                val w = Math.round(size.labelW)
                val h = Math.round(size.labelH)
                items.forEachIndexed { i, item ->
                    val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
                    draw(page.canvas, item, 0f, 0f, w.toFloat(), h.toFloat())
                    doc.finishPage(page)
                }
            }
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    private fun draw(canvas: Canvas, item: LabelItem, x: Float, y: Float, w: Float, h: Float) {
        val pad = h * 0.08f
        val side = h - 2 * pad
        drawQr(canvas, item.code, x + pad, y + pad, side)

        val tx = x + pad + side + pad
        val tw = x + w - pad - tx
        if (tw <= 4f) return
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = h * 0.17f
        }
        // Shrink a long part number a little before cutting it off.
        while (title.measureText(item.title) > tw && title.textSize > h * 0.11f) title.textSize -= 0.5f
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = h * 0.115f }

        var baseline = y + pad + title.textSize
        canvas.drawText(fit(item.title, title, tw), tx, baseline, title)
        val lines = mutableListOf<String>()
        item.lines.firstOrNull()?.let { lines += wrap(it, body, tw, if (item.lines.size > 1) 2 else 3) }
        item.lines.drop(1).forEach { lines += fit(it, body, tw) }
        val bottom = y + h - pad
        for (l in lines) {
            baseline += body.textSize * 1.2f
            if (baseline > bottom) break
            canvas.drawText(l, tx, baseline, body)
        }
    }

    private fun drawQr(canvas: Canvas, text: String, x: Float, y: Float, side: Float) {
        val qr = Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
        val m = qr.matrix
        val n = m.width
        val cell = side / (n + 2) // one module of quiet zone each side; the label margin adds more
        val ox = x + cell
        val oy = y + cell
        val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        for (row in 0 until n) for (col in 0 until n) {
            if (m.get(col, row).toInt() == 1) {
                canvas.drawRect(ox + col * cell, oy + row * cell, ox + (col + 1) * cell, oy + (row + 1) * cell, paint)
            }
        }
    }

    /** [s] cut to fit [width], with an ellipsis when cut. */
    private fun fit(s: String, p: Paint, width: Float): String {
        if (p.measureText(s) <= width) return s
        var end = s.length
        while (end > 0 && p.measureText(s, 0, end) + p.measureText("…") > width) end--
        return s.substring(0, end).trimEnd() + "…"
    }

    /** Word-wraps [s] into at most [maxLines] lines; the last one is cut with an ellipsis. */
    private fun wrap(s: String, p: Paint, width: Float, maxLines: Int): List<String> {
        val out = mutableListOf<String>()
        var cur = ""
        val words = s.trim().split(Regex("\\s+"))
        for ((i, word) in words.withIndex()) {
            val next = if (cur.isEmpty()) word else "$cur $word"
            if (p.measureText(next) <= width || cur.isEmpty()) {
                cur = next
            } else {
                out += cur
                cur = word
                if (out.size == maxLines - 1) {
                    out += fit(words.drop(i).joinToString(" "), p, width)
                    return out
                }
            }
        }
        if (cur.isNotEmpty()) out += fit(cur, p, width)
        return out
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Labels"))
    }

    /** Opens Android's print dialog with the PDF, paper size set to the label size. */
    fun print(context: Context, file: File, size: LabelSize) {
        val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val media = if (size.sheet) PrintAttributes.MediaSize.NA_LETTER
            else PrintAttributes.MediaSize("partstock_${size.name.lowercase()}", size.title, size.milsW, size.milsH)
        val attrs = PrintAttributes.Builder().setMediaSize(media).setMinMargins(PrintAttributes.Margins.NO_MARGINS).build()
        pm.print("Part Stock labels", PdfFileAdapter(file), attrs)
    }

    private class PdfFileAdapter(private val file: File) : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?, newAttributes: PrintAttributes, cancellationSignal: CancellationSignal?,
            callback: PrintDocumentAdapter.LayoutResultCallback, extras: Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            callback.onLayoutFinished(
                PrintDocumentInfo.Builder(file.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true,
            )
        }

        override fun onWrite(
            pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal?,
            callback: PrintDocumentAdapter.WriteResultCallback,
        ) {
            try {
                FileInputStream(file).use { i -> FileOutputStream(destination.fileDescriptor).use { o -> i.copyTo(o) } }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }
    }
}

/** Pick a label size, then share the PDF (e.g. to a label printer's app) or print it. */
@Composable
fun LabelDialog(items: List<LabelItem>, prefs: Prefs, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var size by remember {
        mutableStateOf(
            LabelSize.entries.firstOrNull { it.name == prefs.labelSize }
                ?: if (items.size > 1) LabelSize.LETTER_SHEET else LabelSize.BROTHER_62X29
        )
    }

    fun make(): File? = try {
        prefs.labelSize = size.name
        File(File(context.cacheDir, "exports"), "part-stock-labels.pdf").also { LabelPdf.write(it, items, size) }
    } catch (e: Exception) {
        Toast.makeText(context, "Couldn't make the labels: ${e.message}", Toast.LENGTH_LONG).show()
        null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (items.size == 1) "Make label" else "Make ${items.size} labels") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (items.isEmpty()) Text("Nothing to label.")
                LabelSize.entries.forEach { s ->
                    FilterChip(selected = size == s, onClick = { size = s }, label = { Text(s.title) })
                }
                Text(
                    "QR code plus part number, description and bin. Share sends the PDF to a label printer's app; " +
                        "Print uses Android printing. Scan a label from the scan screen to open that part or set the bin.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = items.isNotEmpty(), onClick = {
                    make()?.let { LabelPdf.print(context, it, size) }
                    onDismiss()
                }) { Text("Print") }
                TextButton(enabled = items.isNotEmpty(), onClick = {
                    make()?.let { LabelPdf.share(context, it) }
                    onDismiss()
                }) { Text("Share") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
