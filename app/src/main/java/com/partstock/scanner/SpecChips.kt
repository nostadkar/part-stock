package com.partstock.scanner

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.partstock.scanner.parse.PartSpecs

/**
 * Chips for a part's value, tolerance, voltage and package (e.g. 10kΩ · 1% · 0603), 12sp. With [onTap] each chip
 * is a search filter (tap 0603 → search "0603") with a 36dp-tall touch area; long-press copies the value. [pkg]
 * gets a small package drawing in front of its chip. [extra] chips (e.g. the bin) are not filters.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun SpecChips(
    chips: List<String>,
    modifier: Modifier = Modifier,
    pkg: String = "",
    extra: List<String> = emptyList(),
    onTap: ((String) -> Unit)? = null,
    /** Tap on a chip that isn't a filter (e.g. opens the part, like the rest of the row). */
    onOtherClick: () -> Unit = {},
) {
    if (chips.isEmpty() && extra.isEmpty()) return
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        (chips.map { it to true } + extra.map { it to false }).forEach { (c, filter) ->
            val tappable = filter && onTap != null
            val touch = Modifier
                .then(if (tappable) Modifier.heightIn(min = 36.dp) else Modifier)
                .combinedClickable(
                    enabled = true,
                    role = if (tappable) Role.Button else null,
                    onClickLabel = if (tappable) "Search $c" else null,
                    onLongClickLabel = "Copy",
                    onClick = { if (tappable) onTap?.invoke(c) else onOtherClick() },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        clipboard.setText(AnnotatedString(c))
                    },
                )
            Box(touch, contentAlignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (filter && c == pkg) {
                            PackageIcon(pkg, MaterialTheme.colorScheme.onSecondaryContainer, Modifier.padding(end = 4.dp), 14.dp)
                        }
                        Text(c, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * The part page's spec "hero": the value large (10 kΩ), then tolerance · package · voltage underneath, each a
 * tappable search filter like the row chips.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpecHero(specs: PartSpecs, modifier: Modifier = Modifier, onTap: ((String) -> Unit)? = null) {
    if (specs.isEmpty()) return
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    androidx.compose.foundation.layout.Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (specs.value.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.combinedClickable(
                    role = Role.Button,
                    onClickLabel = "Search ${specs.value}",
                    onLongClickLabel = "Copy",
                    onClick = { onTap?.invoke(specs.value) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        clipboard.setText(AnnotatedString(specs.value))
                    },
                ),
            ) {
                Text(
                    specs.value,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        val rest = listOf(specs.tolerance, specs.pkg, specs.voltage).filter { it.isNotEmpty() }
        if (rest.isNotEmpty()) SpecChips(rest, pkg = specs.pkg, onTap = onTap)
    }
}
