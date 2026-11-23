package com.partstock.scanner

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs

/** How far a row has to be dragged before letting go uses or adds one. */
private val SWIPE_THRESHOLD = 56.dp

/**
 * A part row that swipes right to use 1 and left to add 1. The colored background grows with the drag and its icon
 * fades and scales in as the threshold nears; passing the threshold gives a haptic tick, letting go a lighter one,
 * and the row shows the result ("Use 1 → 4 left") for a moment. One action per swipe; it always springs back.
 * [peek] nudges the row sideways once to show that it swipes (skipped with animations off).
 */
@Composable
internal fun SwipeRow(
    part: PartRow,
    onUse: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    peek: Boolean = false,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val thresholdPx = with(density) { SWIPE_THRESHOLD.toPx() }
    // The swipe state (and so its callback) is remembered across recompositions: read the newest part and actions.
    val currentPart by rememberUpdatedState(part)
    val use by rememberUpdatedState(onUse)
    val add by rememberUpdatedState(onAdd)
    var result by remember { mutableStateOf<String?>(null) }
    // Kept after [result] clears, so the pill keeps its text while it fades out.
    var shown by remember { mutableStateOf("") }
    val last = remember { longArrayOf(0L) }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            val now = System.currentTimeMillis()
            if (v != SwipeToDismissBoxValue.Settled && now - last[0] > 600) {
                last[0] = now
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                val q = currentPart.quantity
                if (v == SwipeToDismissBoxValue.StartToEnd) {
                    if (q > 0) result = "Use 1 → ${q - 1} left"
                    use()
                } else {
                    result = "Add 1 → ${q + 1} in stock"
                    add()
                }
            }
            false // always spring back; the list updates with the new count
        },
        positionalThreshold = { thresholdPx },
    )
    // A tick as the drag passes the threshold (the target flips from Settled to an action).
    LaunchedEffect(state.targetValue) {
        if (state.targetValue != SwipeToDismissBoxValue.Settled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    LaunchedEffect(result) {
        result?.let { shown = it }
        if (result != null) {
            delay(1600)
            result = null
        }
    }
    val reduceMotion = rememberReduceMotion()
    val peekOffset = remember { Animatable(0f) }
    LaunchedEffect(peek, reduceMotion) {
        if (peek && !reduceMotion) {
            delay(700)
            peekOffset.animateTo(with(density) { 48.dp.toPx() }, tween(350))
            peekOffset.animateTo(0f, spring(dampingRatio = 0.5f))
        }
    }

    Box(modifier) {
        SwipeToDismissBox(
            state = state,
            // TalkBack users can't swipe a row: offer the same actions in its actions menu.
            modifier = Modifier.semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Use 1") { use(); true },
                    CustomAccessibilityAction("Add 1") { add(); true },
                )
            },
            backgroundContent = {
                val offset = runCatching { state.requireOffset() }.getOrDefault(0f).let { if (it == 0f) peekOffset.value else it }
                val useDir = offset > 0f
                val fraction = (abs(offset) / thresholdPx).coerceIn(0f, 1f)
                val color = when {
                    offset == 0f -> Color.Transparent
                    useDir -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.primaryContainer
                }
                val onColor = if (useDir) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
                    contentAlignment = if (useDir) Alignment.CenterStart else Alignment.CenterEnd,
                ) {
                    if (offset != 0f) {
                        // The colored area follows the finger (plus the card's rounded corner).
                        Box(
                            Modifier.fillMaxHeight()
                                .width(with(density) { (abs(offset) + 16.dp.toPx()).toDp() })
                                .background(color),
                        )
                        Row(
                            Modifier.padding(horizontal = 20.dp).graphicsLayer {
                                alpha = 0.3f + 0.7f * fraction
                                val sc = 0.7f + 0.3f * fraction
                                scaleX = sc
                                scaleY = sc
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (useDir) Icons.Filled.Remove else Icons.Filled.Add, contentDescription = null, tint = onColor)
                            Text(
                                if (useDir) "Use 1" else "Add 1",
                                style = MaterialTheme.typography.labelLarge,
                                color = onColor,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            },
        ) {
            Box(Modifier.graphicsLayer { translationX = peekOffset.value }) { content() }
        }
        AnimatedVisibility(
            visible = result != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 2.dp,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(
                    shown,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Long-press on a row's count: use or add several at once without opening the part page. */
@Composable
internal fun BulkStockDialog(part: PartRow, onUse: (Int) -> Unit, onAdd: (Int) -> Unit, onDismiss: () -> Unit) {
    var n by remember { mutableIntStateOf(1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(part.key(), fontFamily = FontFamily.Monospace) },
        text = {
            androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${part.quantity} in stock", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    FilledTonalIconButton(onClick = { n = (n - 1).coerceAtLeast(1) }, enabled = n > 1) {
                        Icon(Icons.Filled.Remove, contentDescription = "Fewer, now $n")
                    }
                    Text(
                        n.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    FilledTonalIconButton(onClick = { n += 1 }) {
                        Icon(Icons.Filled.Add, contentDescription = "More, now $n")
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                val usable = n.coerceAtMost(part.quantity)
                TextButton(onClick = { onUse(usable) }, enabled = usable > 0) { Text("Use ${usable.coerceAtLeast(1)}") }
                TextButton(onClick = { onAdd(n) }) { Text("Add $n") }
            }
        },
    )
}
