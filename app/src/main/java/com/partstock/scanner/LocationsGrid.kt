package com.partstock.scanner

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.partstock.scanner.parse.BinStats
import com.partstock.scanner.parse.Locations

private val CELL = 64.dp
private val ROW_LABEL = 28.dp
private val GAP = 4.dp

/** What a bin's tap and long-press menu do, shared by the grid cells and the "Other bins" chips. */
class BinActions(
    val open: (String) -> Unit,
    val rename: (LocationSummary) -> Unit,
    val binLabel: (LocationSummary) -> Unit,
    val partLabels: (LocationSummary) -> Unit,
)

/**
 * Locations as a cabinet (#59): bins named like drawers (A3, R2) in rows by letter and columns by number, shaded by
 * fill ([shade]: pieces, or number of parts), a red dot when a part in the bin is low, empty drawers outlined only.
 * Tap opens the bin's parts; long-press offers rename and labels. Bins that don't fit go in "Other bins" below.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocationsGrid(
    bins: List<BinView>,
    shade: BinStats.Shade,
    actions: BinActions,
    modifier: Modifier = Modifier,
) {
    val byName = remember(bins) { bins.associateBy { it.summary.name } }
    val grid = remember(bins) { Locations.grid(bins.map { it.summary.name }) }
    val maxPieces = remember(bins) { bins.maxOfOrNull { it.summary.pieces } ?: 0 }
    val maxParts = remember(bins) { bins.maxOfOrNull { it.summary.parts } ?: 0 }
    fun fillOf(b: BinView) =
        if (shade == BinStats.Shade.PARTS) BinStats.fill(b.summary.parts, maxParts) else BinStats.fill(b.summary.pieces, maxPieces)

    Column(modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        if (!grid.useful) {
            Text(
                "Bin names don't look like drawer cells (A1, B2, R3…), so there's no cabinet to draw. " +
                    "Name bins that way to see them as a grid.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(GAP), modifier = Modifier.clearAndSetSemantics {}) {
                        Spacer(Modifier.width(ROW_LABEL))
                        for (c in grid.firstColumn..grid.lastColumn) {
                            Box(Modifier.width(CELL), contentAlignment = Alignment.Center) {
                                Text("$c", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    grid.rows.forEachIndexed { r, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(GAP), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(ROW_LABEL).clearAndSetSemantics {}) {
                                Text(row, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            }
                            grid.cells[r].forEach { name ->
                                val b = name?.let { byName[it] }
                                if (b == null) Spacer(Modifier.size(CELL)) else BinCell(b, fillOf(b), shade, actions)
                            }
                        }
                    }
                }
            }
        }
        val others = grid.others.mapNotNull { byName[it] }
        if (others.isNotEmpty()) {
            if (grid.useful) {
                Text("Other bins", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                others.forEach { b -> OtherBinChip(b, fillOf(b), shade, actions) }
            }
        }
    }
}

/** Background for a bin: none when empty, else from surface variant toward primary with the fill. */
@Composable
private fun shadeColor(pieces: Int, fill: Float): Color {
    val cs = MaterialTheme.colorScheme
    return if (pieces <= 0) Color.Transparent else lerp(cs.surfaceVariant, cs.primary, 0.15f + 0.85f * fill)
}

@Composable
private fun textOn(pieces: Int, fill: Float): Color {
    val cs = MaterialTheme.colorScheme
    return if (pieces > 0 && 0.15f + 0.85f * fill > 0.55f) cs.onPrimary else cs.onSurfaceVariant
}

private fun metric(b: LocationSummary, shade: BinStats.Shade) =
    if (shade == BinStats.Shade.PARTS) "${b.parts} part${if (b.parts == 1) "" else "s"}" else "${b.pieces} pcs"

@Composable
private fun BinCell(bin: BinView, fill: Float, shade: BinStats.Shade, actions: BinActions) {
    val b = bin.summary
    val shape = RoundedCornerShape(8.dp)
    BinMenuBox(bin, actions) { touch ->
        Box(
            Modifier
                .width(CELL)
                .heightIn(min = CELL)
                .clip(shape)
                .background(shadeColor(b.pieces, fill))
                .then(if (b.pieces <= 0) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape) else Modifier)
                .then(touch),
        ) {
            Column(
                Modifier.align(Alignment.Center).padding(4.dp).clearAndSetSemantics {},
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val fg = textOn(b.pieces, fill)
                Text(b.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(metric(b, shade), style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (bin.low > 0) LowDot(Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
    }
}

@Composable
private fun OtherBinChip(bin: BinView, fill: Float, shade: BinStats.Shade, actions: BinActions) {
    val b = bin.summary
    val shape = RoundedCornerShape(8.dp)
    BinMenuBox(bin, actions) { touch ->
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .clip(shape)
                .background(shadeColor(b.pieces, fill))
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .then(touch)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val fg = textOn(b.pieces, fill)
            Text(
                "${b.name} · ${metric(b, shade)}", style = MaterialTheme.typography.labelLarge, color = fg,
                modifier = Modifier.clearAndSetSemantics {},
            )
            if (bin.low > 0) LowDot(Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun LowDot(modifier: Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
}

/** Tap → the bin's parts; long-press → rename / labels menu. [content] puts the touch modifier on its surface. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BinMenuBox(bin: BinView, actions: BinActions, content: @Composable (Modifier) -> Unit) {
    val b = bin.summary
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val description = BinStats.cellDescription(b.name, b.parts, b.pieces, bin.low)
    Box {
        content(
            Modifier
                .combinedClickable(
                    onClickLabel = "Open",
                    onLongClickLabel = "Rename or print labels",
                    onClick = { actions.open(b.name) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                )
                .semantics { contentDescription = description },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = {
                menu = false
                actions.rename(b)
            })
            DropdownMenuItem(text = { Text("Bin label") }, onClick = {
                menu = false
                actions.binLabel(b)
            })
            if (b.parts > 0) {
                DropdownMenuItem(text = { Text("Labels for its ${b.parts} part${if (b.parts == 1) "" else "s"}") }, onClick = {
                    menu = false
                    actions.partLabels(b)
                })
            }
        }
    }
}
