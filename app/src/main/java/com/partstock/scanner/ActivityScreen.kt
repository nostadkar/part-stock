package com.partstock.scanner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.partstock.scanner.parse.ActivityStats
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the Activity page reads once when it opens: a year of history and every part's last stock change. */
private class ActivityData(val today: LocalDate, val changes: List<ActivityStats.Change>, val touches: List<ActivityStats.Touch>)

/**
 * The Activity page: pieces added vs used per week (4 / 12 / 52 weeks), the most used parts, parts not touched in
 * 12+ months and the biggest single changes, all from the change history. [exists] says whether a part id can still
 * be opened; [onPart] opens it (and the caller closes this page).
 */
@Composable
fun ActivityPage(db: InventoryDb, exists: (Long) -> Boolean, onPart: (Long) -> Unit, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val data by produceState<ActivityData?>(null) {
                value = try {
                    withContext(Dispatchers.IO) {
                        val today = LocalDate.now()
                        ActivityData(
                            today,
                            db.activityChanges(ActivityStats.periodStart(today, 52).toString()),
                            db.activityTouches(),
                        )
                    }
                } catch (e: Exception) {
                    ActivityData(LocalDate.now(), emptyList(), emptyList())
                }
            }
            ActivityContent(data, exists, onPart, onClose)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityContent(data: ActivityData?, exists: (Long) -> Boolean, onPart: (Long) -> Unit, onClose: () -> Unit) {
    var weeks by rememberSaveable { mutableIntStateOf(12) }
    var staleOpen by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activity") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") } },
            )
        },
    ) { pad ->
        if (data == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val series = remember(data, weeks) { ActivityStats.weekly(data.changes, data.today, weeks) }
        val totals = remember(series) { ActivityStats.totals(series) }
        val top = remember(data, weeks) { ActivityStats.topUsed(data.changes, data.today, weeks, 5) }
        val big = remember(data, weeks) { ActivityStats.biggest(data.changes, data.today, weeks, 5) }
        val stale = remember(data) { ActivityStats.untouched(data.touches, data.today, 12) }
        val addedColor = ChartColors.fixed()[0]
        val usedColor = ChartColors.fixed()[1]

        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "period") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (w in listOf(4, 12, 52)) {
                        FilterChip(selected = weeks == w, onClick = { weeks = w }, label = { Text("$w weeks") })
                    }
                }
            }
            item(key = "chart") {
                Section {
                    Heading("Added vs used per week")
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Legend(addedColor, "Added", totals[0])
                        Legend(usedColor, "Used", totals[1])
                    }
                    PairedBarChart(
                        first = series.map { it.added },
                        second = series.map { it.used },
                        contentDescription = ActivityStats.summary(series),
                        firstColor = addedColor,
                        secondColor = usedColor,
                        modifier = Modifier.widthIn(max = 720.dp),
                    )
                    Row(Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
                        Text(
                            ActivityStats.day(series.first().start), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                        )
                        Text("This week", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (totals[0] == 0 && totals[1] == 0) {
                        Muted("Nothing added or used in this period.")
                    }
                }
            }
            item(key = "top") {
                Section {
                    Heading("Most used")
                    if (top.isEmpty()) Muted("Nothing used in this period.")
                    val max = top.maxOfOrNull { it.pieces }?.toDouble() ?: 1.0
                    top.forEachIndexed { i, u ->
                        val name = u.name.ifBlank { "Deleted part" }
                        val canOpen = exists(u.partId)
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .let { if (canOpen) it.clickable(role = Role.Button, onClickLabel = "Open part") { onPart(u.partId) } else it }
                                .semantics(mergeDescendants = true) { contentDescription = "Number ${i + 1}, $name, ${u.pieces} used" }
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    name, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                )
                                Text("${u.pieces}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                            BarMeter(
                                value = u.pieces.toDouble(), max = max, contentDescription = "",
                                color = usedColor, modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
            item(key = "stale") {
                Section {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .let { if (stale.isNotEmpty()) it.clickable { staleOpen = !staleOpen } else it }
                            .semantics(mergeDescendants = true) {
                                if (stale.isNotEmpty()) stateDescription = if (staleOpen) "Expanded" else "Collapsed"
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Heading("Not touched in 12+ months")
                            Text(
                                when (stale.size) {
                                    0 -> "Every part in stock was added or used in the last year."
                                    1 -> "1 part in stock, a candidate to clear out"
                                    else -> "${stale.size} parts in stock, candidates to clear out"
                                },
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (stale.isNotEmpty()) {
                            Icon(
                                if (staleOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (staleOpen) "Hide list" else "Show list",
                            )
                        }
                    }
                    if (staleOpen) {
                        stale.forEach { t ->
                            val last = ActivityStats.lastTouched(t)
                            PartLine(
                                title = t.name.ifBlank { "(no part number)" },
                                detail = "${t.quantity} pcs · last touched ${ActivityStats.day(last)} ${last?.year ?: ""}".trim(),
                                onClick = if (exists(t.partId)) ({ onPart(t.partId) }) else null,
                            )
                        }
                    }
                }
            }
            item(key = "big") {
                Section {
                    Heading("Biggest changes")
                    if (big.isEmpty()) Muted("No changes in this period.")
                    big.forEach { b ->
                        PartLine(
                            title = b.text, detail = null,
                            onClick = if (exists(b.partId)) ({ onPart(b.partId) }) else null,
                            dot = if (b.kind == ActivityStats.Kind.USED) usedColor else addedColor,
                        )
                    }
                }
            }
            item(key = "note") {
                Muted("From the change history; undone changes don't count and undo / CSV restore count as neither added nor used.")
            }
        }
    }
}

@Composable
private fun Section(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Legend(color: Color, label: String, total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text("$label $total", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PartLine(title: String, detail: String?, onClick: (() -> Unit)?, dot: Color? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .let { if (onClick != null) it.clickable(role = Role.Button, onClickLabel = "Open part", onClick = onClick) else it }
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
