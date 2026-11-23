package com.partstock.scanner

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.widget.Toast
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.partstock.scanner.parse.ThemeMode
import com.partstock.scanner.parse.LabelBarcode
import com.partstock.scanner.parse.LabelCodes
import com.partstock.scanner.parse.Locations
import com.partstock.scanner.parse.LabelParser
import com.partstock.scanner.parse.OcrLine
import com.partstock.scanner.parse.ParsedPart
import com.partstock.scanner.parse.ScanMerger

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The splash (icon on the brand color) shows while the app starts, instead of a blank window.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Debug builds log any disk access on the main thread (the database is read on IO; see InventoryViewModel).
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyLog().build())
        }
        enableEdgeToEdge()
        val prefs = Prefs(this)
        AppTheme.dynamicColor = prefs.dynamicColor
        AppTheme.mode = ThemeMode.parse(prefs.themeMode)
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
    data class Review(
        val part: ParsedPart,
        val photo: Bitmap? = null,
        val batchId: String? = null,
        /** The photo's OCR lines as size-independent boxes (PhotoBoxes), drawn over it on the review screen. */
        val boxes: List<OcrLine> = emptyList(),
    ) : Screen
    /** [bin] = show only the parts in that bin (opened from the Locations screen). */
    data class Inventory(val bin: String? = null, val openPart: Long? = null) : Screen
    data object Locations : Screen
    data object Settings : Screen
    data object Projects : Screen
}

/** A name that is the same in every process (a class's hashCode isn't), so saved state inside a screen comes back. */
private val Screen.tag: String
    get() = when (this) {
        Screen.Scan -> "scan"
        is Screen.Review -> "review"
        is Screen.Inventory -> "inventory"
        Screen.Locations -> "locations"
        Screen.Settings -> "settings"
        Screen.Projects -> "projects"
    }

/**
 * Saves the open screen so it comes back after Android stops the app in the background. A review isn't saved (its photo
 * is too big for saved state): it comes back as Scan; a batch bag is still in the tray.
 */
private val ScreenSaver = Saver<Screen, String>(
    save = { s ->
        when (s) {
            is Screen.Inventory -> listOf("inventory", s.bin ?: "", s.openPart?.toString() ?: "").joinToString("\n")
            is Screen.Review -> "scan"
            else -> s.tag
        }
    },
    restore = { v ->
        val f = v.split("\n")
        when (f[0]) {
            "inventory" -> Screen.Inventory(
                bin = f.getOrNull(1)?.takeIf { it.isNotEmpty() },
                openPart = f.getOrNull(2)?.toLongOrNull(),
            )
            "locations" -> Screen.Locations
            "settings" -> Screen.Settings
            "projects" -> Screen.Projects
            else -> Screen.Scan
        }
    },
)

/** The top-level screens in the bottom bar (Settings stays in the Scan screen's top bar). */
private enum class Tab(val label: String, val icon: ImageVector) {
    SCAN("Scan", Icons.Filled.CameraAlt),
    INVENTORY("Inventory", Icons.AutoMirrored.Filled.ViewList),
    LOCATIONS("Locations", Icons.Filled.Inventory2),
    PROJECTS("Projects", Icons.Filled.DeveloperBoard),
}

private fun Screen.tab(): Tab? = when (this) {
    Screen.Scan -> Tab.SCAN
    is Screen.Inventory -> Tab.INVENTORY
    Screen.Locations -> Tab.LOCATIONS
    Screen.Projects -> Tab.PROJECTS
    is Screen.Review, Screen.Settings -> null
}

private fun Tab.screen(): Screen = when (this) {
    Tab.SCAN -> Screen.Scan
    Tab.INVENTORY -> Screen.Inventory()
    Tab.LOCATIONS -> Screen.Locations
    Tab.PROJECTS -> Screen.Projects
}

@Composable
private fun App(prefs: Prefs, db: InventoryDb, secrets: SecretStore) {
    val context = LocalContext.current
    // Saveable: after Android stops the app in the background it reopens on the same screen (the camera only starts
    // when that is Scan).
    var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.Scan) }
    // A part page is open on Inventory (phone layout): the bottom bar is hidden there.
    var partPageOpen by remember { mutableStateOf(false) }
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
            screen = Screen.Review(card.part, photo, batchId = card.item.id, boxes = card.item.photoBoxes)
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
    val tab = screen.tab()
    // Read through derivedStateOf, so the keyboard's open/close animation doesn't recompose the app every frame.
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val keyboardOpen by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    val showBar = tab != null && !keyboardOpen && !(tab == Tab.INVENTORY && partPageOpen)
    val config = LocalConfiguration.current
    // Landscape phones get a rail on the side instead, so the camera and lists keep their height.
    val rail = config.screenWidthDp > config.screenHeightDp
    val railInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)
    val goTo: (Tab) -> Unit = { t -> if (screen != t.screen()) screen = t.screen() }

    Row(Modifier.fillMaxSize()) {
    if (showBar && rail) {
        NavigationRail(windowInsets = railInsets) {
            Tab.entries.forEach { t ->
                NavigationRailItem(
                    selected = t == tab,
                    modifier = Modifier.testTag("tab-${t.name}"),
                    onClick = { goTo(t) },
                    icon = { Icon(t.icon, contentDescription = null) },
                    label = { Text(t.label) },
                )
            }
        }
    }
    Column(Modifier.weight(1f).fillMaxSize()) {
    // The bar takes the system navigation inset, so the screen above it doesn't pad for it again.
    val consumed = when {
        !showBar -> Modifier
        rail -> Modifier.consumeWindowInsets(railInsets.only(WindowInsetsSides.Start))
        else -> Modifier.consumeWindowInsets(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
    }
    Box(Modifier.weight(1f).fillMaxSize().then(consumed)) {
    key(dataVersion) {
    // Shared-axis style: forward slides in from the right, back reverses it; none with "Remove animations".
    AnimatedContent(
        targetState = screen,
        contentKey = { it.tag },
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
            onFrames = { frames, photo, codes, boxes ->
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
                    else batch.add(frames, photo, labelCodes, boxes)
                } else {
                    // Barcodes are read exactly: where one disagrees with OCR, the barcode wins.
                    val labelCodes = if (prefs.readBarcodes) codes.filterNot { LabelCodes.isOurs(it) } else emptyList()
                    val part = LabelBarcode.apply(ScanMerger.merge(frames.map { LabelParser.parse(it) }), labelCodes)
                    if (part.rawText.isBlank()) toast("No text found. Move closer and avoid glare.")
                    else screen = Screen.Review(part, photo, boxes = boxes)
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
                    photoBoxes = s.boxes,
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
                onPartPage = { partPageOpen = it },
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
                onScan = { screen = Screen.Scan },
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
    if (showBar && !rail) {
        NavigationBar {
            Tab.entries.forEach { t ->
                NavigationBarItem(
                    selected = t == tab,
                    modifier = Modifier.testTag("tab-${t.name}"),
                    onClick = { goTo(t) },
                    icon = { Icon(t.icon, contentDescription = null) },
                    label = { Text(t.label) },
                )
            }
        }
    }
    }
    }
}

private fun Screen.depth(): Int = when (this) {
    Screen.Scan -> 0
    is Screen.Review, Screen.Settings, is Screen.Inventory -> 1
    Screen.Locations, Screen.Projects -> 2
}

private fun AnimatedContentTransitionScope<Screen>.screenTransition(reduceMotion: Boolean): ContentTransform {
    if (reduceMotion) return EnterTransition.None togetherWith ExitTransition.None
    // Switching between bottom-bar tabs fades through; going deeper slides.
    if (initialState.tab() != null && targetState.tab() != null) return fadeIn(tween(220)) togetherWith fadeOut(tween(120))
    val dir = if (targetState.depth() >= initialState.depth()) 1 else -1
    return (slideInHorizontally(tween(300)) { w -> dir * w / 5 } + fadeIn(tween(300))) togetherWith
        (slideOutHorizontally(tween(300)) { w -> -dir * w / 5 } + fadeOut(tween(150)))
}

@Composable
private fun PartStockTheme(content: @Composable () -> Unit) {
    val mode = AppTheme.mode
    val dark = mode.isDark(isSystemInDarkTheme())
    val context = LocalContext.current
    val base = when {
        AppTheme.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> BrandColors.dark
        else -> BrandColors.light
    }
    val scheme = if (mode.isBlack()) BrandColors.black(base) else base
    // Status- and navigation-bar icons follow the app's theme, not the phone's dark mode (they differ when the theme
    // is forced in Settings → Appearance). Re-applied whenever it flips.
    val activity = context as? ComponentActivity
    LaunchedEffect(activity, dark) {
        activity?.enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
            else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(NAV_SCRIM_DARK)
            else SystemBarStyle.light(NAV_SCRIM_LIGHT, NAV_SCRIM_DARK),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** The scrims enableEdgeToEdge() uses behind 3-button navigation by default. */
private val NAV_SCRIM_LIGHT = AndroidColor.argb(0xE6, 0xFF, 0xFF, 0xFF)
private val NAV_SCRIM_DARK = AndroidColor.argb(0x80, 0x1B, 0x1B, 0x1B)
