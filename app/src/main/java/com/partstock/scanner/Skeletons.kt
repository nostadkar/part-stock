package com.partstock.scanner

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Placeholder fill for loading content: a soft light band sweeping across, or plain grey when animations are off
 * ("Remove animations"), so nothing moves for people who asked for that.
 */
@Composable
fun skeletonBrush(animate: Boolean): Brush {
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    if (!animate) return SolidColor(base)
    val shine = MaterialTheme.colorScheme.surfaceContainerLow
    val t by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "skeleton-x",
    )
    val w = 600f
    return Brush.linearGradient(
        colors = listOf(base, shine, base),
        start = Offset(t * w, 0f),
        end = Offset(t * w + w, w / 3),
    )
}

/** A grey rounded block in place of text or an image. */
@Composable
private fun Bone(brush: Brush, modifier: Modifier, radius: Int = 6) {
    Box(modifier.clip(RoundedCornerShape(radius.dp)).background(brush))
}

/** Four stat-tile placeholders, the same row as the real Parts / Pieces / Value / Low stock tiles. */
@Composable
fun SkeletonStatTiles(animate: Boolean, modifier: Modifier = Modifier) {
    val brush = skeletonBrush(animate)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(4) { Bone(brush, Modifier.weight(1f).height(64.dp), radius = 12) }
    }
}

/** One part-row placeholder, about as tall as a real row (image tile, two lines of text, the count). */
@Composable
fun SkeletonPartRow(animate: Boolean, modifier: Modifier = Modifier) {
    val brush = skeletonBrush(animate)
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Bone(brush, Modifier.size(48.dp), radius = 12)
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Bone(brush, Modifier.fillMaxWidth(0.6f).height(16.dp))
                Bone(brush, Modifier.fillMaxWidth(0.85f).height(12.dp))
                Bone(brush, Modifier.fillMaxWidth(0.4f).height(20.dp), radius = 10)
            }
            Bone(brush, Modifier.padding(start = 12.dp).size(width = 40.dp, height = 28.dp))
        }
    }
}

/** What the list shows while the inventory loads: six row placeholders, read out once as "Loading parts". */
@Composable
fun SkeletonPartRows(animate: Boolean, modifier: Modifier = Modifier, count: Int = 6) {
    Column(
        modifier.semantics { contentDescription = "Loading parts" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(count) { SkeletonPartRow(animate) }
    }
}
