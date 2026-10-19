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
    data class Review(val part: ParsedPart, val photo: Bitmap? = null) : Screen
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
                    toast("Bin set to ${Locations.normalize(own.bin)} for the next saves")
                } else {
                    // Barcodes are read exactly: where one disagrees with OCR, the barcode wins.
                    val labelCodes = if (prefs.readBarcodes) codes.filterNot { LabelCodes.isOurs(it) } else emptyList()
                    val part = LabelBarcode.apply(ScanMerger.merge(frames.map { LabelParser.parse(it) }), labelCodes)
                    if (part.rawText.isBlank()) toast("No text found. Move closer and avoid glare.")
                    else screen = Screen.Review(part, photo)
                }
            },
            onError = ::toast,
        )

        is Screen.Review -> {
            BackHandler { screen = Screen.Scan }
            ReviewScreen(
                part = s.part,
                photo = s.photo,
                db = db,
                prefs = prefs,
                secrets = secrets,
                onSaved = { msg ->
                    UndoOffer.offer(context, msg, db.lastChange)
                    screen = Screen.Scan
                },
                onCancel = { screen = Screen.Scan },
            )
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
