package com.partstock.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.partstock.scanner.parse.Categories
import com.partstock.scanner.parse.Locations
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
    /** [shown] grouped by category, in the usual category order. */
    val groups: List<Pair<String, List<PartRow>>> = emptyList(),
    /** Parts per category (of all parts, not just the shown ones). */
    val counts: Map<String, Int> = emptyMap(),
    val lowCount: Int = 0,
    /** True when any part has a minimum set (shows the Low stock chip). */
    val tracked: Boolean = false,
    val pieces: Int = 0,
    val value: Double = 0.0,
    val unpriced: Int = 0,
) {
    val parts: List<PartRow> get() = data.parts
    val photos: Map<Long, List<String>> get() = data.photos
    val loaded: Boolean get() = data.loaded
}

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
    val bin = MutableStateFlow<String?>(null)

    private val filters = combine(
        query.debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS },
        bucket, lowOnly, bin,
    ) { q, b, low, bin -> Filters(q, b, low, bin) }

    val view: StateFlow<InventoryView> = combine(data, filters) { d, f -> build(d, f) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, InventoryView())

    /** Back to all parts, no search (when the Inventory screen closes, like before). */
    fun clearFilters() {
        query.value = ""
        bucket.value = null
        lowOnly.value = false
    }

    private data class Filters(val query: String, val bucket: String?, val lowOnly: Boolean, val bin: String?)

    private fun build(d: InventoryData, f: Filters): InventoryView {
        val parts = d.parts
        val shown = parts.filter { p ->
            (f.bucket == null || p.category == f.bucket) && (!f.lowOnly || p.isLow()) &&
                (f.query.isBlank() || p.matches(f.query)) &&
                (f.bin == null || Locations.keyOf(p.bins(), f.bin) != null)
        }
        val order = Categories.ALL
        val groups = shown.groupBy { it.category }.toList()
            .sortedBy { (c, _) -> order.indexOf(c).let { if (it < 0) 999 else it } }
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
        )
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS = 150L
    }
}
