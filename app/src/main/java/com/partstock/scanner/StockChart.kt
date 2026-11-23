package com.partstock.scanner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.partstock.scanner.parse.ChartData
import com.partstock.scanner.parse.StockSeries
import java.time.LocalDate

/**
 * "Stock over time" on the part page: a sparkline of the part's count per day from its history (last 30 / 90 days or
 * all), today's count as the end dot and the minimum as a dashed line. Tapping it opens the History dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StockOverTimeCard(part: PartRow, load: suspend () -> List<HistoryEntry>, onOpenHistory: () -> Unit) {
    // Reloads whenever the part changes (a save, use or undo); null while loading.
    val entries by produceState<List<HistoryEntry>?>(null, part) { value = load() }
    var days by rememberSaveable { mutableIntStateOf(90) }
    val series = remember(entries, days, part.quantity) {
        entries?.let { list ->
            StockSeries.daily(
                list.map { StockSeries.Entry(it.ts, it.qtyBefore, it.qtyAfter, it.undone) },
                LocalDate.now(), days, part.quantity,
            ).toList()
        }
    }
    val span = when (days) {
        0 -> "all history"
        else -> "the last $days days"
    }
    Card(
        onClick = onOpenHistory,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.ShowChart, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Stock over time", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text("History ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30 to "30d", 90 to "90d", 0 to "All").forEach { (d, label) ->
                    FilterChip(selected = days == d, onClick = { days = d }, label = { Text(label) })
                }
            }
            when {
                series == null -> Spacer(Modifier.height(64.dp))
                series.size < 2 -> Text(
                    "Not enough history yet",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                else -> {
                    val description = ChartData.describeSeries("Stock over $span", series.toDoubleArray()) +
                        (part.minQty?.let { ", minimum $it" } ?: "")
                    Sparkline(
                        values = series,
                        contentDescription = description,
                        minLine = part.minQty?.toDouble(),
                        color = if (part.isLow()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        minLineColor = MaterialTheme.colorScheme.outline,
                    )
                    Row {
                        Text(
                            ChartData.num(series.first()), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                        )
                        if (part.minQty != null) {
                            Text(
                                "- - min ${part.minQty}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                            )
                        }
                        Text("now ${part.quantity}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** An icon for a History row's action (see [historyAction] for the words). */
internal fun historyIcon(a: String): ImageVector = when {
    a == "add" || a == "scan add" || a == "set" || a == "scan set" -> Icons.Filled.QrCodeScanner
    a == "use" || a.startsWith("use · ") -> Icons.Filled.Remove
    a == "adjust" -> Icons.Filled.Add
    a == "edit" -> Icons.Filled.Edit
    a == "move" -> Icons.Filled.SwapHoriz
    a == "rename bin" -> Icons.Filled.DriveFileRenameOutline
    a == "delete" -> Icons.Filled.Delete
    a == "undo" || a == "undone" -> Icons.AutoMirrored.Filled.Undo
    a.startsWith("lookup") -> Icons.Filled.CloudDownload
    a == "import" -> Icons.Filled.UploadFile
    a == "restore" -> Icons.Filled.Restore
    else -> Icons.Filled.History
}

/** A History row's quantity as a signed delta ("−2", "+10") with its color, or null when there's no change. */
@Composable
internal fun HistoryDelta(before: Int?, after: Int?) {
    if (before == null || after == null || before == after) {
        val text = when {
            after != null -> "= $after"
            before != null -> "$before → –"
            else -> ""
        }
        if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val d = after - before
    Column(horizontalAlignment = Alignment.End) {
        Text(
            if (d > 0) "+$d" else "−${-d}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (d > 0) ChartColors.positive() else ChartColors.negative(),
        )
        Text("$before → $after", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
