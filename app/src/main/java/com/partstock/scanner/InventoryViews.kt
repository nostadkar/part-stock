package com.partstock.scanner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewHeadline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.partstock.scanner.parse.PartSort

/*
 * Inventory list extras (#44): the sort menu and List / Compact / Grid toggle, the active-filter bar, and the
 * Compact row and Grid cell layouts (List keeps PartRowCard).
 */

/** "Sort: Category ▾" and the three-way view toggle, above the parts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SortViewBar(
    sort: PartSort.Sort,
    viewMode: PartSort.View,
    onSort: (PartSort.Sort) -> Unit,
    onView: (PartSort.View) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { open = true }, modifier = Modifier.testTag("inventory-sort")) {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Sort: ${sort.label}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                PartSort.Sort.entries.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.label) },
                        leadingIcon = {
                            if (s == sort) Icon(Icons.Filled.Check, contentDescription = "Selected")
                            else Spacer(Modifier.size(24.dp))
                        },
                        onClick = {
                            open = false
                            onSort(s)
                        },
                    )
                }
            }
        }
        val views = PartSort.View.entries
        SingleChoiceSegmentedButtonRow {
            views.forEachIndexed { i, v ->
                SegmentedButton(
                    selected = v == viewMode,
                    onClick = { onView(v) },
                    shape = SegmentedButtonDefaults.itemShape(i, views.size),
                    icon = {},
                    label = {
                        Icon(
                            when (v) {
                                PartSort.View.LIST -> Icons.AutoMirrored.Filled.ViewList
                                PartSort.View.COMPACT -> Icons.Filled.ViewHeadline
                                else -> Icons.Filled.GridView
                            },
                            contentDescription = "${v.label} view",
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        }
    }
}

/** One removable chip in the active-filter bar. */
class FilterTag(val label: String, val remove: () -> Unit)

/** "Resistors × · Bin A3 × — 24 of 310 · Clear all": every filter that's on, in one row. */
@Composable
internal fun ActiveFilterBar(tags: List<FilterTag>, shown: Int, total: Int, onClearAll: () -> Unit) {
    if (tags.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            items(tags.size) { i ->
                val t = tags[i]
                InputChip(
                    selected = true,
                    onClick = t.remove,
                    label = { Text(t.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.Close, contentDescription = "Remove filter ${t.label}",
                            modifier = Modifier.size(InputChipDefaults.IconSize),
                        )
                    },
                )
            }
            item {
                Text(
                    "$shown of $total",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        TextButton(onClick = onClearAll) { Text("Clear all") }
    }
}

/** One line per part: small image, part number, description, bin and count. Twice as many rows fit. */
@Composable
internal fun CompactPartRow(
    part: PartRow,
    photo: String?,
    query: String,
    scopes: SharedScopes?,
    unpricedBadge: Boolean,
    onClick: () -> Unit,
) {
    val p = part
    val mark = MaterialTheme.colorScheme.tertiaryContainer
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            PartImage(p, photo, 28.dp, Modifier.sharedPart(scopes, "img-${p.id}"))
            Text(
                highlighted(p.key(), query, mark),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp).weight(0.45f).sharedPart(scopes, "mpn-${p.id}"),
            )
            Text(
                highlighted(p.description, query, mark),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).weight(0.55f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp).clearAndSetSemantics {
                    contentDescription = stockDescription(p, unpricedBadge && p.unitCost == null)
                },
            ) {
                if (p.isLow()) {
                    Icon(
                        Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(end = 2.dp).size(14.dp),
                    )
                }
                Text(
                    p.quantity.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (p.isLow()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** A grid cell: large photo (or category tile) with a quantity badge, the part number and its bin. */
@Composable
internal fun GridPartCell(
    part: PartRow,
    photo: String?,
    query: String,
    scopes: SharedScopes?,
    unpricedBadge: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = part
    val mark = MaterialTheme.colorScheme.tertiaryContainer
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(8.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth, 180.dp)
                Box {
                    PartImage(p, photo, side, Modifier.sharedPart(scopes, "img-${p.id}"))
                    val low = p.isLow()
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (low) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
                        contentColor = if (low) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                        shadowElevation = 2.dp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clearAndSetSemantics {
                            contentDescription = stockDescription(p, unpricedBadge && p.unitCost == null)
                        },
                    ) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (low) Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(14.dp))
                            Text(
                                (if (low) " " else "") + p.quantity,
                                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
            Text(
                highlighted(p.key(), query, mark),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp).sharedPart(scopes, "mpn-${p.id}"),
            )
            val where = com.partstock.scanner.parse.Locations.summary(p.bins())
            Text(
                listOf(p.description, where).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Padding around a row in each view. */
internal fun rowPadding(view: PartSort.View): PaddingValues = when (view) {
    PartSort.View.COMPACT -> PaddingValues(horizontal = 16.dp, vertical = 2.dp)
    else -> PaddingValues(horizontal = 16.dp, vertical = 4.dp)
}
