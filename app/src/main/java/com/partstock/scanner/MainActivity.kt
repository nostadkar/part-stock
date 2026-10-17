package com.partstock.scanner

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.partstock.scanner.parse.LabelParser
import com.partstock.scanner.parse.ParsedPart

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = Prefs(this)
        val db = InventoryDb(applicationContext)
        setContent {
            PartStockTheme { App(prefs, db) }
        }
    }
}

private sealed interface Screen {
    data object Scan : Screen
    data class Review(val part: ParsedPart) : Screen
    data object Inventory : Screen
}

@Composable
private fun App(prefs: Prefs, db: InventoryDb) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Scan) }
    var partCount by remember { mutableIntStateOf(db.all().size) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    when (val s = screen) {
        Screen.Scan -> ScanScreen(
            partCount = partCount,
            onInventory = { screen = Screen.Inventory },
            onLines = { lines ->
                val part = LabelParser.parse(lines)
                if (part.rawText.isBlank()) toast("No text found. Move closer and avoid glare.")
                else screen = Screen.Review(part)
            },
            onError = ::toast,
        )

        is Screen.Review -> {
            BackHandler { screen = Screen.Scan }
            ReviewScreen(
                part = s.part,
                db = db,
                prefs = prefs,
                onSaved = { msg ->
                    toast(msg)
                    partCount = db.all().size
                    screen = Screen.Scan
                },
                onCancel = { screen = Screen.Scan },
            )
        }

        Screen.Inventory -> {
            BackHandler {
                partCount = db.all().size
                screen = Screen.Scan
            }
            InventoryScreen(db = db, onBack = {
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
