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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.partstock.scanner.parse.Locations
import com.partstock.scanner.parse.PartSort
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
    /** Show only parts with stock in this bin (picked on the Locations screen); null = all. */
    bin: String? = null,
    onClearBin: () -> Unit = {},
    prefs: Prefs? = null,
    /** Open this part's page straight away (a part label was scanned). */
    openPart: Long? = null,
    /** "Scan a label" on the empty states. */
    onScan: () -> Unit = onBack,
    /** Told whether a part page fills the screen (one-pane layout), so the bottom bar can step aside. */
    onPartPage: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val db = vm.db
    // Parts, photos and filters come from the ViewModel, loaded and filtered off the main thread.
    remember(bin) { vm.bin.value = bin }
    // Saveable, so a configuration change that recreates the screen keeps the search and the open part.
    var query by rememberSaveable { mutableStateOf(vm.query.value) }
    var searching by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    // The filters live in the ViewModel; a saved copy brings them back after Android stops the app in the background
    // (or the activity is recreated, which clears the ViewModel's filters on the way out).
    var savedBucket by rememberSaveable { mutableStateOf(vm.bucket.value) }
    var savedLow by rememberSaveable { mutableStateOf(vm.lowOnly.value) }
    var savedUnpriced by rememberSaveable { mutableStateOf(vm.unpricedOnly.value) }
    remember {
        vm.query.value = query
        vm.bucket.value = savedBucket
        vm.lowOnly.value = savedLow
        vm.unpricedOnly.value = savedUnpriced
    }
    DisposableEffect(Unit) { onDispose { vm.clearFilters() } }
    val view by vm.view.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val bucket by vm.bucket.collectAsStateWithLifecycle()
    val lowOnly by vm.lowOnly.collectAsStateWithLifecycle()
    val unpricedOnly by vm.unpricedOnly.collectAsStateWithLifecycle()
    LaunchedEffect(bucket, lowOnly, unpricedOnly) {
        savedBucket = bucket
        savedLow = lowOnly
        savedUnpriced = unpricedOnly
    }
    val shown = view.shown
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    // Parts below their minimum, counted when the menu opens (null while counting).
    var reorderCount by remember { mutableStateOf<Int?>(null) }
    var selectedId by rememberSaveable { mutableStateOf(openPart) }
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
    // Saved with the screen, so the list comes back at the same place (also after the app was stopped).
    val listState = rememberLazyListState()
    val barState = rememberTopAppBarState()
    val focus = remember { FocusRequester() }
    val datasheets = rememberDatasheetOpener(db, secrets)
    // "$?" on unpriced rows only when a price could be looked up or some parts already have one.
    val hasPriceKey = remember { secrets.getMouserKey() != null || secrets.getDigiKey() != null }
    val unpricedBadge = hasPriceKey || view.unpriced < view.parts.size

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

    fun addParts(p: PartRow, n: Int) {
        scope.launch {
            val total = withContext(Dispatchers.IO) { db.addPieces(p.id, n) }
            UndoOffer.offer(context, "Added $n ${p.key()} · $total in stock", db.lastChange)
        }
    }

    fun addOne(p: PartRow) = addParts(p, 1)

    var swipeHint by remember { mutableStateOf(prefs?.swipeHintSeen == false) }
    fun swipeHintDone() {
        if (swipeHint) {
            swipeHint = false
            prefs?.swipeHintSeen = true
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

    LaunchedEffect(menuOpen, data) {
        if (menuOpen) reorderCount = try {
            withContext(Dispatchers.IO) { db.reorderCount() }
        } catch (e: Exception) {
            null
        }
    }
    if (menuOpen) {
        InventoryMenuSheet(
            actions = InventoryMenuActions(
                refreshPrices = { refreshing = true },
                buildKit = { buildingKit = true },
                importOrder = { importingOrder = true },
                shareInventory = { withCsv(db::inventoryCsv) { Export.share(context, Export.fileName("inventory"), it) } },
                saveInventory = {
                    withCsv(db::inventoryCsv) {
                        pendingCsv = it
                        saveFile.launch(Export.fileName("inventory"))
                    }
                },
                shareZip = {
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
                },
                saveZip = { saveZip.launch(Export.zipName()) },
                shareLog = { withCsv(db::logCsv) { Export.share(context, Export.fileName("scan-log"), it) } },
                saveLog = {
                    withCsv(db::logCsv) {
                        pendingCsv = it
                        saveFile.launch(Export.fileName("scan-log"))
                    }
                },
                labels = if (prefs != null) ({ labels = shown.map(::partLabel) }) else null,
                shareReorder = {
                    withCsv({ if (db.reorderCount() == 0) "" else db.reorderCsv() }) { csv ->
                        if (csv.isEmpty()) toast("Nothing is below its minimum")
                        else Export.share(context, Export.fileName("reorder"), csv)
                    }
                },
                saveReorder = {
                    withCsv({ if (db.reorderCount() == 0) "" else db.reorderCsv() }) { csv ->
                        if (csv.isEmpty()) toast("Nothing is below its minimum")
                        else {
                            pendingCsv = csv
                            saveFile.launch(Export.fileName("reorder"))
                        }
                    }
                },
            ),
            shownCount = shown.size,
            reorderCount = reorderCount,
            zipping = zipping,
            onDismiss = { menuOpen = false },
        )
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

    var overviewOpen by remember { mutableStateOf(prefs?.overviewOpen ?: true) }
    var viewMode by remember { mutableStateOf(PartSort.View.parse(prefs?.inventoryView)) }
    remember { prefs?.let { vm.sort.value = PartSort.Sort.parse(it.inventorySort) } }
    var overviewByValue by remember { mutableStateOf(prefs?.overviewByValue ?: false) }

    val listActions = InventoryListActions(
        open = { p -> selectedId = p.id },
        search = { q -> setQuery(q) },
        useOne = { p ->
            swipeHintDone()
            useParts(p, 1)
        },
        addOne = { p ->
            swipeHintDone()
            addOne(p)
        },
        useN = { p, n -> useParts(p, n) },
        addN = { p, n -> addParts(p, n) },
        dismissSwipeHint = ::swipeHintDone,
        setBucket = { vm.bucket.value = it },
        toggleLow = { vm.lowOnly.value = !lowOnly },
        setSort = { s ->
            vm.sort.value = s
            prefs?.inventorySort = s.name
        },
        setView = { v ->
            viewMode = v
            prefs?.inventoryView = v.name
        },
        clearBin = onClearBin,
        clearSearch = { setQuery("") },
        clearFilters = {
            vm.bucket.value = null
            vm.lowOnly.value = false
            vm.unpricedOnly.value = false
            if (query.isNotEmpty()) setQuery("")
        },
        scan = onScan,
        toggleOverview = {
            overviewOpen = !overviewOpen
            prefs?.overviewOpen = overviewOpen
        },
        toggleUnpriced = { vm.unpricedOnly.value = !vm.unpricedOnly.value },
        setOverviewByValue = { v ->
            overviewByValue = v
            prefs?.overviewByValue = v
        },
    )

    // Two panes (list | details) on tablets and unfolded foldables; a phone in landscape keeps one pane.
    val config = LocalConfiguration.current
    val twoPane = config.smallestScreenWidthDp >= 600 && config.screenWidthDp >= 600
    val partPage = selected != null && !twoPane
    LaunchedEffect(partPage) { onPartPage(partPage) }
    DisposableEffect(Unit) { onDispose { onPartPage(false) } }
    val landscape = config.screenWidthDp > config.screenHeightDp

    val detailsPane: @Composable (PartRow, SharedScopes?) -> Unit = { part, scopes ->
        PartDetails(
            part = part,
            photos = data.photos[part.id].orEmpty(),
            knownBins = data.locations.map { it.name },
            scopes = scopes,
            animate = !reduceMotion,
            datasheets = datasheets,
            actions = PartDetailsActions(
                back = { selectedId = null },
                search = { q ->
                    selectedId = null
                    setQuery(q)
                },
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
            loadHistory = { withContext(Dispatchers.IO) { db.history(part) } },
        )
    }

    val listPane: @Composable (SharedScopes?) -> Unit = { scopes ->
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(barState)
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                val menu: @Composable () -> Unit = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More: prices, import, export, labels, reorder and kits")
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
                                // Results so far (the list filters as you type).
                                Text(
                                    "${shown.size}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.semantics {
                                        contentDescription = "${shown.size} result${if (shown.size == 1) "" else "s"}"
                                    },
                                )
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
                overviewOpen = overviewOpen,
                overviewByValue = overviewByValue,
                sideBySide = landscape && !twoPane,
                unpricedBadge = unpricedBadge,
                swipeHint = swipeHint,
                viewMode = viewMode,
                modifier = Modifier.padding(top = padding.calculateTopPadding()),
            )
        }
    }

    if (twoPane) {
        // Selecting a row fills the right pane instead of opening a page; no shared-element flight here.
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(0.45f).fillMaxHeight()) { listPane(null) }
            VerticalDivider()
            Box(Modifier.weight(0.55f).fillMaxHeight()) {
                val part = selected
                if (part != null) {
                    key(part.id) { detailsPane(part, null) }
                } else {
                    NoPartSelected(Modifier.align(Alignment.Center))
                }
            }
        }
    } else {
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
                if (part != null) detailsPane(part, scopes) else listPane(scopes)
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

/** Right pane before a part is picked. */
@Composable
private fun NoPartSelected(modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Filled.Inventory2, contentDescription = null, modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Pick a part to see its details",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
