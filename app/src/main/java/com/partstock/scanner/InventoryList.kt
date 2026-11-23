package com.partstock.scanner

import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.partstock.scanner.parse.PartSort

/** What the list does when you tap, swipe or filter. */
class InventoryListActions(
    val open: (PartRow) -> Unit,
    val useOne: (PartRow) -> Unit,
    val addOne: (PartRow) -> Unit,
    val setBucket: (String?) -> Unit,
    val toggleLow: () -> Unit,
    /** The list order (sort menu). */
    val setSort: (PartSort.Sort) -> Unit,
    /** List / Compact / Grid. */
    val setView: (PartSort.View) -> Unit = {},
    /** Filter-bar chips: drop the bin filter / the search. */
    val clearBin: () -> Unit = {},
    val clearSearch: () -> Unit = {},
    val clearFilters: () -> Unit,
    val scan: () -> Unit,
    /** Opens or collapses the Overview card. */
    val toggleOverview: () -> Unit = {},
    /** Overview donut by value (true) or pieces. */
    val setOverviewByValue: (Boolean) -> Unit = {},
    /** Drops the "Price changed" filter (set from a price refresh's result). */
    val clearChanged: () -> Unit = {},
    /** Shows only parts without a price (the Value tile's "unpriced" count), or all again. */
    val toggleUnpriced: () -> Unit = {},
    /** Searches for a spec chip's text (tap 0603 → search "0603"). */
    val search: ((String) -> Unit)? = null,
    /** Use or add several (long-press on a row's count). */
    val useN: ((PartRow, Int) -> Unit)? = null,
    val addN: ((PartRow, Int) -> Unit)? = null,
    /** Hides the "Swipe a row" tip for good. */
    val dismissSwipeHint: () -> Unit = {},
    /** Opens the Activity page (from the Overview card). */
    val openActivity: () -> Unit = {},
    /** Opens the Reorder screen ("Reorder (4)" under the Low stock filter). */
    val openReorder: (() -> Unit)? = null,
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
    overviewOpen: Boolean = true,
    overviewByValue: Boolean = false,
    /** Wide and short (a phone in landscape): stat tiles and the Overview card share one row. */
    sideBySide: Boolean = false,
    /** Mark unpriced rows with "$?" (off when there's nothing to price them with and no part has a price). */
    unpricedBadge: Boolean = false,
    /** First run: a one-line "Swipe a row" tip under the tiles, and the first row peeks sideways once. */
    swipeHint: Boolean = false,
    viewMode: PartSort.View = PartSort.View.LIST,
) {
    val context = LocalContext.current
    var bulkFor by remember { mutableStateOf<PartRow?>(null) }
    bulkFor?.let { b ->
        val fresh = view.parts.firstOrNull { it.id == b.id } ?: b
        BulkStockDialog(
            fresh,
            onUse = { n -> actions.useN?.invoke(fresh, n); bulkFor = null },
            onAdd = { n -> actions.addN?.invoke(fresh, n); bulkFor = null },
            onDismiss = { bulkFor = null },
        )
    }
    if (!view.loaded) {
        // Placeholders shaped like the tiles and rows, instead of a blank list that pops in. Kept out of the LazyColumn,
        // so its saved scroll position isn't spent on the placeholders and comes back once the parts are there.
        Column(modifier.fillMaxSize().padding(contentPadding)) {
            header()
            SkeletonStatTiles(animate, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            SkeletonPartRows(animate, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        return
    }
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier.fillMaxSize(),
    ) {
        item(key = "header") { header() }
        if (view.filtered) {
            item(key = "filters") {
                val tags = buildList {
                    view.bucket?.let { add(FilterTag(it) { actions.setBucket(null) }) }
                    if (view.lowOnly) add(FilterTag("Low stock", actions.toggleLow))
                    if (view.unpricedOnly) add(FilterTag("Unpriced", actions.toggleUnpriced))
                    if (view.changedOnly) add(FilterTag("Price changed", actions.clearChanged))
                    view.bin?.let { add(FilterTag("Bin $it", actions.clearBin)) }
                    if (view.query.isNotBlank()) add(FilterTag("\u201C${view.query}\u201D", actions.clearSearch))
                }
                ActiveFilterBar(tags, view.shown.size, view.parts.size) {
                    actions.clearFilters()
                    if (view.bin != null) actions.clearBin()
                }
            }
        }
        val openReorder = actions.openReorder
        if (view.lowOnly && view.lowCount > 0 && openReorder != null) {
            item(key = "reorder") {
                FilledTonalButton(
                    onClick = openReorder,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Filled.ShoppingCart, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Reorder (${view.lowCount})")
                }
            }
        }
        val withOverview = view.loaded && view.parts.isNotEmpty() // hidden while loading and with an empty inventory
        if (sideBySide && withOverview) {
            item(key = "stats") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatTiles(view, lowOnly, onLow = actions.toggleLow, onUnpriced = actions.toggleUnpriced, modifier = Modifier.weight(1f))
                    OverviewCard(
                        view = view, bucket = bucket, open = overviewOpen, byValue = overviewByValue,
                        onToggleOpen = actions.toggleOverview, onByValue = actions.setOverviewByValue,
                        onCategory = actions.setBucket, onPart = actions.open, onActivity = actions.openActivity,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            item(key = "stats") {
                StatTiles(
                    view, lowOnly, onLow = actions.toggleLow, onUnpriced = actions.toggleUnpriced,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        if (swipeHint && withOverview) {
            item(key = "swipe-hint") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Swipe, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp),
                    )
                    Text(
                        "Swipe a row to use or add one",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    IconButton(onClick = actions.dismissSwipeHint) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss tip")
                    }
                }
            }
        }
        if (withOverview && !sideBySide) {
            item(key = "overview") {
                OverviewCard(
                    view = view, bucket = bucket, open = overviewOpen, byValue = overviewByValue,
                    onToggleOpen = actions.toggleOverview, onByValue = actions.setOverviewByValue,
                    onCategory = actions.setBucket, onPart = actions.open, onActivity = actions.openActivity,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        if (view.counts.size > 1 || view.tracked) {
            item(key = "chips") {
                CategoryChips(view, bucket, lowOnly, actions)
            }
        }
        if (view.parts.isNotEmpty()) {
            item(key = "sort-view") {
                SortViewBar(
                    view.sort, viewMode, onSort = actions.setSort, onView = actions.setView,
                    modifier = Modifier.padding(start = 8.dp, end = 16.dp),
                )
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
        val firstId = view.groups.firstOrNull()?.second?.firstOrNull()?.id
        view.groups.forEach { (cat, rows) ->
            stickyHeader(key = "h-$cat") {
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val grouped = view.sort.grouped()
                        val tint = if (!grouped) null else categoryTint(cat)
                        Box(
                            Modifier.size(26.dp).clip(CircleShape)
                                .background(tint?.container ?: MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (!grouped) Icons.AutoMirrored.Filled.Sort else categoryIcon(cat),
                                contentDescription = null,
                                tint = tint?.onContainer ?: MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Text(
                            "$cat · ${rows.size}",
                            style = MaterialTheme.typography.titleSmall,
                            color = tint?.onSurface ?: MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
            if (viewMode == PartSort.View.GRID) {
                // Two columns: one list item per pair, keyed by its first part.
                items(rows.chunked(2), key = { "g-" + it.first().id }) { pair ->
                    val rowModifier = if (animate) Modifier.animateItem() else Modifier
                    Row(
                        rowModifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pair.forEach { p ->
                            SwipeRow(
                                part = p,
                                onUse = { actions.useOne(p) },
                                onAdd = { actions.addOne(p) },
                                modifier = Modifier.weight(1f),
                                peek = swipeHint && p.id == firstId,
                            ) {
                                GridPartCell(
                                    part = p,
                                    photo = view.photos[p.id]?.firstOrNull(),
                                    query = query,
                                    scopes = scopes,
                                    unpricedBadge = unpricedBadge,
                                    onClick = { actions.open(p) },
                                )
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                return@forEach
            }
            items(rows, key = { it.id }) { p ->
                val rowModifier = if (animate) Modifier.animateItem() else Modifier
                SwipeRow(
                    part = p,
                    onUse = { actions.useOne(p) },
                    onAdd = { actions.addOne(p) },
                    modifier = rowModifier.padding(rowPadding(viewMode)),
                    peek = swipeHint && p.id == firstId,
                ) {
                    if (viewMode == PartSort.View.COMPACT) {
                        CompactPartRow(
                            part = p,
                            photo = view.photos[p.id]?.firstOrNull(),
                            query = query,
                            scopes = scopes,
                            unpricedBadge = unpricedBadge,
                            onClick = { actions.open(p) },
                        )
                    } else {
                        PartRowCard(
                            part = p,
                            photo = view.photos[p.id]?.firstOrNull(),
                            query = query,
                            scopes = scopes,
                            animate = animate,
                            onClick = { actions.open(p) },
                            datasheets = datasheets,
                            unpricedBadge = unpricedBadge,
                            onSpecTap = actions.search,
                            onLongCount = if (actions.useN != null) ({ bulkFor = p }) else null,
                        )
                    }
                }
            }
        }
        item(key = "bottom") { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
private fun StatTiles(
    view: InventoryView,
    lowOnly: Boolean,
    onLow: () -> Unit,
    onUnpriced: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val priced = view.parts.size - view.unpriced
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile("Parts", view.parts.size.toString(), Modifier.weight(1f))
        StatTile("Pieces", view.pieces.toString(), Modifier.weight(1f))
        // With parts missing a price, the tile says how many and tapping it lists them.
        val unpriced = view.unpriced
        StatTile(
            "Value", if (priced > 0) "\$" + InventoryDb.fmtMoney(view.value) else "–", Modifier.weight(1f),
            selected = view.unpricedOnly,
            onClick = if (unpriced > 0 || view.unpricedOnly) onUnpriced else null,
            chip = if (unpriced > 0) "$unpriced unpriced" else null,
            clickLabel = if (view.unpricedOnly) "Show all parts" else "Show the $unpriced parts without a price",
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
    /** Small pill under the label ("3 unpriced"). */
    chip: String? = null,
    clickLabel: String? = null,
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
        modifier = modifier.clip(RoundedCornerShape(16.dp)).let {
            if (onClick != null) it.clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick) else it
        },
    ) {
        // At large font sizes the value shrinks to fit instead of being cut off, and labels wrap.
        val big = LocalDensity.current.fontScale > 1.3f
        Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
            FitText(
                value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = content,
            )
            Text(
                label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (big) 3 else 1, overflow = TextOverflow.Ellipsis,
            )
            if (chip != null) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (selected) MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(
                        chip, style = MaterialTheme.typography.labelSmall, maxLines = if (big) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (selected) content else MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryChips(view: InventoryView, bucket: String?, lowOnly: Boolean, actions: InventoryListActions) {
    val cats = Categories.ALL.filter { view.counts.containsKey(it) }
    // Many categories: a few chips, then "More…" opens the same tile sheet as the category picker.
    val chips = Categories.chipsBeforeMore(cats, bucket, CHIPS_BEFORE_MORE)
    var more by remember { mutableStateOf(false) }
    if (more) {
        CategorySheet(
            title = "Show category",
            categories = cats,
            selected = bucket,
            counts = view.counts,
            onPick = {
                more = false
                actions.setBucket(if (bucket == it) null else it)
            },
            onDismiss = { more = false },
        )
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        item(key = "all") {
            FilterChip(
                selected = bucket == null && !lowOnly && !view.unpricedOnly && !view.changedOnly,
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
        items(chips, key = { it }) { c ->
            FilterChip(
                selected = bucket == c,
                onClick = { actions.setBucket(if (bucket == c) null else c) },
                leadingIcon = { Icon(categoryIcon(c), contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text("$c · ${view.counts[c]}", maxLines = 1) },
            )
        }
        if (chips.size < cats.size) {
            item(key = "more") {
                FilterChip(
                    selected = false,
                    onClick = { more = true },
                    label = { Text("More… · ${cats.size - chips.size}") },
                )
            }
        }
    }
}

/** Category chips shown before "More…" when there are more categories than fit comfortably. */
private const val CHIPS_BEFORE_MORE = 6

/** Swipe right to use one, left to add one; the row springs back and the change can be undone. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PartRowCard(
    part: PartRow,
    photo: String?,
    query: String,
    scopes: SharedScopes?,
    animate: Boolean,
    onClick: () -> Unit,
    datasheets: DatasheetOpener,
    unpricedBadge: Boolean = false,
    onSpecTap: ((String) -> Unit)? = null,
    /** Long-press on the count: use or add several. */
    onLongCount: (() -> Unit)? = null,
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
                val specs = p.specs()
                SpecChips(
                    specs.chips(),
                    Modifier.padding(top = 4.dp),
                    pkg = specs.pkg,
                    extra = listOfNotNull(where.takeIf { it.isNotBlank() }),
                    onTap = onSpecTap,
                    onOtherClick = onClick,
                )
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
            Column(
                horizontalAlignment = Alignment.End,
                // One spoken summary instead of "Low stock", "3", "min 10" and the bar separately.
                modifier = Modifier
                    .let {
                        if (onLongCount == null) it
                        else it.combinedClickable(
                            onClickLabel = "Open part",
                            onLongClickLabel = "Use or add several",
                            onClick = onClick,
                            onLongClick = onLongCount,
                        )
                    }
                    .clearAndSetSemantics {
                        contentDescription = stockDescription(p, unpricedBadge && p.unitCost == null)
                    },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (p.isLow()) {
                        // An icon, not just a red dot, so low stock isn't shown by color alone.
                        Icon(
                            Icons.Filled.Warning, contentDescription = "Low stock",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(end = 4.dp).size(16.dp),
                        )
                    }
                    QuantityText(
                        p.quantity, animate,
                        color = if (p.isLow()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (unpricedBadge && p.unitCost == null) {
                    Text(
                        "\$?", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { contentDescription = "No price" },
                    )
                }
                p.minQty?.let { m ->
                    Text(
                        "min $m", style = MaterialTheme.typography.labelSmall,
                        color = if (p.isLow()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StockBar(p.quantity, m, Modifier.padding(top = 3.dp).width(44.dp), animate = animate)
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
            val tint = categoryTint(part.category)
            Box(
                Modifier.fillMaxSize().background(tint.container),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    categoryIcon(part.category), contentDescription = null,
                    tint = tint.onContainer,
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
internal fun EmptyState(
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

/**
 * One line of [text] that shrinks (down to 60%) until it fits the width instead of ellipsizing, so a number is never
 * hidden at a large font size. At the default size it fits and looks like a normal Text.
 */
@Composable
internal fun FitText(text: String, style: androidx.compose.ui.text.TextStyle, color: Color, modifier: Modifier = Modifier) {
    var scale by remember(text, style) { mutableFloatStateOf(1f) }
    Text(
        text,
        style = style.copy(fontSize = style.fontSize * scale),
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = modifier,
        onTextLayout = { r -> if (r.hasVisualOverflow && scale > 0.6f) scale = (scale * 0.9f).coerceAtLeast(0.6f) },
    )
}

/** What TalkBack says for a row's count: "Low stock, 3, minimum 10" or "500 in stock, minimum 100". */
internal fun stockDescription(p: PartRow, noPrice: Boolean = false): String {
    val m = p.minQty
    val base = when {
        p.isLow() && m != null -> "Low stock, ${p.quantity}, minimum $m"
        p.isLow() -> "Low stock, ${p.quantity}"
        m != null -> "${p.quantity} in stock, minimum $m"
        else -> "${p.quantity} in stock"
    }
    return if (noPrice) "$base, no price" else base
}
