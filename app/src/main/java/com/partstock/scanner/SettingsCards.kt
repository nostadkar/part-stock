package com.partstock.scanner

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.partstock.scanner.parse.SettingsStatus
import com.partstock.scanner.parse.SettingsStatus.Level
import kotlinx.coroutines.delay

/** Icon and color for a status level; the icon shape differs per level so it never relies on color alone. */
@Composable
private fun levelLook(level: Level): Pair<ImageVector, Color> {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (level) {
        Level.OK -> Icons.Filled.CheckCircle to (if (dark) Color(0xFF81C784) else Color(0xFF2E7D32))
        Level.WORKING -> Icons.Filled.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
        Level.WARN -> Icons.Filled.Warning to (if (dark) Color(0xFFFFCC80) else Color(0xFF8A5A00))
        Level.ERROR -> Icons.Filled.Error to MaterialTheme.colorScheme.error
        Level.OFF -> Icons.Filled.RemoveCircleOutline to MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** A small rounded chip: status icon + text. */
@Composable
fun StatusChip(status: SettingsStatus.Status, modifier: Modifier = Modifier) {
    val (icon, color) = levelLook(status.level)
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.12f),
        contentColor = color,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(status.text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** A status line in a card (e.g. "Key saved", "Couldn't check: …"): icon + text in the level's color. */
@Composable
fun StatusLine(status: SettingsStatus.Status) {
    val (icon, color) = levelLook(status.level)
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Text(
            status.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (status.level == Level.ERROR || status.level == Level.WARN) color else MaterialTheme.colorScheme.onSurface,
        )
    }
}

fun statusOk(text: String) = SettingsStatus.Status(Level.OK, text)
fun statusFailed(text: String) = SettingsStatus.Status(Level.ERROR, text)
fun statusNeutral(text: String) = SettingsStatus.Status(Level.OFF, text)

/**
 * A Settings section as a card: icon, title, status chip and, when [collapsible], an expand arrow. The open/closed
 * state is remembered in [prefs] under [id]; a card whose [status] is a problem opens by itself until the user
 * closes it.
 */
/**
 * The Settings card to bring into view and highlight (opened from Getting started); [onPlaced] gets its y position
 * in the scrolling column.
 */
class SettingsFocus(val id: String, val onPlaced: (Float) -> Unit)

val LocalSettingsFocus = compositionLocalOf<SettingsFocus?> { null }

@Composable
fun SettingsCard(
    id: String,
    icon: ImageVector,
    title: String,
    prefs: Prefs,
    status: SettingsStatus.Status? = null,
    collapsible: Boolean = true,
    openByDefault: Boolean = false,
    content: @Composable () -> Unit,
) {
    var open by remember(id) {
        mutableStateOf(!collapsible || (prefs.settingsCardOpen(id) ?: openByDefault) || status?.isProblem() == true)
    }
    // A new problem (e.g. a backup that just failed) opens the card even if it was closed.
    val problem = status?.isProblem() == true
    LaunchedEffect(problem) { if (problem) open = true }
    val reduceMotion = rememberReduceMotion()
    val focus = LocalSettingsFocus.current?.takeIf { it.id == id }
    // A focused card opens and gets an outline that fades out after a moment.
    val glow = remember(id) { Animatable(if (focus != null) 1f else 0f) }
    LaunchedEffect(focus != null) {
        if (focus != null) {
            open = true
            glow.snapTo(1f)
            delay(2500)
            if (reduceMotion) glow.snapTo(0f) else glow.animateTo(0f, tween(800))
        }
    }
    val body: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }

    Card(
        modifier = Modifier.fillMaxWidth()
            .let { m -> if (focus != null) m.onGloballyPositioned { focus.onPlaced(it.positionInParent().y) } else m },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = if (glow.value > 0f) BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = glow.value)) else null,
    ) {
        Column {
            val header = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .let {
                    if (collapsible) it.clickable(role = Role.Button, onClickLabel = if (open) "Collapse" else "Expand") {
                        open = !open
                        prefs.setSettingsCardOpen(id, open)
                    }.semantics { stateDescription = if (open) "Expanded" else "Collapsed" } else it
                }
                .padding(horizontal = 16.dp, vertical = 10.dp)
            Row(header, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                status?.let { StatusChip(it) }
                if (collapsible) {
                    Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                }
            }
            if (reduceMotion) {
                if (open) body()
            } else {
                AnimatedVisibility(visible = open) { body() }
            }
        }
    }
}
