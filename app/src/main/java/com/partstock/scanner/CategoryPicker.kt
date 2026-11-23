package com.partstock.scanner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.partstock.scanner.parse.Categories

/**
 * Button showing the category; tap to open a sheet of category tiles. With [description] the sheet starts with an
 * "Auto (from description)" tile that picks the category the description suggests.
 */
@Composable
fun CategoryPicker(value: String, description: String? = null, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Icon(categoryIcon(value), contentDescription = null, modifier = Modifier.size(18.dp))
        Text("Category: $value", modifier = Modifier.weight(1f).padding(start = 8.dp))
        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Change category")
    }
    if (open) {
        CategorySheet(
            title = "Category",
            categories = Categories.ALL,
            selected = value,
            auto = description?.let { Categories.classify(it) },
            onPick = {
                open = false
                onChange(it)
            },
            onDismiss = { open = false },
        )
    }
}

/** The search box appears once there are more categories than this. */
private const val SEARCH_FROM = 9

/**
 * Bottom sheet with a 3-column grid of category tiles (icon + name), [selected] highlighted. [counts] adds
 * "· N" to each name (Inventory filter); [auto] adds a first "Auto (from description)" tile that picks it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategorySheet(
    title: String,
    categories: List<String>,
    selected: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    counts: Map<String, Int>? = null,
    auto: String? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val shown = remember(categories, query) { Categories.search(categories, query) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            if (categories.size > SEARCH_FROM) {
                OutlinedTextField(
                    query, { query = it },
                    placeholder = { Text("Search categories") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = textKeys(last = true),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.heightIn(max = 560.dp),
            ) {
                if (auto != null && query.isBlank()) {
                    item(key = "auto") {
                        CategoryTile(
                            name = "Auto (from description)",
                            detail = auto,
                            icon = Icons.Filled.AutoAwesome,
                            selected = false,
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer,
                            onClick = { onPick(auto) },
                        )
                    }
                }
                items(shown, key = { it }) { c ->
                    val tint = categoryTint(c)
                    CategoryTile(
                        name = c,
                        detail = counts?.get(c)?.let { "$it part${if (it == 1) "" else "s"}" },
                        icon = categoryIcon(c),
                        selected = c == selected,
                        container = if (c == selected) tint.container else MaterialTheme.colorScheme.surfaceVariant,
                        content = if (c == selected) tint.onContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        border = if (c == selected) tint.accent else null,
                        onClick = { onPick(c) },
                    )
                }
                if (shown.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "No category matches \"${query.trim()}\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryTile(
    name: String,
    detail: String?,
    icon: ImageVector,
    selected: Boolean,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    border: Color? = null,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = container,
        contentColor = content,
        border = border?.let { BorderStroke(2.dp, it) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .semantics {
                this.selected = selected
                role = Role.RadioButton
            },
    ) {
        Column(
            Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
            Text(
                name, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    detail, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = content.copy(alpha = 0.8f),
                )
            }
        }
    }
}
