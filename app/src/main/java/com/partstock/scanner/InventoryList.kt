package com.partstock.scanner

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.partstock.scanner.parse.Categories
import com.partstock.scanner.parse.Highlight
import com.partstock.scanner.parse.Links
import com.partstock.scanner.parse.Locations

/** What the list does when you tap, swipe or filter. */
class InventoryListActions(
    val open: (PartRow) -> Unit,
    val useOne: (PartRow) -> Unit,
    val addOne: (PartRow) -> Unit,
    val setBucket: (String?) -> Unit,
    val toggleLow: () -> Unit,
    val clearFilters: () -> Unit,
    val scan: () -> Unit,
)

/** The inventory list: stat tiles, category chips, sticky category headers and swipeable part rows. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InventoryList(
    view: InventoryView,
    query: String,
    bucket: String?,
    lowOnly: Boolean,
    listState: LazyListState,
    contentPadding: PaddingValues,
    actions: InventoryListActions,
    scopes: SharedScopes?,
    animate: Boolean,
    datasheets: DatasheetOpener,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier.fillMaxSize(),
    ) {
        item(key = "header") { header() }
        item(key = "stats") {
            StatTiles(view, lowOnly, onLow = actions.toggleLow, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        if (view.counts.size > 1 || view.tracked) {
            item(key = "chips") {
                CategoryChips(view, bucket, lowOnly, actions)
            }
        }
        when {
            !view.loaded -> Unit
            view.parts.isEmpty() -> item(key = "empty") {
                EmptyState(
                    icon = { Icon(Icons.Filled.Inventory2, contentDescription = null, modifier = Modifier.size(72.dp)) },
                    title = "Nothing in stock yet",
                    text = "Scan a parts bag label to add your first part.",
                    buttons = { Button(onClick = actions.scan) { Text("Scan a label") } },
                )
            }
            view.shown.isEmpty() -> item(key = "none") {
                EmptyState(
                    icon = { Icon(Icons.Filled.SearchOff, contentDescription = null, modifier = Modifier.size(72.dp)) },
                    title = "No results",
                    text = if (query.isNotBlank()) "Nothing matches \"$query\"." else "No parts match these filters.",
                    buttons = {
                        TextButton(onClick = actions.clearFilters) { Text("Clear filters") }
                        Button(onClick = actions.scan) { Text("Scan a label") }
                    },
                )
            }
        }
        view.groups.forEach { (cat, rows) ->
            stickyHeader(key = "h-$cat") {
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            categoryIcon(cat), contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            "$cat · ${rows.size}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
            items(rows, key = { it.id }) { p ->
                val rowModifier = if (animate) Modifier.animateItem() else Modifier
                SwipeRow(
                    part = p,
                    onUse = { actions.useOne(p) },
                    onAdd = { actions.addOne(p) },
                    modifier = rowModifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    PartRowCard(
                        part = p,
                        photo = view.photos[p.id]?.firstOrNull(),
                        query = query,
                        scopes = scopes,
                        animate = animate,
                        onClick = { actions.open(p) },
                        datasheets = datasheets,
                    )
                }
            }
        }
        item(key = "bottom") { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
private fun StatTiles(view: InventoryView, lowOnly: Boolean, onLow: () -> Unit, modifier: Modifier = Modifier) {
    val priced = view.parts.size - view.unpriced
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile("Parts", view.parts.size.toString(), Modifier.weight(1f))
        StatTile("Pieces", view.pieces.toString(), Modifier.weight(1f))
        StatTile(
            "Value", if (priced > 0) "\$" + InventoryDb.fmtMoney(view.value) else "–", Modifier.weight(1f),
        )
        StatTile(
            "Low stock", view.lowCount.toString(), Modifier.weight(1f),
            highlight = view.lowCount > 0, selected = lowOnly, onClick = onLow,
        )
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val container = when {
        selected -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = when {
        selected -> MaterialTheme.colorScheme.onErrorContainer
        highlight -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = container,
        modifier = modifier.clip(RoundedCornerShape(16.dp)).let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
            Text(
                value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = content,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CategoryChips(view: InventoryView, bucket: String?, lowOnly: Boolean, actions: InventoryListActions) {
    val cats = Categories.ALL.filter { view.counts.containsKey(it) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        item(key = "all") {
            FilterChip(
                selected = bucket == null && !lowOnly,
                onClick = actions.clearFilters,
                label = { Text("All · ${view.parts.size}") },
            )
        }
        if (view.tracked) {
            item(key = "low") {
                FilterChip(
                    selected = lowOnly,
                    onClick = actions.toggleLow,
                    leadingIcon = { Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    label = { Text("Low stock · ${view.lowCount}") },
                )
            }
        }
        items(cats, key = { it }) { c ->
            FilterChip(
                selected = bucket == c,
                onClick = { actions.setBucket(if (bucket == c) null else c) },
                leadingIcon = { Icon(categoryIcon(c), contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text("$c · ${view.counts[c]}", maxLines = 1) },
            )
        }
    }
}

/** Swipe right to use one, left to add one; the row springs back and the change can be undone. */
@Composable
private fun SwipeRow(
    part: PartRow,
    onUse: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // One action per swipe, even if the box reports the same swipe more than once.
    val last = remember { longArrayOf(0L) }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            val now = System.currentTimeMillis()
            if (v != SwipeToDismissBoxValue.Settled && now - last[0] > 600) {
                last[0] = now
                if (v == SwipeToDismissBoxValue.StartToEnd) onUse() else onAdd()
            }
            false // always spring back; the list updates with the new count
        },
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            val dir = state.dismissDirection
            val use = dir == SwipeToDismissBoxValue.StartToEnd
            val color = when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.tertiaryContainer
                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.primaryContainer
                SwipeToDismissBoxValue.Settled -> Color.Transparent
            }
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(color).padding(horizontal = 20.dp),
                contentAlignment = if (use) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                if (dir != SwipeToDismissBoxValue.Settled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (use) Icons.Filled.Remove else Icons.Filled.Add, contentDescription = null)
                        Text(
                            if (use) "Use 1" else "Add 1",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        },
    ) { content() }
}

@Composable
private fun PartRowCard(
    part: PartRow,
    photo: String?,
    query: String,
    scopes: SharedScopes?,
    animate: Boolean,
    onClick: () -> Unit,
    datasheets: DatasheetOpener,
) {
    val p = part
    val mark = MaterialTheme.colorScheme.tertiaryContainer
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PartImage(p, photo, 48.dp, Modifier.sharedPart(scopes, "img-${p.id}"))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    highlighted(p.key(), query, mark),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.sharedPart(scopes, "mpn-${p.id}"),
                )
                if (p.description.isNotBlank()) {
                    Text(
                        highlighted(p.description, query, mark),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val bins = p.bins()
                val where = Locations.summary(bins).let { w -> if (w.isNotBlank() && bins.size == 1) "Bin $w" else w }
                val chips = p.specs().chips() + listOfNotNull(where.takeIf { it.isNotBlank() })
                if (chips.isNotEmpty()) {
                    SpecChips(chips, Modifier.padding(top = 4.dp))
                }
            }
            if (p.datasheet().isNotBlank()) {
                val search = datasheets.isSearch(p)
                IconButton(onClick = { datasheets.open(p) }, enabled = datasheets.busyId == null) {
                    if (datasheets.busyId == p.id) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Filled.Description,
                            contentDescription = if (search) "Find datasheet" else "Datasheet",
                            tint = if (search) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (p.isLow()) {
                        Box(
                            Modifier.padding(end = 6.dp).size(8.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error),
                        )
                    }
                    QuantityText(
                        p.quantity, animate,
                        color = if (p.isLow()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
                p.minQty?.let { m ->
                    Text(
                        "min $m", style = MaterialTheme.typography.labelSmall,
                        color = if (p.isLow()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A part's first photo, or its category icon on a tinted tile. */
@Composable
fun PartImage(part: PartRow, photo: String?, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(12.dp))) {
        if (photo != null) {
            PhotoThumb(photo, size)
        } else {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    categoryIcon(part.category), contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(size * 0.5f),
                )
            }
        }
    }
}

/** The count, rolling up or down when it changes. */
@Composable
fun QuantityText(
    quantity: Int,
    animate: Boolean,
    color: Color,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.headlineSmall,
) {
    if (!animate) {
        Text(quantity.toString(), style = style, fontWeight = FontWeight.SemiBold, color = color)
        return
    }
    AnimatedContent(
        targetState = quantity,
        transitionSpec = {
            val up = targetState > initialState
            val spec = if (up) {
                (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
            } else {
                (slideInVertically { -it } + fadeIn()) togetherWith (slideOutVertically { it } + fadeOut())
            }
            spec.using(SizeTransform(clip = false))
        },
        label = "quantity",
    ) { q ->
        Text(q.toString(), style = style, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/** [text] with the search words marked. */
fun highlighted(text: String, query: String, mark: Color): AnnotatedString {
    val ranges = if (query.isBlank()) emptyList() else Highlight.ranges(text, query)
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        ranges.forEach { r -> addStyle(SpanStyle(background = mark, fontWeight = FontWeight.Bold), r[0], r[1]) }
    }
}

@Composable
private fun EmptyState(
    icon: @Composable () -> Unit,
    title: String,
    text: String,
    buttons: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(120.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) { buttons() }
    }
}
