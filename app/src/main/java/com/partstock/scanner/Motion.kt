package com.partstock.scanner

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Waves
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.partstock.scanner.parse.Categories

/** True when the system "Remove animations" setting is on (animator duration scale 0): skip transitions. */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (e: Exception) {
            false
        }
    }
}

/** The scopes a shared element needs (list row → part details); null when animations are off. */
@OptIn(ExperimentalSharedTransitionApi::class)
class SharedScopes(val shared: SharedTransitionScope, val visibility: AnimatedVisibilityScope)

/** Marks this element as shared under [key] so it flies between the list and the details page. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedPart(scopes: SharedScopes?, key: String): Modifier {
    if (scopes == null) return this
    val base = this
    return with(scopes.shared) {
        base.sharedElement(rememberSharedContentState(key), animatedVisibilityScope = scopes.visibility)
    }
}

/** An icon per category bucket, for filter chips, rows without a photo and the details page. */
fun categoryIcon(category: String): ImageVector = when (category) {
    Categories.POWER -> Icons.Filled.Bolt
    Categories.MCU -> Icons.Filled.Memory
    Categories.MEMORY -> Icons.Filled.Storage
    Categories.INTERFACE -> Icons.Filled.SettingsEthernet
    Categories.AMPLIFIERS -> Icons.Filled.GraphicEq
    Categories.LOGIC -> Icons.Filled.Functions
    Categories.SENSORS -> Icons.Filled.Thermostat
    Categories.OTHER_ICS -> Icons.Filled.DeveloperBoard
    Categories.TRANSISTORS -> Icons.Filled.Tune
    Categories.PROTECTION -> Icons.Filled.Shield
    Categories.DIODES -> Icons.Filled.PlayArrow
    Categories.LEDS -> Icons.Filled.Lightbulb
    Categories.RESISTORS -> Icons.Filled.LinearScale
    Categories.CAPACITORS -> Icons.Filled.Pause
    Categories.INDUCTORS -> Icons.Filled.Loop
    Categories.CRYSTALS -> Icons.Filled.Waves
    Categories.CONNECTORS -> Icons.Filled.Cable
    Categories.SWITCHES -> Icons.Filled.ToggleOn
    else -> Icons.Filled.Category
}
