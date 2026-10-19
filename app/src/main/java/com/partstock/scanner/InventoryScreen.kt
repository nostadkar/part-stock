package com.partstock.scanner

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.partstock.scanner.parse.Locations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Inventory: a list with a collapsing header, stat tiles, category chips and swipeable rows; tapping a row opens the
 * part's full-screen details page, with its photo and part number flying into place (shared elements).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun InventoryScreen(
    vm: InventoryViewModel,
    secrets: SecretStore,
    onBack: () -> Unit,
    onProjects: () -> Unit = {},
    onLocations: () -> Unit = {},
    /** Show only parts with stock in this bin (picked on the Locations screen); null = all. */
    bin: String? = null,
    onClearBin: () -> Unit = {},
    prefs: Prefs? = null,
    /** Open this part's page straight away (a part label was scanned). */
    openPart: Long? = null,
    /** "Scan a label" on the empty states. */
    onScan: () -> Unit = onBack,
) {
    val context = LocalContext.current
    val db = vm.db
    // Parts, photos and filters come from the ViewModel, loaded and filtered off the main thread.
    remember(bin) { vm.bin.value = bin }
    DisposableEffect(Unit) { onDispose { vm.clearFilters() } }
    val view by vm.view.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val bucket by vm.bucket.collectAsStateWithLifecycle()
    val lowOnly by vm.lowOnly.collectAsStateWithLifecycle()
    val shown = view.shown
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(vm.query.value) }
    var searching by remember { mutableStateOf(query.isNotEmpty()) }
    var menuOpen by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf(openPart) }
    // Follows the latest copy of the part, and closes the page when the part is gone (e.g. undone).
    val selected = selectedId?.let { id -> data.parts.firstOrNull { it.id == id } }
    LaunchedEffect(data.loaded, selected == null) {
        if (data.loaded && selectedId != null && selected == null) selectedId = null
    }
    var labels by remember { mutableStateOf<List<LabelItem>?>(null) }
    var history by remember { mutableStateOf<PartRow?>(null) }
    var pendingCsv by remember { mutableStateOf("") }
    var buildingKit by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var importingOrder by remember { mutableStateOf(false) }
    val reduceMotion = rememberReduceMotion()
    val listState = rememberLazyListState()
    val barState = rememberTopAppBarState()
    val focus = remember { FocusRequester() }
    val datasheets = rememberDatasheetOpener(db, secrets)

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    fun setQuery(q: String) {
        query = q
        vm.query.value = q // filtered (debounced) in the ViewModel
    }

    fun closeSearch() {
        setQuery("")
        searching = false
    }

    /** Builds a CSV on IO, then hands it over on the main thread. */
    fun withCsv(build: () -> String, then: (String) -> Unit) {
        scope.launch {
            val csv = try {
                withContext(Dispatchers.IO) { build() }
            } catch (e: Exception) {
                toast("Export failed: ${e.message}")
                return@launch
            }
            then(csv)
        }
    }

    fun useParts(p: PartRow, n: Int) {
        if (p.quantity <= 0) {
            toast("None of ${p.key()} left")
            return
        }
        scope.launch {
            val left = withContext(Dispatchers.IO) { db.use(p.id, n) }
            UndoOffer.offer(context, "Used $n ${p.key()} · $left left", db.lastChange)
        }
    }

    fun addOne(p: PartRow) {
        scope.launch {
            val total = withContext(Dispatchers.IO) { db.addPieces(p.id, 1) }
            UndoOffer.offer(context, "Added 1 ${p.key()} · $total in stock", db.lastChange)
        }
    }

    var zipping by remember { mutableStateOf(false) }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            zipping = true
            scope.launch {
                try {
                    val n = withContext(Dispatchers.IO) { Export.saveZip(context, uri, db) }
                    toast("Saved with $n photo${if (n == 1) "" else "s"}")
                } catch (e: Exception) {
                    toast("Save failed: ${e.message}")
                }
                zipping = false
            }
        }
    }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            try {
                Export.writeTo(context, uri, pendingCsv)
                toast("Saved")
            } catch (e: Exception) {
                toast("Save failed: ${e.message}")
            }
        }
    }

    BackHandler(enabled = searching && selectedId == null) { closeSearch() }
    LaunchedEffect(searching) {
        if (searching) {
            try {
                focus.requestFocus()
            } catch (e: IllegalStateException) {
                // not attached yet; the user can tap the field
            }
        }
    }

    val listActions = InventoryListActions(
        open = { p -> selectedId = p.id },
        useOne = { p -> useParts(p, 1) },
        addOne = ::addOne,
        setBucket = { vm.bucket.value = it },
        toggleLow = { vm.lowOnly.value = !lowOnly },
        clearFilters = {
            vm.bucket.value = null
            vm.lowOnly.value = false
            if (query.isNotEmpty()) setQuery("")
        },
        scan = onScan,
    )

    SharedTransitionLayout {
        AnimatedContent(
            targetState = selectedId,
            transitionSpec = {
                if (reduceMotion) EnterTransition.None togetherWith ExitTransition.None
                else fadeIn(tween(300)) togetherWith fadeOut(tween(300))
            },
            label = "inventory-details",
        ) { id ->
            val scopes = if (reduceMotion) null else SharedScopes(this@SharedTransitionLayout, this@AnimatedContent)
            val part = id?.let { pid -> data.parts.firstOrNull { it.id == pid } }
            if (part != null) {
                PartDetails(
                    part = part,
                    photos = data.photos[part.id].orEmpty(),
                    knownBins = data.locations.map { it.name },
                    scopes = scopes,
                    animate = !reduceMotion,
                    datasheets = datasheets,
                    actions = PartDetailsActions(
                        back = { selectedId = null },
                        save = { stock, loc, desc, cost, cat, min ->
                            scope.launch {
                                withContext(Dispatchers.IO) { db.update(part.id, stock, loc, desc, cost, cat, min) }
                                UndoOffer.offer(context, "Saved ${part.key()}", db.lastChange)
                            }
                        },
                        use = { n -> useParts(part, n) },
                        addOne = { addOne(part) },
                        delete = {
                            selectedId = null
                            scope.launch {
                                withContext(Dispatchers.IO) { db.delete(part.id) }
                                UndoOffer.offer(context, "Deleted ${part.key()}", db.lastChange)
                            }
                        },
                        move = { from, to, n ->
                            scope.launch {
                                val moved = withContext(Dispatchers.IO) { db.move(part.id, from, to, n) } ?: 0
                                if (moved > 0) {
                                    UndoOffer.offer(context, "Moved $moved from $from to ${Locations.normalize(to)}", db.lastChange)
                                } else {
                                    toast("Nothing to move")
                                }
                            }
                        },
                        history = { history = part },
                        label = if (prefs != null) ({ labels = listOf(partLabel(part)) }) else null,
                    ),
                    photoStrip = { PartPhotosRow(db, part.id) },
                )
            } else {
                val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(barState)
                Scaffold(
                    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                    topBar = {
                        val menu: @Composable () -> Unit = {
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "More: prices, kits, import and export")
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(text = { Text("Refresh prices from Mouser…") }, onClick = {
                                        menuOpen = false
                                        refreshing = true
                                    })
                                    DropdownMenuItem(text = { Text("Build kit…") }, onClick = {
                                        menuOpen = false
                                        buildingKit = true
                                    })
                                    HorizontalDivider()
                                    DropdownMenuItem(text = { Text("Import Mouser / DigiKey order CSV…") }, onClick = {
                                        menuOpen = false
                                        importingOrder = true
                                    })
                                    DropdownMenuItem(text = { Text("Share inventory CSV") }, onClick = {
                                        menuOpen = false
                                        withCsv(db::inventoryCsv) { Export.share(context, Export.fileName("inventory"), it) }
                                    })
                                    DropdownMenuItem(text = { Text("Save inventory CSV to file…") }, onClick = {
                                        menuOpen = false
                                        withCsv(db::inventoryCsv) {
                                            pendingCsv = it
                                            saveFile.launch(Export.fileName("inventory"))
                                        }
                                    })
                                    DropdownMenuItem(text = { Text("Share inventory + photos (zip)") }, enabled = !zipping, onClick = {
                                        menuOpen = false
                                        zipping = true
                                        scope.launch {
                                            try {
                                                val chooser = withContext(Dispatchers.IO) { Export.shareZip(context, db) }
                                                context.startActivity(chooser)
                                            } catch (e: Exception) {
                                                toast("Export failed: ${e.message}")
                                            }
                                            zipping = false
                                        }
                                    })
                                    DropdownMenuItem(text = { Text("Save inventory + photos (zip) to file…") }, enabled = !zipping, onClick = {
                                        menuOpen = false
                                        saveZip.launch(Export.zipName())
                                    })
                                    if (prefs != null) {
                                        DropdownMenuItem(text = { Text("Make QR labels for the ${shown.size} shown parts…") }, onClick = {
                                            menuOpen = false
                                            labels = shown.map(::partLabel)
                                        })
                                    }
                                    DropdownMenuItem(text = { Text("Share reorder list CSV") }, onClick = {
                                        menuOpen = false
                                        withCsv({ if (db.reorderCount() == 0) "" else db.reorderCsv() }) { csv ->
                                            if (csv.isEmpty()) toast("Nothing is below its minimum")
                                            else Export.share(context, Export.fileName("reorder"), csv)
                                        }
                                    })
                                    DropdownMenuItem(text = { Text("Save reorder list CSV to file…") }, onClick = {
                                        menuOpen = false
                                        withCsv({ if (db.reorderCount() == 0) "" else db.reorderCsv() }) { csv ->
                                            if (csv.isEmpty()) toast("Nothing is below its minimum")
                                            else {
                                                pendingCsv = csv
                                                saveFile.launch(Export.fileName("reorder"))
                                            }
                                        }
                                    })
                                    DropdownMenuItem(text = { Text("Share scan log CSV") }, onClick = {
                                        menuOpen = false
                                        withCsv(db::logCsv) { Export.share(context, Export.fileName("scan-log"), it) }
                                    })
                                    DropdownMenuItem(text = { Text("Save scan log CSV to file…") }, onClick = {
                                        menuOpen = false
                                        withCsv(db::logCsv) {
                                            pendingCsv = it
                                            saveFile.launch(Export.fileName("scan-log"))
                                        }
                                    })
                                }
                            }
                        }
                        if (searching) {
                            TopAppBar(
                                title = {
                                    TextField(
                                        value = query,
                                        onValueChange = ::setQuery,
                                        placeholder = { Text("Part, value, package, bin") },
                                        singleLine = true,
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = Color.Transparent,
                                            unfocusedContainerColor = Color.Transparent,
                                            focusedIndicatorColor = Color.Transparent,
                                            unfocusedIndicatorColor = Color.Transparent,
                                        ),
                                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                                    )
                                },
                                navigationIcon = {
                                    IconButton(onClick = ::closeSearch) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                                    }
                                },
                                actions = {
                                    if (query.isNotEmpty()) {
                                        IconButton(onClick = { setQuery("") }) {
                                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                                        }
                                    }
                                },
                            )
                        } else {
                            LargeTopAppBar(
                                title = { Text("Inventory") },
                                navigationIcon = {
                                    IconButton(onClick = onBack) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                    }
                                },
                                actions = {
                                    IconButton(onClick = { searching = true }) {
                                        Icon(Icons.Filled.Search, contentDescription = "Search")
                                    }
                                    IconButton(onClick = onLocations) {
                                        Icon(Icons.Filled.Inventory2, contentDescription = "Locations")
                                    }
                                    IconButton(onClick = onProjects) {
                                        Icon(Icons.Filled.DeveloperBoard, contentDescription = "Projects / BOMs")
                                    }
                                    menu()
                                },
                                scrollBehavior = scrollBehavior,
                            )
                        }
                    },
                ) { padding ->
                    InventoryList(
                        view = view,
                        query = query,
                        bucket = bucket,
                        lowOnly = lowOnly,
                        listState = listState,
                        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
                        actions = listActions,
                        scopes = scopes,
                        animate = !reduceMotion,
                        datasheets = datasheets,
                        modifier = Modifier.padding(top = padding.calculateTopPadding()),
                        header = {
                            if (bin != null) {
                                FilterChip(
                                    selected = true,
                                    onClick = onClearBin,
                                    label = { Text("Bin $bin · ${shown.size} part${if (shown.size == 1) "" else "s"} ✕") },
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    history?.let { p -> HistoryDialog(db, p) { history = null } }

    labels?.let { items ->
        if (prefs != null) LabelDialog(items, prefs) { labels = null }
    }

    if (refreshing) {
        RefreshPricesDialog(
            db = db,
            secrets = secrets,
            parts = data.parts,
            onClose = { refreshing = false },
        )
    }

    if (importingOrder) {
        OrderImportFlow(
            db = db,
            onClose = { msg ->
                importingOrder = false
                if (msg != null) UndoOffer.offer(context, msg, db.lastChange)
            },
        )
    }

    if (buildingKit) {
        BuildKitDialog(
            db = db,
            onDismiss = { buildingKit = false },
            onBuilt = { msg ->
                buildingKit = false
                UndoOffer.offer(context, msg, db.lastChange)
            },
        )
    }
}
