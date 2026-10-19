package com.partstock.scanner

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.selection.selectable
import com.partstock.scanner.parse.InventoryCsv
import com.partstock.scanner.parse.SyncStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(secrets: SecretStore, db: InventoryDb, prefs: Prefs, onDone: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(secrets.hasMouserKey()) }
    var autoLookup by remember { mutableStateOf(prefs.autoMouserLookup) }
    var readBarcodes by remember { mutableStateOf(prefs.readBarcodes) }
    var status by remember { mutableStateOf<String?>(null) }
    var dkId by remember { mutableStateOf("") }
    var dkSecret by remember { mutableStateOf("") }
    var dkSaved by remember { mutableStateOf(secrets.hasDigiKey()) }
    var dkStatus by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<Updater.Release?>(null) }
    update?.let { r -> UpdateDialog(release = r, onLater = { update = null }, onClose = { update = null }) }

    // ---- backup & restore ----
    var restore by remember { mutableStateOf<RestorePlan?>(null) }
    var restoreStatus by remember { mutableStateOf<String?>(null) }
    var backupFolder by remember { mutableStateOf(prefs.autoBackupUri) }
    var backupStatus by remember { mutableStateOf(prefs.autoBackupStatus) }
    var busy by remember { mutableStateOf(false) }

    // ---- sync to a file ----
    var syncFile by remember { mutableStateOf(prefs.syncFileUri) }
    var syncTick by remember { mutableIntStateOf(0) } // bumped by sync results and once a minute ("2 min ago")
    DisposableEffect(Unit) {
        FileSync.onStatus = { syncTick++ }
        onDispose { FileSync.onStatus = null }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            syncTick++
        }
    }
    fun startSync(uri: Uri?) {
        if (uri == null) return
        FileSync.choose(context, prefs, uri)
        syncFile = prefs.syncFileUri
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) { FileSync.runNow(context, prefs, db) }
            syncTick++
            busy = false
        }
    }
    val createSyncFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { startSync(it) }
    val pickSyncFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { startSync(it) }

    val pickCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        restoreStatus = null
        scope.launch {
            try {
                restore = withContext(Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: error("Couldn't open the file")
                    val res = InventoryCsv.read(text)
                    if (res.rows.isEmpty()) error("No parts found in the file")
                    RestorePlan(
                        rows = res.rows,
                        skipped = res.skipped,
                        merge = db.importRows(res.rows, replace = false, dryRun = true),
                        replace = db.importRows(res.rows, replace = true, dryRun = true),
                    )
                }
            } catch (e: Exception) {
                restoreStatus = "Can't restore: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Exception) {
            // Some providers grant access without a persistable permission; the backup reports it if writing fails.
        }
        prefs.autoBackupUri = uri.toString()
        backupFolder = uri.toString()
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) { AutoBackup.runNow(context, prefs, db) }
            backupStatus = prefs.autoBackupStatus
            busy = false
        }
    }

    restore?.let { plan ->
        RestoreDialog(
            plan = plan,
            onCancel = { restore = null },
            onConfirm = { replace ->
                restore = null
                busy = true
                scope.launch {
                    try {
                        val s = withContext(Dispatchers.IO) {
                            // Keep a copy of what's there now before replacing it.
                            if (replace && prefs.autoBackupUri.isNotBlank()) AutoBackup.runNow(context, prefs, db)
                            db.importRows(plan.rows, replace = replace, dryRun = false)
                        }
                        restoreStatus = if (replace) "Restored ${s.added} parts (replaced ${s.removed})."
                        else "Merged: ${s.added} new, ${s.updated} updated."
                    } catch (e: Exception) {
                        restoreStatus = "Restore failed, nothing changed: ${e.message}"
                    } finally {
                        busy = false
                    }
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings"); error("Deliberately broken screen (smoke test check)") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Mouser Search API key", style = MaterialTheme.typography.titleMedium)
            Text(
                "Used only to look up the price, link and official description of a part on Mouser. " +
                    "It is stored encrypted on this phone with the Android Keystore. It is not in the app's code, " +
                    "it is left out of backups, and it never appears in exports.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (saved) "A key is saved on this phone." else "No key saved yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim() },
                label = { Text(if (saved) "Replace key" else "Paste key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = key.isNotBlank(),
                    onClick = {
                        secrets.setMouserKey(key)
                        key = ""
                        saved = secrets.hasMouserKey()
                        status = "Key saved"
                    },
                ) { Text("Save key") }
                if (saved) {
                    OutlinedButton(onClick = {
                        secrets.setMouserKey(null)
                        saved = false
                        status = "Key removed"
                    }) { Text("Remove key") }
                }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (saved) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Look up on Mouser after each scan", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Fills in the price as soon as a label is read, without tapping the button.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoLookup, onCheckedChange = {
                        autoLookup = it
                        prefs.autoMouserLookup = it
                    })
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Read label barcodes", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Also reads the DigiKey DataMatrix and Mouser barcodes on each scan. Barcodes are exact, so " +
                            "where one disagrees with the text read, the barcode is used and the field says \"from barcode\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = readBarcodes, onCheckedChange = {
                    readBarcodes = it
                    prefs.readBarcodes = it
                })
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Wallpaper colors", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Colors the app from your wallpaper (Android dynamic color). Off: Part Stock's own navy and amber.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = AppTheme.dynamicColor, onCheckedChange = {
                        AppTheme.dynamicColor = it
                        prefs.dynamicColor = it
                    })
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("DigiKey API (Client ID and Secret)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Turns on \"Look up on DigiKey\" for DigiKey labels: price, link, description and category. " +
                    "Create an app with the Product Information API at developer.digikey.com and paste its " +
                    "Client ID and Client Secret here. Both are stored encrypted on this phone like the Mouser key, " +
                    "left out of backups and never shown in exports.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (dkSaved) "DigiKey credentials are saved on this phone." else "No DigiKey credentials saved yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = dkId,
                onValueChange = { dkId = it.trim() },
                label = { Text(if (dkSaved) "Replace Client ID" else "Client ID") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = dkSecret,
                onValueChange = { dkSecret = it.trim() },
                label = { Text(if (dkSaved) "Replace Client Secret" else "Client Secret") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = dkId.isNotBlank() && dkSecret.isNotBlank(),
                    onClick = {
                        secrets.setDigiKey(dkId, dkSecret)
                        dkId = ""
                        dkSecret = ""
                        dkSaved = secrets.hasDigiKey()
                        dkStatus = "DigiKey credentials saved"
                    },
                ) { Text("Save") }
                if (dkSaved) {
                    OutlinedButton(onClick = {
                        secrets.setDigiKey(null, null)
                        dkSaved = false
                        dkStatus = "DigiKey credentials removed"
                    }) { Text("Remove") }
                }
            }
            dkStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Backup & restore", style = MaterialTheme.typography.titleMedium)
            Text(
                "The inventory lives only on this phone. Export it from the Inventory screen, or turn on auto backup, " +
                    "then restore that CSV here after a reinstall or on a new phone.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(enabled = !busy, onClick = {
                pickCsv.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream"))
            }) { Text("Restore from CSV…") }
            restoreStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            Text("Auto backup", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Text(
                if (backupFolder.isBlank()) "Off. Pick a folder and the inventory CSV is written there after every save " +
                    "(the newest ${AutoBackup.KEEP} files are kept). A folder synced by Drive or another cloud app works."
                else "On: writes to ${AutoBackup.folderLabel(backupFolder)} after every save, keeping the newest ${AutoBackup.KEEP}.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(enabled = !busy, onClick = { pickFolder.launch(null) }) {
                    Text(if (backupFolder.isBlank()) "Choose folder…" else "Change folder…")
                }
                if (backupFolder.isNotBlank()) {
                    OutlinedButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            withContext(Dispatchers.IO) { AutoBackup.runNow(context, prefs, db) }
                            backupStatus = prefs.autoBackupStatus
                            busy = false
                        }
                    }) { Text("Back up now") }
                    TextButton(onClick = {
                        try {
                            context.contentResolver.releasePersistableUriPermission(
                                Uri.parse(backupFolder),
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                            )
                        } catch (_: Exception) {
                        }
                        prefs.autoBackupUri = ""
                        prefs.autoBackupStatus = ""
                        backupFolder = ""
                        backupStatus = ""
                    }) { Text("Turn off") }
                }
            }
            if (backupFolder.isNotBlank() && backupStatus.isNotBlank()) {
                Text(backupStatus, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text("Sync to a file", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Text(
                if (syncFile.isBlank()) "Off. Pick or create one CSV file and it is rewritten with the inventory after every " +
                    "change. Choose Google Drive in the file picker and opening that file in Google Sheets always shows " +
                    "current stock. No login or key is stored in the app."
                else "On: rewrites ${prefs.syncFileName.ifBlank { "the chosen file" }} after every change.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(enabled = !busy, onClick = { createSyncFile.launch("part-stock-inventory.csv") }) {
                    Text("New file…")
                }
                OutlinedButton(enabled = !busy, onClick = {
                    pickSyncFile.launch(arrayOf("text/*", "application/csv", "text/comma-separated-values", "application/octet-stream"))
                }) { Text("Existing file…") }
            }
            if (syncFile.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            withContext(Dispatchers.IO) { FileSync.runNow(context, prefs, db) }
                            syncTick++
                            busy = false
                        }
                    }) { Text("Sync now") }
                    TextButton(onClick = {
                        FileSync.turnOff(context, prefs)
                        syncFile = ""
                    }) { Text("Turn off") }
                }
                val line = remember(syncTick, syncFile) {
                    SyncStatus.line(prefs.syncLastOk, prefs.syncError, System.currentTimeMillis())
                }
                Text(
                    line, style = MaterialTheme.typography.bodySmall,
                    color = if (prefs.syncError.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("App updates", style = MaterialTheme.typography.titleMedium)
            Text(
                "This is ${Updater.currentName(context)}. The app checks GitHub for a newer build when it starts " +
                    "and offers to install it. Your inventory is kept.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                enabled = !checking,
                onClick = {
                    checking = true
                    updateStatus = null
                    scope.launch {
                        try {
                            val r = Updater.latest()
                            if (r != null && r.build > Updater.currentBuild(context)) update = r
                            else updateStatus = "You have the latest build."
                        } catch (e: Exception) {
                            updateStatus = "Couldn't check: ${e.message}"
                        } finally {
                            checking = false
                        }
                    }
                },
            ) { Text(if (checking) "Checking…" else "Check for updates") }
            updateStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

private class RestorePlan(
    val rows: List<InventoryCsv.Row>,
    val skipped: Int,
    val merge: ImportSummary,
    val replace: ImportSummary,
)

@Composable
private fun RestoreDialog(plan: RestorePlan, onCancel: () -> Unit, onConfirm: (replace: Boolean) -> Unit) {
    var replace by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    val n = plan.rows.size
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Restore $n part${if (n == 1) "" else "s"}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(false to "Merge", true to "Replace everything").forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = replace == value, onClick = {
                            replace = value
                            confirmReplace = false
                        }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = replace == value, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                val m = plan.merge
                val r = plan.replace
                Text(
                    if (!replace) "$n parts: ${m.added} new, ${m.updated} updated. Quantities are set from the file; " +
                        "parts not in the file are kept."
                    else "Your ${r.removed} current part${if (r.removed == 1) "" else "s"} will be removed and replaced " +
                        "by ${r.added} from the file. The scan log is kept.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (r.added < n && replace) {
                    Text("${n - r.added} rows repeat a part and are merged.", style = MaterialTheme.typography.bodySmall)
                }
                if (plan.skipped > 0) {
                    Text(
                        "${plan.skipped} row${if (plan.skipped == 1) "" else "s"} without any part number will be skipped.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!replace) onConfirm(false)
                else if (confirmReplace) onConfirm(true)
                else confirmReplace = true
            }) {
                Text(
                    when {
                        !replace -> "Merge"
                        confirmReplace -> "Tap again to replace"
                        else -> "Replace"
                    },
                    color = if (replace) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
