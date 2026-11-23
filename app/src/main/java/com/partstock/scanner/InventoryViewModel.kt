package com.partstock.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.partstock.scanner.parse.BinStats
import com.partstock.scanner.parse.Categories
import com.partstock.scanner.parse.ChartData
import com.partstock.scanner.parse.Locations
import com.partstock.scanner.parse.PartSort
import com.partstock.scanner.parse.ReorderList
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Everything the inventory screens show, read from the database in one go. */
data class InventoryData(
    val parts: List<PartRow> = emptyList(),
    /** Photo file names per part id, oldest first (the first is the thumbnail). */
    val photos: Map<Long, List<String>> = emptyMap(),
    val locations: List<LocationSummary> = emptyList(),
    /** False until the first read finished (so screens don't flash "Nothing yet"). */
    val loaded: Boolean = false,
)

/** The inventory list after search and filters, with the summary numbers for the header. */
data class InventoryView(
    val data: InventoryData = InventoryData(),
    val shown: List<PartRow> = emptyList(),
    /** [shown] grouped by category, in the usual category order (one group when sorted by lowest stock). */
    val groups: List<Pair<String, List<PartRow>>> = emptyList(),
    /** Parts per category (of all parts, not just the shown ones). */
    val counts: Map<String, Int> = emptyMap(),
    val lowCount: Int = 0,
    /** True when any part has a minimum set (shows the Low stock chip). */
    val tracked: Boolean = false,
    val pieces: Int = 0,
    val value: Double = 0.0,
    val unpriced: Int = 0,
    /** Showing only parts without a unit cost (the Value tile's "unpriced" filter). */
    val unpricedOnly: Boolean = false,
    /** Showing only the parts whose price the last refresh changed ("Show parts with a price change"). */
    val changedOnly: Boolean = false,
    /** Up to [InventoryViewModel.TOP_VALUE] parts with the highest stock value (unit cost × pieces), highest first. */
    val mostValuable: List<PartRow> = emptyList(),
    /** The list order; only [PartSort.Sort.CATEGORY] groups by category. */
    val sort: PartSort.Sort = PartSort.Sort.CATEGORY,
    /** The search as it was filtered (after the debounce). */
    val query: String = "",
    val bucket: String? = null,
    val lowOnly: Boolean = false,
    val bin: String? = null,
    /** Overview donut by category: pieces, and value (unpriced parts count as 0), top 6 + the rest. */
    val overviewPieces: List<OverviewSlice> = emptyList(),
    val overviewValue: List<OverviewSlice> = emptyList(),
) {
    /** True when [shown] is sorted lowest stock first (quantity ÷ minimum) instead of grouped by category. */
    val lowestFirst: Boolean get() = sort == PartSort.Sort.LOWEST_STOCK

    /** Any filter on (category, low stock, unpriced, bin or search): shows the active-filter bar. */
    val filtered: Boolean get() =
        bucket != null || lowOnly || unpricedOnly || changedOnly || bin != null || query.isNotBlank()

    val parts: List<PartRow> get() = data.parts
    val photos: Map<Long, List<String>> get() = data.photos
    val loaded: Boolean get() = data.loaded
}

/** One Reorder screen line: the part, its CSV item, the suggested quantity and when it was marked ordered. */
data class ReorderLine(val part: PartRow, val item: ReorderList.Item, val suggested: Int, val orderedOn: String?)

/** One Overview donut slice; [category] is null for the merged "Other" slice of the smaller categories. */
data class OverviewSlice(val label: String, val category: String?, val value: Double, val fraction: Double)

/** One bin on the Locations screen, with what's needed to draw its card. */
class BinView(
    val summary: LocationSummary,
    /** Pieces relative to the fullest bin, 0…1. */
    val fill: Float,
    /** Up to 3 categories with the most parts in this bin, most first. */
    val topCategories: List<String>,
    /** Up to 4 parts to show as thumbnails, most pieces in this bin first. */
    val preview: List<PartRow>,
    /** Parts in this bin that are low on stock (the red dot in the grid view). */
    val low: Int = 0,
) : BinStats.Bin {
    override fun name(): String = summary.name
    override fun parts(): Int = summary.parts
    override fun pieces(): Int = summary.pieces
}

/** The Locations screen after search and sort. */
data class LocationsView(
    val bins: List<BinView> = emptyList(),
    /** All bins, before the search. */
    val total: Int = 0,
    val loaded: Boolean = false,
)

/**
 * Loads parts, photos and locations on [Dispatchers.IO] and reloads them whenever the database changes
 * ([InventoryDb.changes]). Search (debounced), category, low-stock and bin filters run here, off the main thread.
 */
@OptIn(FlowPreview::class)
class InventoryViewModel(val db: InventoryDb) : ViewModel() {

    val data: StateFlow<InventoryData> = db.changes
        .map { InventoryData(db.all(), db.allPhotos(), db.locations(), loaded = true) }
        .flowOn(Dispatchers.IO)
        // Eagerly: the scan screen shows the part count, and Inventory opens with the list already loaded.
        .stateIn(viewModelScope, SharingStarted.Eagerly, InventoryData())

    val query = MutableStateFlow("")
    val bucket = MutableStateFlow<String?>(null)
    val lowOnly = MutableStateFlow(false)
    val unpricedOnly = MutableStateFlow(false)
    val bin = MutableStateFlow<String?>(null)
    /** Only these part ids (the parts a price refresh changed); null = no such filter. */
    val changedOnly = MutableStateFlow<Set<Long>?>(null)
    /** List order (the screen restores it from Prefs). */
    val sort = MutableStateFlow(PartSort.Sort.CATEGORY)

    private val filters = combine(
        query.debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS },
        bucket, combine(lowOnly, unpricedOnly, changedOnly) { low, unpriced, ids -> Triple(low, unpriced, ids) }, bin, sort,
    ) { q, b, (low, unpriced, ids), bin, order -> Filters(q, b, low, bin, order, unpriced, ids) }

    /** Locations screen sort and search. */
    val binSort = MutableStateFlow(BinStats.Sort.NAME)
    val binQuery = MutableStateFlow("")

    val locations: StateFlow<LocationsView> = combine(data, binSort, binQuery) { d, sort, q -> buildLocations(d, sort, q) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocationsView())

    val view: StateFlow<InventoryView> = combine(data, filters) { d, f -> build(d, f) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, InventoryView())

    // ---------------------------------------------------------------- Reorder screen

    private val orderedVersion = MutableStateFlow(0)

    /** Parts at or below their minimum, lowest stock first, with suggested quantities; null until loaded. */
    val reorder: StateFlow<List<ReorderLine>?> = combine(data, orderedVersion) { d, _ -> d }
        .map { d -> if (!d.loaded) null else buildReorder(d) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun buildReorder(d: InventoryData): List<ReorderLine> {
        val low = d.parts.filter { it.isLow() }
        val marks = try {
            db.orderedMarks(low.map { it.id }.toSet())
        } catch (e: Exception) {
            emptyMap()
        }
        return low
            .sortedWith(compareBy<PartRow> { it.quantity.toDouble() / (it.minQty ?: 1).coerceAtLeast(1) }.thenBy { it.mpn })
            .map { p ->
                val item = db.reorderItem(p)
                ReorderLine(p, item, ReorderList.orderQuantity(p.quantity, p.minQty), marks[p.id])
            }
    }

    /** "Mark ordered" for these parts, dated [date] (yyyy-MM-dd). */
    fun markOrdered(ids: Collection<Long>, date: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.markOrdered(ids, date)
            } finally {
                orderedVersion.value++
            }
        }
    }

    /** Back to all parts, no search (when the Inventory screen closes, like before). */
    fun clearFilters() {
        query.value = ""
        bucket.value = null
        lowOnly.value = false
        unpricedOnly.value = false
        changedOnly.value = null
    }

    private data class Filters(
        val query: String,
        val bucket: String?,
        val lowOnly: Boolean,
        val bin: String?,
        val sort: PartSort.Sort,
        val unpricedOnly: Boolean = false,
        val ids: Set<Long>? = null,
    )

    private fun build(d: InventoryData, f: Filters): InventoryView {
        val parts = d.parts
        val filtered = parts.filter { p ->
            (f.bucket == null || p.category == f.bucket) && (!f.lowOnly || p.isLow()) && (!f.unpricedOnly || p.unitCost == null) &&
                (f.ids == null || p.id in f.ids) &&
                (f.query.isBlank() || p.matches(f.query)) &&
                (f.bin == null || Locations.keyOf(p.bins(), f.bin) != null)
        }
        val order = Categories.ALL
        val shown: List<PartRow>
        val groups: List<Pair<String, List<PartRow>>>
        if (!f.sort.grouped()) {
            // One flat list in the chosen order (PartSort: ties by part number), under one header naming the sort.
            shown = PartSort.sorted(filtered.map(::SortItem), f.sort).map { it.p }
            groups = if (shown.isEmpty()) emptyList() else listOf(f.sort.label to shown)
        } else {
            shown = filtered
            groups = shown.groupBy { it.category }.toList()
                .sortedBy { (c, _) -> order.indexOf(c).let { if (it < 0) 999 else it } }
        }
        return InventoryView(
            data = d,
            shown = shown,
            groups = groups,
            counts = parts.groupingBy { it.category }.eachCount(),
            lowCount = parts.count { it.isLow() },
            tracked = parts.any { it.minQty != null },
            pieces = parts.sumOf { it.quantity },
            value = parts.sumOf { (it.unitCost ?: 0.0) * it.quantity },
            unpriced = parts.count { it.unitCost == null },
            unpricedOnly = f.unpricedOnly,
            changedOnly = f.ids != null,
            mostValuable = parts.filter { (it.unitCost ?: 0.0) * it.quantity > 0 }
                .sortedByDescending { (it.unitCost ?: 0.0) * it.quantity }.take(TOP_VALUE),
            sort = f.sort,
            query = f.query,
            bucket = f.bucket,
            lowOnly = f.lowOnly,
            bin = f.bin,
            overviewPieces = overview(parts.groupBy { it.category }.mapValues { (_, ps) -> ps.sumOf { it.quantity }.toDouble() }),
            overviewValue = overview(
                parts.groupBy { it.category }.mapValues { (_, ps) -> ps.sumOf { (it.unitCost ?: 0.0) * it.quantity } },
            ),
        )
    }

    /** Top [OVERVIEW_SLICES] categories by [byCategory], in the usual category order for ties, plus the rest. */
    private fun overview(byCategory: Map<String, Double>): List<OverviewSlice> {
        val cats = byCategory.keys.sortedBy { c -> Categories.ALL.indexOf(c).let { if (it < 0) 999 else it } }
        val slices = ChartData.topN(cats, DoubleArray(cats.size) { byCategory.getValue(cats[it]) }, OVERVIEW_SLICES, "Other")
        // The merged slice is called "Other" unless the real Other category is shown on its own.
        val merged = if (slices.any { it.index >= 0 && cats[it.index] == Categories.OTHER }) "More categories" else "Other"
        return slices.map { s ->
            if (s.index >= 0) OverviewSlice(cats[s.index], cats[s.index], s.value, s.fraction)
            else OverviewSlice(merged, null, s.value, s.fraction)
        }
    }

    private fun buildLocations(d: InventoryData, sort: BinStats.Sort, query: String): LocationsView {
        val max = d.locations.maxOfOrNull { it.pieces } ?: 0
        val bins = d.locations.map { b ->
            // Parts in this bin with how many pieces each has there.
            val inBin = d.parts.mapNotNull { p ->
                val stock = p.bins()
                if (Locations.keyOf(stock, b.name) == null) null else p to Locations.qtyIn(stock, b.name)
            }
            // Parts per category, in the usual category order so ties come out the same way everywhere.
            val byCat = inBin.groupingBy { it.first.category }.eachCount()
            val counts = LinkedHashMap<String, Int>()
            (Categories.ALL + byCat.keys).forEach { c -> byCat[c]?.let { counts.putIfAbsent(c, it) } }
            BinView(
                summary = b,
                fill = BinStats.fill(b.pieces, max),
                topCategories = BinStats.topCategories(counts, 3),
                preview = inBin.sortedByDescending { it.second }.take(PREVIEW).map { it.first },
                low = inBin.count { it.first.isLow() },
            )
        }
        val shown = BinStats.sort(bins.filter { BinStats.matches(it.summary.name, query) }, sort)
        return LocationsView(shown, d.locations.size, d.loaded)
    }

    companion object {
        /** Thumbnails per bin card. */
        const val PREVIEW = 4

        const val SEARCH_DEBOUNCE_MS = 150L

        const val OVERVIEW_SLICES = 6
        const val TOP_VALUE = 5
    }
}

/** A part as [PartSort] sees it. */
private class SortItem(val p: PartRow) : PartSort.Item {
    override fun key(): String = p.mpn.ifBlank { p.customerPn.ifBlank { p.distributorPn } }
    override fun quantity(): Int = p.quantity
    override fun unitCost(): Double? = p.unitCost
    override fun minQty(): Int? = p.minQty
    override fun updatedAt(): String = p.updatedAt
}
