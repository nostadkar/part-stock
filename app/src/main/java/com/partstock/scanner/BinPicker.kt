package com.partstock.scanner

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.partstock.scanner.parse.Locations

/** Every bin in the inventory with its part count, provided once by MainActivity for all bin pickers. */
val LocalBins = compositionLocalOf<List<LocationSummary>> { emptyList() }

/** How many recent bins the picker offers as chips. */
private const val RECENT_BINS = 5

/**
 * The one bin field used everywhere a bin is typed (Review, batch bin, Move, Rename, order import):
 * - a drop-down of existing bins filtered as you type ("A3 · 12 parts"),
 * - "Create bin …" only when nothing matches, and "Did you mean A3?" when the text is a near-duplicate ("A-3"),
 * - a row of recently used bins.
 * The text is passed back as typed; saving still goes through [Locations.normalize], so the stored format is unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BinPicker(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Bin / location",
    bins: List<LocationSummary> = LocalBins.current,
    /** A bin not to offer (the bin being moved from or renamed). */
    exclude: String? = null,
    showRecent: Boolean = true,
    last: Boolean = false,
    placeholder: String? = null,
) {
    val context = LocalContext.current
    val recent = remember { if (showRecent) Prefs(context).recentBins else emptyList() }
    val names = remember(bins, exclude) {
        bins.map { it.name }.filter { exclude == null || !Locations.same(it, exclude) }
    }
    var focused by remember { mutableStateOf(false) }
    var closed by remember { mutableStateOf(false) }
    val typed = value.trim()
    val choices = remember(names, typed) { Locations.suggest(names, typed, 6) }
    val exact = Locations.exists(names, typed)
    val close = remember(names, typed) { Locations.closeMatch(names, typed) }
    val offerCreate = typed.isNotEmpty() && !exact && close == null && !Locations.same(typed, Locations.UNASSIGNED)
    // Nothing to show when the only suggestion is exactly what's typed.
    val onlyItself = choices.size == 1 && Locations.same(choices[0], typed) && !offerCreate
    val expanded = focused && !closed && (choices.isNotEmpty() || offerCreate) && !onlyItself

    fun pick(b: String) {
        onValueChange(b)
        closed = true
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { closed = !it }) {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    onValueChange(it)
                    closed = false
                },
                label = { Text(label) },
                placeholder = placeholder?.let { { Text(it) } },
                singleLine = true,
                trailingIcon = if (names.isNotEmpty()) ({ ExposedDropdownMenuDefaults.TrailingIcon(expanded) }) else null,
                keyboardOptions = codeKeys(last),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable)
                    .onFocusChanged { focused = it.isFocused }
                    .bringIntoViewOnFocus(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { closed = true }) {
                choices.forEach { b ->
                    DropdownMenuItem(
                        text = { Text(binLine(b, bins)) },
                        leadingIcon = { Icon(Icons.Filled.Inventory2, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        onClick = { pick(b) },
                    )
                }
                if (offerCreate) {
                    DropdownMenuItem(
                        text = { Text("Create bin \"$typed\"", fontWeight = FontWeight.Medium) },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        onClick = { closed = true },
                    )
                }
            }
        }
        if (close != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Did you mean $close? \"$typed\" would be a new bin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { pick(close) }) { Text("Use $close") }
            }
        }
        val chips = recent.filter { r -> (exclude == null || !Locations.same(r, exclude)) }.take(RECENT_BINS)
        if (chips.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.History, contentDescription = "Recent bins",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp),
                )
                chips.forEach { r ->
                    FilterChip(
                        selected = typed.isNotEmpty() && Locations.same(r, typed),
                        onClick = { pick(r) },
                        label = { Text(r) },
                    )
                }
            }
        }
    }
}

/** "A3 · 12 parts" (just the name when the count isn't known). */
private fun binLine(name: String, bins: List<LocationSummary>): String {
    val n = bins.firstOrNull { Locations.same(it.name, name) }?.parts ?: 0
    return if (n > 0) "$name · $n part${if (n == 1) "" else "s"}" else name
}

/** Bins as [LocationSummary] for callers that only know the names. */
internal fun binsOrNames(names: List<String>, all: List<LocationSummary>): List<LocationSummary> =
    all.ifEmpty { names.map { LocationSummary(it, 0, 0) } }
