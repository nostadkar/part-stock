package com.partstock.scanner

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** What the framing guide shows: idle white, or a short green/amber flash after a capture. */
enum class GuideFlash { NONE, READ_OK, NOTHING_READ }

val GuideGreen = Color(0xFF4CAF50)
val GuideAmber = Color(0xFFFFB300)

/** How long the brackets flash after a capture before the app moves on. */
const val GUIDE_FLASH_MS = 400L

/**
 * The framing guide: corner brackets (like the app icon) with a thin dark outline so they show on bright labels,
 * and a light scrim over everything outside the box. The scrim draws past this box's bounds, so the parent
 * should clip (Modifier.clipToBounds) to keep it off the top bar and the controls.
 */
@Composable
fun ScanGuide(modifier: Modifier, flash: GuideFlash = GuideFlash.NONE, cornerRadius: Dp = 12.dp, scrim: Boolean = true) {
    val reduceMotion = rememberReduceMotion()
    val target = when (flash) {
        GuideFlash.NONE -> Color.White
        GuideFlash.READ_OK -> GuideGreen
        GuideFlash.NOTHING_READ -> GuideAmber
    }
    val color by animateColorAsState(target, if (reduceMotion) snap() else tween(120), label = "guide")
    Box(
        modifier.drawBehind {
            val r = cornerRadius.toPx()
            if (scrim) {
                val hole = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(-10_000f, -10_000f, size.width + 10_000f, size.height + 10_000f))
                    addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(r)))
                }
                drawPath(hole, Color.Black.copy(alpha = 0.35f))
            }
            val w = 4.dp.toPx()
            brackets(Color.Black.copy(alpha = 0.45f), w + 2.dp.toPx(), r) // dark outline under the brackets
            brackets(color, w, r)
        },
    )
}

/** Four L-shaped corners with rounded elbows, inset by half the stroke so they sit on the box edge. */
private fun DrawScope.brackets(color: Color, stroke: Float, radius: Float) {
    val arm = minOf(size.width, size.height) * 0.18f
    val i = stroke / 2
    val l = i
    val t = i
    val rgt = size.width - i
    val b = size.height - i
    val rr = radius.coerceAtMost(arm)
    val path = Path().apply {
        // top-left
        moveTo(l, t + arm); lineTo(l, t + rr); quadraticTo(l, t, l + rr, t); lineTo(l + arm, t)
        // top-right
        moveTo(rgt - arm, t); lineTo(rgt - rr, t); quadraticTo(rgt, t, rgt, t + rr); lineTo(rgt, t + arm)
        // bottom-right
        moveTo(rgt, b - arm); lineTo(rgt, b - rr); quadraticTo(rgt, b, rgt - rr, b); lineTo(rgt - arm, b)
        // bottom-left
        moveTo(l + arm, b); lineTo(l + rr, b); quadraticTo(l, b, l, b - rr); lineTo(l, b - arm)
    }
    drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
}

/**
 * A ring of [segments] arcs around the shutter; [filled] of them are lit (one per burst frame).
 * Draws nothing when [filled] is 0. Place it in a Box behind / around the shutter button.
 */
@Composable
fun BurstRing(filled: Int, segments: Int, ringSize: Dp, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val progress by animateFloatAsState(
        filled.toFloat(),
        if (reduceMotion || filled == 0) snap() else tween(250),
        label = "burst",
    )
    if (filled <= 0 && progress <= 0f) return
    val active = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(ringSize)) {
        val stroke = 5.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val gap = 14f
        val sweep = 360f / segments - gap
        for (s in 0 until segments) {
            val start = -90f + s * (360f / segments) + gap / 2
            // Track: dark under light, so the ring shows on any background.
            drawArc(Color.Black.copy(alpha = 0.45f), start, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke + 2.dp.toPx(), cap = StrokeCap.Round))
            drawArc(Color.White.copy(alpha = 0.35f), start, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val part = (progress - s).coerceIn(0f, 1f)
            if (part > 0f) {
                drawArc(active, start, sweep * part, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
    }
}

/** Text on a ~70% black rounded pill, readable over a bright label or a white bench. */
@Composable
fun HintPill(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    announce: Boolean = false,
) {
    Surface(
        modifier = if (announce) modifier.semantics { liveRegion = LiveRegionMode.Polite } else modifier,
        shape = RoundedCornerShape(50),
        color = Color.Black.copy(alpha = 0.7f),
        contentColor = Color.White,
    ) {
        Text(text, style = style, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
    }
}

/** Box helper so callers can stack the ring and the shutter at the same center. */
@Composable
fun RingedShutter(filled: Int, segments: Int, ringSize: Dp, content: @Composable () -> Unit) {
    Box(Modifier.size(ringSize), contentAlignment = Alignment.Center) {
        BurstRing(filled, segments, ringSize, Modifier.fillMaxSize())
        content()
    }
}
