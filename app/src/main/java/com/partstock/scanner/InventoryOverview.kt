package com.partstock.scanner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.partstock.scanner.parse.ChartData

/**
 * The collapsible Overview card at the top of Inventory: a donut of pieces or value per category (top 6 + Other),
 * with a legend. Tapping a slice or legend row filters to that category (tap again to clear), like the chips.
 */
@Composable
internal fun OverviewCard(
    view: InventoryView,
    bucket: String?,
    open: Boolean,
    byValue: Boolean,
    onToggleOpen: () -> Unit,
    onByValue: (Boolean) -> Unit,
    onCategory: (String?) -> Unit,
    modifier: Modifier = Modifier,
    /** Opens a part from the "Most valuable parts" list. */
    onPart: (PartRow) -> Unit = {},
) {
    val slices = if (byValue) view.overviewValue else view.overviewPieces
    val palette = ChartColors.categorical()
    val other = ChartColors.other()
    val colors = slices.mapIndexed { i, s -> if (s.category == null) other else palette[i % palette.size] }
    val selected = slices.indexOfFirst { it.category != null && it.category == bucket }.takeIf { it >= 0 }
    fun tap(s: OverviewSlice) {
        if (s.category != null) onCategory(if (bucket == s.category) null else s.category)
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onToggleOpen)
                    .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.DonutLarge, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Overview", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (open) "Hide overview" else "Show overview",
                )
            }
            if (!open) return@Column

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !byValue, onClick = { onByValue(false) }, label = { Text("Pieces") })
                FilterChip(selected = byValue, onClick = { onByValue(true) }, label = { Text("Value") })
            }
            if (slices.isEmpty()) {
                Text(
                    if (byValue) "No prices yet. Add unit costs (or look parts up on Mouser) to see value by category."
                    else "No pieces in stock.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                return@Column
            }

            val description = (if (byValue) "Value by category: " else "Pieces by category: ") +
                slices.joinToString(", ") { "${it.label} ${ChartData.percent(it.fraction)}" }
            val donut: @Composable (Modifier) -> Unit = { m ->
                DonutChart(
                    slices = slices.mapIndexed { i, s -> DonutSlice(s.label, s.value, colors[i]) },
                    contentDescription = description,
                    selected = selected,
                    onSelect = { i -> tap(slices[i]) },
                    modifier = m.size(140.dp),
                )
            }
            val legend: @Composable (Modifier) -> Unit = { m ->
                Column(m) {
                    slices.forEachIndexed { i, s ->
                        LegendRow(s, colors[i], byValue, selected = selected == i, dim = selected != null && selected != i) { tap(s) }
                    }
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 480.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        donut(Modifier.padding(end = 16.dp))
                        legend(Modifier.weight(1f))
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        donut(Modifier.padding(bottom = 8.dp))
                        legend(Modifier.fillMaxWidth())
                    }
                }
            }
            if (byValue && view.unpriced > 0) {
                Text(
                    "${view.unpriced} part${if (view.unpriced == 1) "" else "s"} without a price count as \$0.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (view.mostValuable.isNotEmpty()) {
                Text(
                    "Most valuable parts", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Column(Modifier.padding(bottom = 8.dp)) {
                    view.mostValuable.forEachIndexed { i, p -> ValueRow(i + 1, p) { onPart(p) } }
                }
            }
        }
    }
}

/** Swatch, category icon, name, amount and share; tap to filter (the merged Other row can't be tapped). */
@Composable
private fun LegendRow(s: OverviewSlice, color: Color, byValue: Boolean, selected: Boolean, dim: Boolean, onClick: () -> Unit) {
    val amount = if (byValue) "\$" + InventoryDb.fmtMoney(s.value) else ChartData.num(s.value) + " pcs"
    val share = ChartData.percent(s.fraction)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .let { if (selected) it.background(MaterialTheme.colorScheme.secondaryContainer) else it }
            .let { if (s.category != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .semantics(mergeDescendants = true) {
                contentDescription = "${s.label}, $amount, $share" + if (selected) ", filtered" else ""
            }
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(if (dim) color.copy(alpha = 0.35f) else color))
        Spacer(Modifier.width(8.dp))
        Icon(
            if (s.category != null) categoryIcon(s.category) else Icons.Filled.MoreHoriz,
            contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            s.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.SemiBold else null, modifier = Modifier.weight(1f),
        )
        Text(
            amount, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp),
        )
        Text(share, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** "1  LM358DR · 120 × $0.45   $54.00"; tap opens the part. */
@Composable
private fun ValueRow(rank: Int, p: PartRow, onClick: () -> Unit) {
    val cost = p.unitCost ?: 0.0
    val total = "\$" + InventoryDb.fmtMoney(cost * p.quantity)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = "Open part", onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "Number $rank, ${p.key()}, ${p.quantity} pieces, $total"
            }
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$rank", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                p.key(), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${p.quantity} × \$${InventoryDb.fmtCost(cost)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
        Text(total, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
