package com.partstock.scanner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.partstock.scanner.parse.FieldReader
import com.partstock.scanner.parse.OcrLine
import com.partstock.scanner.parse.PhotoBoxes

/** One color per field for its boxes on the photo; each box also carries a text tag, so color isn't the only cue. */
private fun fieldColor(f: FieldReader.Field): Color = when (f) {
    FieldReader.Field.MPN -> Color(0xFF1E88E5)
    FieldReader.Field.QUANTITY -> Color(0xFFFB8C00)
    FieldReader.Field.DESCRIPTION -> Color(0xFF43A047)
    FieldReader.Field.CUSTOMER_PN -> Color(0xFF8E24AA)
    FieldReader.Field.DISTRIBUTOR_PN -> Color(0xFF00897B)
}

/** Fields offered for a tapped line, in this order. */
private val USE_AS = listOf(
    FieldReader.Field.MPN, FieldReader.Field.QUANTITY, FieldReader.Field.DESCRIPTION,
    FieldReader.Field.CUSTOMER_PN, FieldReader.Field.DISTRIBUTOR_PN,
)

/**
 * The review screen's photo, about 40% of the screen high (collapsible), with the recognized lines boxed: lines a
 * field's value came from in that field's color with a tag ("MPN", "Qty"), the rest faint. Tapping a line offers
 * "Use as" a field (read the same way as the per-field rescan). [highlight] (the field being edited) is drawn bolder.
 * Without [boxes] (an older batch bag) it is the plain photo; tap it to zoom. The zoom button opens it full screen.
 */
@Composable
fun ReviewPhotoPanel(
    photo: ImageBitmap,
    boxes: List<OcrLine>,
    sources: Map<FieldReader.Field, List<Int>>,
    highlight: FieldReader.Field?,
    onUseLine: (Int, FieldReader.Field) -> Unit,
    onZoom: () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(true) }
    var picked by remember { mutableStateOf<Int?>(null) }
    val lineField = remember(sources) {
        buildMap { sources.forEach { (f, lines) -> lines.forEach { put(it, f) } } }
    }
    val height = (LocalConfiguration.current.screenHeightDp * 0.4f).dp
    val measurer = rememberTextMeasurer()
    val tagStyle = TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (boxes.isEmpty()) "Photo that was read" else "Photo that was read · tap a line to use it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onZoom) { Icon(Icons.Filled.ZoomIn, contentDescription = "Open photo full screen") }
            IconButton(onClick = { open = !open }) {
                Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (open) "Hide photo" else "Show photo",
                )
            }
        }
        if (!open) return@Column
        Box(Modifier.fillMaxWidth().height(height)) {
            Image(
                photo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            if (boxes.isEmpty()) {
                Box(
                    Modifier.fillMaxSize()
                        .semantics { contentDescription = "Photo that was read. Opens full screen." }
                        .clickable(onClick = onZoom),
                )
                return@Box
            }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .semantics {
                        contentDescription = "Photo that was read, ${boxes.size} lines found. " +
                            "Fields can also be re-read with the camera button in each field."
                    }
                    .pointerInput(boxes, photo) {
                        detectTapGestures { pos ->
                            val r = PhotoBoxes.fit(
                                photo.width.toDouble(), photo.height.toDouble(),
                                size.width.toDouble(), size.height.toDouble(),
                            )
                            if (r[2] <= 0 || r[3] <= 0) return@detectTapGestures
                            val x = (pos.x - r[0]) / r[2] * PhotoBoxes.SCALE
                            val y = (pos.y - r[1]) / r[3] * PhotoBoxes.SCALE
                            val slop = 16.dp.toPx() / r[2] * PhotoBoxes.SCALE
                            val i = PhotoBoxes.hit(boxes, x, y, slop)
                            if (i >= 0) picked = i
                        }
                    },
            ) {
                val r = PhotoBoxes.fit(photo.width.toDouble(), photo.height.toDouble(), size.width.toDouble(), size.height.toDouble())
                val sx = r[2].toFloat() / PhotoBoxes.SCALE
                val sy = r[3].toFloat() / PhotoBoxes.SCALE
                val ox = r[0].toFloat()
                val oy = r[1].toFloat()
                boxes.forEachIndexed { i, b ->
                    val topLeft = Offset(ox + b.left * sx, oy + b.top * sy)
                    val boxSize = Size((b.right - b.left) * sx, (b.bottom - b.top) * sy)
                    val f = lineField[i]
                    if (f == null) {
                        drawRoundRect(
                            Color.White.copy(alpha = 0.55f), topLeft, boxSize, CornerRadius(3.dp.toPx()),
                            style = Stroke(1.dp.toPx()),
                        )
                        return@forEachIndexed
                    }
                    val c = fieldColor(f)
                    val bold = f == highlight
                    if (bold) drawRoundRect(c.copy(alpha = 0.3f), topLeft, boxSize, CornerRadius(3.dp.toPx()))
                    drawRoundRect(
                        c, topLeft, boxSize, CornerRadius(3.dp.toPx()),
                        style = Stroke((if (bold) 3.dp else 2.dp).toPx()),
                    )
                    // Tag on the box's top edge (the first line of a wrapped description only).
                    if (sources[f]?.firstOrNull() == i) {
                        val text = measurer.measure(PhotoBoxes.tag(f), tagStyle)
                        val pad = 3.dp.toPx()
                        val w = text.size.width + pad * 2
                        val h = text.size.height.toFloat()
                        val tagTop = (topLeft.y - h).coerceAtLeast(0f)
                        drawRoundRect(c, Offset(topLeft.x, tagTop), Size(w, h), CornerRadius(2.dp.toPx()))
                        drawText(text, topLeft = Offset(topLeft.x + pad, tagTop))
                    }
                }
            }
        }
    }

    val i = picked
    val line = i?.let { boxes.getOrNull(it) }
    if (i != null && line != null) {
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text("Use this line as") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        line.text,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    USE_AS.forEach { f ->
                        TextButton(
                            onClick = {
                                picked = null
                                onUseLine(i, f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                PhotoBoxes.tag(f).let { if (it == "Desc") "Description" else it } +
                                    if (lineField[i] == f) " (now)" else "",
                                color = fieldColor(f),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picked = null }) { Text("Cancel") } },
        )
    }
}
