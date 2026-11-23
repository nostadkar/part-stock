package com.partstock.scanner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.partstock.scanner.parse.GettingStarted

/**
 * The dismissible "Getting started" checklist (top of Inventory, under the hint on Scan). Items tick themselves off
 * from the existing checks; each opens its Settings card ([onSettings] with the card id) or, for the first scan,
 * [onScan]. Shown only to new installs (decided once, after the inventory has loaded) and never after dismissal.
 * [compact]: starts folded to one line (Scan screen, so the viewfinder stays clear).
 */
@Composable
fun GettingStartedCard(
    prefs: Prefs,
    secrets: SecretStore,
    hasParts: Boolean,
    loaded: Boolean,
    onSettings: (String) -> Unit,
    modifier: Modifier = Modifier,
    onScan: (() -> Unit)? = null,
    compact: Boolean = false,
) {
    var mode by remember { mutableStateOf(prefs.gettingStarted) }
    var skipped by remember { mutableStateOf(prefs.gettingStartedSkipped) }
    var open by rememberSaveable { mutableStateOf(!compact) }
    val hasMouser = remember { secrets.hasMouserKey() }
    val hasDigiKey = remember { secrets.hasDigiKey() }
    val hasBackup = remember { prefs.autoBackupUri.isNotBlank() }
    LaunchedEffect(loaded) {
        if (loaded && mode.isBlank()) {
            mode = GettingStarted.decide(mode, hasParts, hasMouser, hasBackup)
            prefs.gettingStarted = mode
        }
    }
    val state = GettingStarted.State(hasParts, hasMouser, hasBackup, hasDigiKey, skipped)
    if (!loaded || !GettingStarted.visible(mode, state)) return

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = if (open) 8.dp else 4.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClickLabel = if (open) "Collapse" else "Expand") { open = !open }
                    .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = GettingStarted.describe(state)
                }) {
                    CircularProgressIndicator(
                        progress = { GettingStarted.fraction(state) },
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Text("${GettingStarted.settledCount(state)}/${GettingStarted.Item.values().size}", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Getting started", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        "${GettingStarted.progress(state)} done",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                IconButton(onClick = {
                    mode = GettingStarted.OFF
                    prefs.gettingStarted = GettingStarted.OFF
                }) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss Getting started (reopen in Settings)")
                }
            }
            if (!open) return@Column
            for (item in GettingStarted.Item.values()) {
                val done = GettingStarted.done(item, state)
                val skip = GettingStarted.skipped(item, state)
                val action: (() -> Unit)? = item.settingsCard?.let { id -> { onSettings(id) } } ?: onScan
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .let { if (action != null && !done) it.clickable(role = Role.Button, onClick = action) else it }
                        .semantics(mergeDescendants = true) {
                            stateDescription = when {
                                done -> "Done"
                                skip -> "Skipped"
                                else -> "To do"
                            }
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        when {
                            done -> Icons.Filled.CheckCircle
                            skip -> Icons.Filled.SkipNext
                            else -> Icons.Filled.RadioButtonUnchecked
                        },
                        contentDescription = null,
                        tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.title, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (done || skip) null else FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        if (!done) {
                            Text(
                                if (skip) "Skipped" else item.detail, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                            )
                        }
                    }
                    if (!done && !skip && item.skippable()) {
                        TextButton(onClick = {
                            skipped = GettingStarted.skip(skipped, item)
                            prefs.gettingStartedSkipped = skipped
                        }) { Text("Skip") }
                    }
                }
            }
        }
    }
}

/** Settings → Getting started: shows the checklist again (skipped items come back). */
fun reopenGettingStarted(prefs: Prefs) {
    prefs.gettingStartedSkipped = emptySet()
    prefs.gettingStarted = GettingStarted.ACTIVE
}
