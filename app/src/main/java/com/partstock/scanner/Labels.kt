package com.partstock.scanner

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.partstock.scanner.parse.LabelCodes
import com.partstock.scanner.parse.LabelSheet
import com.partstock.scanner.parse.Locations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** One label: the QR text ([LabelCodes]) plus a bold title and up to two short lines. */
data class LabelItem(val code: String, val title: String, val lines: List<String>)

/** What didn't fit on a label: a cut part number, a description cut after [descriptionLines] lines, dropped lines. */
data class LabelFit(val titleCut: Boolean, val descriptionLines: Int, val descriptionCut: Boolean, val linesDropped: Int) {
    /** "Description is cut off at 2 lines", or null when everything fits. */
    fun warning(): String? = when {
        titleCut -> "Part number is cut off"
        descriptionCut -> "Description is cut off at $descriptionLines line${if (descriptionLines == 1) "" else "s"}"
        linesDropped > 0 -> "Bin / manufacturer line doesn't fit"
        else -> null
    }
}

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
    /**
     * Writes the labels. On the Letter sheet (Avery 5160 / 8160, layout in [LabelSheet]) the first label goes in slot
     * [start] (0 = top left), so a partly used sheet can be printed on again; label printers ignore it.
     */
    fun write(file: File, items: List<LabelItem>, size: LabelSize, start: Int = 0) {
        val doc = PdfDocument()
        try {
            if (size.sheet) {
                val pages = LabelSheet.pages(items.size, start)
                var next = 0
                for (pageNo in 0 until pages) {
                    val page = doc.startPage(PdfDocument.PageInfo.Builder(LabelSheet.PAGE_W, LabelSheet.PAGE_H, pageNo + 1).create())
                    while (next < items.size) {
                        val slot = LabelSheet.slot(next, start)
                        if (slot.page != pageNo) break
                        draw(page.canvas, items[next], slot.x, slot.y, size.labelW, size.labelH)
                        next++
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

    /**
     * Draws a label at the chosen size into a bitmap ([scale] pixels per PDF point), with the same drawing code as the
     * PDF, and says what didn't fit.
     */
    fun preview(item: LabelItem, size: LabelSize, scale: Float = 4f): Pair<Bitmap, LabelFit> {
        val bmp = Bitmap.createBitmap(
            Math.round(size.labelW * scale).coerceAtLeast(1), Math.round(size.labelH * scale).coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        canvas.scale(scale, scale)
        val fit = draw(canvas, item, 0f, 0f, size.labelW, size.labelH)
        return bmp to fit
    }

    private fun draw(canvas: Canvas, item: LabelItem, x: Float, y: Float, w: Float, h: Float): LabelFit {
        val pad = h * 0.08f
        val side = h - 2 * pad
        drawQr(canvas, item.code, x + pad, y + pad, side)

        val tx = x + pad + side + pad
        val tw = x + w - pad - tx
        if (tw <= 4f) return LabelFit(titleCut = true, descriptionLines = 0, descriptionCut = item.lines.isNotEmpty(), linesDropped = 0)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = h * 0.17f
        }
        // Shrink a long part number a little before cutting it off.
        while (title.measureText(item.title) > tw && title.textSize > h * 0.11f) title.textSize -= 0.5f
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = h * 0.115f }

        var baseline = y + pad + title.textSize
        val titleText = fit(item.title, title, tw)
        canvas.drawText(titleText, tx, baseline, title)
        val lines = mutableListOf<String>()
        val descMax = if (item.lines.size > 1) 2 else 3
        var descLines = 0
        var descCut = false
        item.lines.firstOrNull()?.let { d ->
            val wrapped = wrap(d, body, tw, descMax)
            descLines = wrapped.size
            descCut = wrapped.joinToString(" ").replace(Regex("\\s+"), " ") != d.trim().replace(Regex("\\s+"), " ")
            lines += wrapped
        }
        item.lines.drop(1).forEach { lines += fit(it, body, tw) }
        val bottom = y + h - pad
        var drawn = 0
        for (l in lines) {
            baseline += body.textSize * 1.2f
            if (baseline > bottom) break
            canvas.drawText(l, tx, baseline, body)
            drawn++
        }
        return LabelFit(
            titleCut = titleText != item.title,
            descriptionLines = descLines,
            descriptionCut = descCut || drawn < descLines,
            linesDropped = lines.size - drawn,
        )
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

/**
 * Pick a label size, see the first label as it will print (drawn by the PDF code), pick the first free slot on a
 * partly used sheet, then share the PDF (e.g. to a label printer's app) or print it.
 */
@Composable
fun LabelDialog(items: List<LabelItem>, prefs: Prefs, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var size by remember {
        mutableStateOf(
            LabelSize.entries.firstOrNull { it.name == prefs.labelSize }
                ?: if (items.size > 1) LabelSize.LETTER_SHEET else LabelSize.BROTHER_62X29
        )
    }
    var start by remember { mutableIntStateOf(0) }
    var help by remember { mutableStateOf(false) }
    // Drawn off the main thread; redrawn when the size changes.
    val preview by produceState<Pair<Bitmap, LabelFit>?>(null, size, items.firstOrNull()) {
        val first = items.firstOrNull()
        value = if (first == null) null else withContext(Dispatchers.Default) {
            try {
                LabelPdf.preview(first, size)
            } catch (e: Exception) {
                null
            }
        }
    }

    fun make(): File? = try {
        prefs.labelSize = size.name
        File(File(context.cacheDir, "exports"), "part-stock-labels.pdf")
            .also { LabelPdf.write(it, items, size, if (size.sheet) start else 0) }
    } catch (e: Exception) {
        Toast.makeText(context, "Couldn't make the labels: ${e.message}", Toast.LENGTH_LONG).show()
        null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (items.size == 1) "Make label" else "Make ${items.size} labels") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (items.isEmpty()) Text("Nothing to label.")
                LabelSize.entries.forEach { s ->
                    FilterChip(selected = size == s, onClick = { size = s }, label = { Text(s.title) })
                }
                preview?.let { (bmp, fit) ->
                    Image(
                        bmp.asImageBitmap(),
                        contentDescription = "Preview of the first label: ${items.first().title}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth()
                            .aspectRatio(size.labelW / size.labelH)
                            .border(1.dp, MaterialTheme.colorScheme.outline),
                    )
                    fit.warning()?.let { w ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(
                                Icons.Filled.Warning, contentDescription = null, tint = ChartColors.warning(),
                                modifier = Modifier.size(18.dp),
                            )
                            Text(w, style = MaterialTheme.typography.bodySmall, color = ChartColors.warning())
                        }
                    }
                }
                if (size.sheet && items.isNotEmpty()) {
                    SheetSlotPicker(count = items.size, start = start, onStart = { start = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Share sends the PDF to a printer app; Print uses Android printing.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { help = !help }) {
                        Icon(Icons.Outlined.Info, contentDescription = if (help) "Hide label help" else "More about labels")
                    }
                }
                if (help) {
                    Text(
                        "Each label has a QR code plus part number, description and bin. Scan a label from the scan " +
                            "screen to open that part or set the bin. Label printer sizes print one label per page; " +
                            "the Letter sheet fits Avery 5160 / 8160 (3 × 10). On a partly used sheet, tap the first " +
                            "free slot so printing starts there.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

/**
 * The Letter sheet as a 3 × 10 grid: slots before [start] are already used (dimmed), the slots this print fills on
 * the first page are shaded. Tap a slot to start there; − / + step it.
 */
@Composable
private fun SheetSlotPicker(count: Int, start: Int, onStart: (Int) -> Unit) {
    val filled = LabelSheet.onFirstPage(count, start)
    val pages = LabelSheet.pages(count, start)
    val used = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surface
    val line = MaterialTheme.colorScheme.outline
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier.width(96.dp).border(1.dp, line).padding(3.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (r in 0 until LabelSheet.ROWS) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (c in 0 until LabelSheet.COLS) {
                        val slot = r * LabelSheet.COLS + c
                        val color = when {
                            slot < start -> used
                            slot < start + filled -> fill
                            else -> empty
                        }
                        Box(
                            Modifier.weight(1f).height(12.dp).background(color).border(0.5.dp, line)
                                .clickable(onClickLabel = "Start at ${LabelSheet.describe(slot)}") { onStart(slot) }
                                .semantics { contentDescription = "Slot ${slot + 1}, ${LabelSheet.describe(slot)}" },
                        )
                    }
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Start at slot ${start + 1}", style = MaterialTheme.typography.titleSmall)
            Text(
                LabelSheet.describe(start) + (if (start > 0) " · $start used" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                IconButton(enabled = start > 0, onClick = { onStart(start - 1) }) {
                    Icon(Icons.Filled.Remove, contentDescription = "Start one slot earlier")
                }
                IconButton(enabled = start < LabelSheet.PER_PAGE - 1, onClick = { onStart(start + 1) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Start one slot later")
                }
            }
            Text(
                "$count label${if (count == 1) "" else "s"} on $pages page${if (pages == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
