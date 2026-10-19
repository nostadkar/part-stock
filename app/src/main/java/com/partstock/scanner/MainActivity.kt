package com.partstock.scanner

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.partstock.scanner.parse.LabelBarcode
import com.partstock.scanner.parse.LabelCodes
import com.partstock.scanner.parse.Locations
import com.partstock.scanner.parse.LabelParser
import com.partstock.scanner.parse.ParsedPart
import com.partstock.scanner.parse.ScanMerger

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Debug builds log any disk access on the main thread (the database is read on IO; see InventoryViewModel).
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyLog().build())
        }
        enableEdgeToEdge()
        val prefs = Prefs(this)
        val db = InventoryDb(applicationContext)
        val secrets = SecretStore(applicationContext)
        db.onChanged = {
            AutoBackup.schedule(applicationContext, prefs, db)
            FileSync.schedule(applicationContext, prefs, db)
        }
        // A change that couldn't reach the sync file last time (offline, app closed) is retried now.
        if (prefs.syncPending) FileSync.schedule(applicationContext, prefs, db)
        setContent {
            PartStockTheme { App(prefs, db, secrets) }
        }
    }
}

private sealed interface Screen {
    data object Scan : Screen
    /** [photo] is one of the photos that was read, shown on the review screen to compare against. */
    data class Review(val part: ParsedPart, val photo: Bitmap? = null, val batchId: String? = null) : Screen
    /** [bin] = show only the parts in that bin (opened from the Locations screen). */
    data class Inventory(val bin: String? = null, val openPart: Long? = null) : Screen
    data object Locations : Screen
    data object Settings : Screen
    data object Projects : Screen
}

@Composable
private fun App(prefs: Prefs, db: InventoryDb, secrets: SecretStore) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Scan) }
    // Parts are loaded on IO and kept up to date by the ViewModel (no database reads on the main thread).
    val vm: InventoryViewModel = viewModel { InventoryViewModel(db) }
    val inventory by vm.data.collectAsStateWithLifecycle()
    val partCount = inventory.parts.size
    // Batch scanning: the queue lives in a ViewModel (lookups keep running while a bag is reviewed) and in a file.
    val batch: BatchViewModel = viewModel { BatchViewModel(context.applicationContext, db, prefs, secrets) }
    val batchItems by batch.items.collectAsStateWithLifecycle()
    val batchCards = remember(batchItems) { BatchQueue.evaluate(batchItems) }
    var batchMode by remember { mutableStateOf(prefs.batchMode) }
    var batchBin by remember { mutableStateOf(prefs.batchBin) }
    var batchSaving by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    // Undo snackbar after any save; an undo reloads the current screen.
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var dataVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        UndoOffer.show = { msg, group ->
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val undoable = withContext(Dispatchers.IO) { db.canUndo(group) }
                val r = snackbar.showSnackbar(
                    msg, actionLabel = if (undoable) "Undo" else null, withDismissAction = !undoable,
                    duration = if (undoable) SnackbarDuration.Long else SnackbarDuration.Short,
                )
                if (r == SnackbarResult.ActionPerformed) {
                    val n = withContext(Dispatchers.IO) { db.undo(group) }
                    (screen as? Screen.Inventory)?.let { if (it.openPart != null) screen = it.copy(openPart = null) }
                    dataVersion++
                    snackbar.showSnackbar(if (n > 0) "Undone" else "Couldn't undo that any more", duration = SnackbarDuration.Short)
                }
            }
        }
        onDispose { UndoOffer.show = null }
    }

    // Look for a newer build on GitHub at launch (quietly; offline is fine).
    var update by remember { mutableStateOf<Updater.Release?>(null) }
    LaunchedEffect(Unit) { update = Updater.autoCheck(context, prefs) }
    update?.let { r ->
        UpdateDialog(
            release = r,
            onLater = {
                prefs.dismissedBuild = r.build
                update = null
            },
            onClose = { update = null },
        )
    }

    /** Opens a queued bag in the normal review screen (photo read off the main thread). */
    fun reviewBatch(card: BatchCard) {
        scope.launch {
            val photo = withContext(Dispatchers.IO) { batch.photo(card.item) }
            screen = Screen.Review(card.part, photo, batchId = card.item.id)
        }
    }

    /** Next bag that needs a look, oldest first; null when none is left. */
    fun nextToReview(): BatchCard? = BatchQueue.evaluate(batch.items.value)
        .firstOrNull { it.status == BatchStatus.CHECK || it.status == BatchStatus.DUPLICATE }

    fun saveConfident() {
        if (batchSaving) return
        batchSaving = true
        scope.launch {
            try {
                val n = batch.saveConfident(batchBin)
                if (n > 0) {
                    val where = if (batchBin.isBlank()) "" else " to ${Locations.normalize(batchBin)}"
                    UndoOffer.offer(context, if (n == 1) "Saved 1 bag$where" else "Saved $n bags$where", db.lastChange)
                } else {
                    toast("No bag passes all the checks right now")
                }
            } catch (e: Exception) {
                toast("Save failed: ${e.message}")
            } finally {
                batchSaving = false
            }
        }
    }

    val reduceMotion = rememberReduceMotion()
    Box(Modifier.fillMaxSize()) {
    key(dataVersion) {
    // Shared-axis style: forward slides in from the right, back reverses it; none with "Remove animations".
    AnimatedContent(
        targetState = screen,
        contentKey = { it::class },
        transitionSpec = { screenTransition(reduceMotion) },
        label = "screens",
    ) { s ->
    when (s) {
        Screen.Scan -> ScanScreen(
            partCount = partCount,
            onInventory = { screen = Screen.Inventory() },
            onSettings = { screen = Screen.Settings },
            // Always read barcodes so the app's own QR labels work; label barcodes only count when that's on.
            readBarcodes = true,
            onFrames = { frames, photo, codes ->
                val own = LabelCodes.first(codes)
                if (own != null && own.isPart()) {
                    if (inventory.parts.any { it.id == own.partId }) screen = Screen.Inventory(openPart = own.partId)
                    else toast("That label's part is no longer in the inventory")
                } else if (own != null) {
                    prefs.lastLocation = Locations.plain(own.bin)
                    if (batchMode) {
                        batchBin = Locations.plain(own.bin)
                        prefs.batchBin = batchBin
                    }
                    toast("Bin set to ${Locations.normalize(own.bin)} for the next saves")
                } else if (batchMode) {
                    val labelCodes = if (prefs.readBarcodes) codes.filterNot { LabelCodes.isOurs(it) } else emptyList()
                    if (frames.all { f -> f.all { it.text.isBlank() } }) toast("No text found. Move closer and avoid glare.")
                    else batch.add(frames, photo, labelCodes)
                } else {
                    // Barcodes are read exactly: where one disagrees with OCR, the barcode wins.
                    val labelCodes = if (prefs.readBarcodes) codes.filterNot { LabelCodes.isOurs(it) } else emptyList()
                    val part = LabelBarcode.apply(ScanMerger.merge(frames.map { LabelParser.parse(it) }), labelCodes)
                    if (part.rawText.isBlank()) toast("No text found. Move closer and avoid glare.")
                    else screen = Screen.Review(part, photo)
                }
            },
            onError = ::toast,
            batchMode = batchMode,
            onBatchMode = {
                batchMode = it
                prefs.batchMode = it
            },
            batchTop = {
                BatchBinChip(batchBin) {
                    batchBin = it
                    prefs.batchBin = it
                }
            },
            batchTray = { reading ->
                BatchTray(
                    cards = batchCards,
                    reading = reading,
                    saving = batchSaving,
                    onSaveConfident = ::saveConfident,
                    onReviewNext = { nextToReview()?.let(::reviewBatch) },
                    onOpen = ::reviewBatch,
                    onRemove = { batch.remove(it.item.id) },
                    onClear = { batch.clear() },
                )
            },
        )

        is Screen.Review -> {
            BackHandler { screen = Screen.Scan }
            // A new key per bag, so going from one batch bag straight to the next starts a fresh form.
            key(s) {
                ReviewScreen(
                    part = s.part,
                    photo = s.photo,
                    db = db,
                    prefs = prefs,
                    secrets = secrets,
                    onSaved = { msg ->
                        UndoOffer.offer(context, msg, db.lastChange)
                        val id = s.batchId
                        if (id != null) {
                            // Reviewed bags leave the batch; go on with the next one that needs a look.
                            batch.remove(id)
                            val next = nextToReview()
                            if (next != null) reviewBatch(next) else screen = Screen.Scan
                        } else {
                            screen = Screen.Scan
                        }
                    },
                    onCancel = { screen = Screen.Scan },
                    initialLocation = if (s.batchId != null) batchBin else null,
                )
            }
        }

        Screen.Settings -> {
            BackHandler { screen = Screen.Scan }
            SettingsScreen(secrets = secrets, db = db, prefs = prefs, onDone = { screen = Screen.Scan })
        }

        is Screen.Inventory -> {
            fun back() {
                if (s.bin != null) {
                    screen = Screen.Locations
                } else {
                    screen = Screen.Scan
                }
            }
            BackHandler { back() }
            InventoryScreen(
                vm = vm, secrets = secrets, onBack = ::back,
                onScan = { screen = Screen.Scan },
                onProjects = { screen = Screen.Projects },
                onLocations = { screen = Screen.Locations },
                bin = s.bin,
                onClearBin = { screen = Screen.Inventory() },
                prefs = prefs,
                openPart = s.openPart,
            )
        }

        Screen.Locations -> {
            BackHandler { screen = Screen.Inventory() }
            LocationsScreen(
                vm = vm, prefs = prefs,
                onBack = { screen = Screen.Inventory() },
                onOpen = { screen = Screen.Inventory(it) },
            )
        }

        Screen.Projects -> {
            BackHandler { screen = Screen.Inventory() }
            ProjectsScreen(db = db, onBack = { screen = Screen.Inventory() })
        }
    }
    }
    }
    SnackbarHost(
        snackbar,
        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 8.dp),
    )
    }
}

private fun Screen.depth(): Int = when (this) {
    Screen.Scan -> 0
    is Screen.Review, Screen.Settings, is Screen.Inventory -> 1
    Screen.Locations, Screen.Projects -> 2
}

private fun AnimatedContentTransitionScope<Screen>.screenTransition(reduceMotion: Boolean): ContentTransform {
    if (reduceMotion) return EnterTransition.None togetherWith ExitTransition.None
    val dir = if (targetState.depth() >= initialState.depth()) 1 else -1
    return (slideInHorizontally(tween(300)) { w -> dir * w / 5 } + fadeIn(tween(300))) togetherWith
        (slideOutHorizontally(tween(300)) { w -> -dir * w / 5 } + fadeOut(tween(150)))
}

@Composable
private fun PartStockTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
