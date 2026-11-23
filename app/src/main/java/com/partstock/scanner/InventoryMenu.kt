package com.partstock.scanner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The Inventory "⋮" actions; each one is the handler the old dropdown used. [labels] is null when labels are off. */
class InventoryMenuActions(
    val refreshPrices: () -> Unit,
    val buildKit: () -> Unit,
    val importOrder: () -> Unit,
    val shareInventory: () -> Unit,
    val saveInventory: () -> Unit,
    val shareZip: () -> Unit,
    val saveZip: () -> Unit,
    val shareLog: () -> Unit,
    val saveLog: () -> Unit,
    val labels: (() -> Unit)?,
    val shareReorder: () -> Unit,
    val saveReorder: () -> Unit,
    /** Opens the Reorder screen (editable quantities, estimated cost). */
    val openReorder: () -> Unit = {},
    /** Opens the Activity page (added vs used per week, most used, untouched stock). */
    val openActivity: () -> Unit = {},
)

/**
 * The Inventory menu as a bottom sheet, grouped into Prices · Import · Export & share · Labels · Reorder · Kits.
 * [reorderCount]: parts below their minimum (null while counting). [zipping]: a photos zip is being built (its
 * progress card is at the bottom of the screen), so another can't be started.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryMenuSheet(
    actions: InventoryMenuActions,
    shownCount: Int,
    reorderCount: Int?,
    zipping: Boolean,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun run(action: () -> Unit) {
        onDismiss()
        action()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp)) {
            Section("Prices")
            MenuRow(Icons.Filled.AttachMoney, "Refresh prices from Mouser…", onClick = { run(actions.refreshPrices) })

            Section("Import")
            MenuRow(Icons.Filled.FileUpload, "Import Mouser / DigiKey order CSV…", onClick = { run(actions.importOrder) })

            Section("Export & share")
            ShareSaveRow(
                Icons.Filled.Description, "Inventory CSV",
                share = { run(actions.shareInventory) }, save = { run(actions.saveInventory) },
            )
            ShareSaveRow(
                Icons.Filled.FolderZip, "Inventory + photos (zip)",
                supporting = if (zipping) "Export running…" else null,
                share = { run(actions.shareZip) }, save = { run(actions.saveZip) }, enabled = !zipping,
            )
            ShareSaveRow(
                Icons.Filled.History, "Scan log CSV",
                share = { run(actions.shareLog) }, save = { run(actions.saveLog) },
            )

            actions.labels?.let { labels ->
                Section("Labels")
                MenuRow(Icons.Filled.QrCode2, "QR labels for the $shownCount shown parts…", onClick = { run(labels) })
            }

            Section("Reorder")
            val none = reorderCount == 0
            MenuRow(
                Icons.Filled.ShoppingCart,
                if (reorderCount == null || reorderCount == 0) "Reorder list…" else "Reorder list ($reorderCount)…",
                onClick = { run(actions.openReorder) },
            )
            ShareSaveRow(
                Icons.Filled.ShoppingCart,
                if (reorderCount == null) "Reorder list CSV" else "Reorder list CSV ($reorderCount)",
                supporting = if (none) "Nothing is below its minimum" else null,
                share = { run(actions.shareReorder) }, save = { run(actions.saveReorder) },
                enabled = !none,
            )

            Section("Activity")
            MenuRow(Icons.Filled.BarChart, "Activity: added vs used, most used…", onClick = { run(actions.openActivity) })

            Section("Kits")
            MenuRow(Icons.Filled.Build, "Build kit…", onClick = { run(actions.buildKit) })
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 2.dp).semantics { heading() },
    )
}

@Composable
private fun MenuRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text) },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
    )
}

/** One row per export with Share and Save buttons, instead of two menu items. */
@Composable
private fun ShareSaveRow(
    icon: ImageVector,
    text: String,
    share: () -> Unit,
    save: () -> Unit,
    enabled: Boolean = true,
    supporting: String? = null,
) {
    ListItem(
        headlineContent = { Text(text) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = {
            Row {
                IconButton(onClick = share, enabled = enabled) {
                    Icon(Icons.Filled.Share, contentDescription = "Share $text")
                }
                IconButton(onClick = save, enabled = enabled) {
                    Icon(Icons.Filled.Save, contentDescription = "Save $text to file")
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}
