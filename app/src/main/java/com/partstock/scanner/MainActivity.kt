package com.partstock.scanner

import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
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
import com.partstock.scanner.parse.LabelParser
import com.partstock.scanner.parse.ParsedPart
import com.partstock.scanner.parse.ScanMerger

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = Prefs(this)
        val db = InventoryDb(applicationContext)
        val secrets = SecretStore(applicationContext)
        db.onChanged = { AutoBackup.schedule(applicationContext, prefs, db) }
        setContent {
            PartStockTheme { App(prefs, db, secrets) }
        }
    }
}

private sealed interface Screen {
    data object Scan : Screen
    /** [photo] is one of the photos that was read, shown on the review screen to compare against. */
    data class Review(val part: ParsedPart, val photo: Bitmap? = null) : Screen
    data object Inventory : Screen
    data object Settings : Screen
}

@Composable
private fun App(prefs: Prefs, db: InventoryDb, secrets: SecretStore) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Scan) }
    var partCount by remember { mutableIntStateOf(db.all().size) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

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

    when (val s = screen) {
        Screen.Scan -> ScanScreen(
            partCount = partCount,
            onInventory = { screen = Screen.Inventory },
            onSettings = { screen = Screen.Settings },
            onFrames = { frames, photo ->
                val part = ScanMerger.merge(frames.map { LabelParser.parse(it) })
                if (part.rawText.isBlank()) toast("No text found. Move closer and avoid glare.")
                else screen = Screen.Review(part, photo)
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
                    toast(msg)
                    partCount = db.all().size
                    screen = Screen.Scan
                },
                onCancel = { screen = Screen.Scan },
            )
        }

        Screen.Settings -> {
            BackHandler {
                partCount = db.all().size
                screen = Screen.Scan
            }
            SettingsScreen(secrets = secrets, db = db, prefs = prefs, onDone = {
                partCount = db.all().size
                screen = Screen.Scan
            })
        }

        Screen.Inventory -> {
            BackHandler {
                partCount = db.all().size
                screen = Screen.Scan
            }
            InventoryScreen(db = db, secrets = secrets, onBack = {
                partCount = db.all().size
                screen = Screen.Scan
            })
        }
    }
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
