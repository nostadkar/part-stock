package com.partstock.scanner

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.partstock.scanner.parse.ChartData
import com.partstock.scanner.parse.StockLevel

/*
 * Small chart building blocks drawn on a Compose Canvas (no chart library): a sparkline, a donut and a bar meter.
 * Data prep (scaling, top-N + "Other", tap hit-testing, spoken summaries) lives in parse/ChartData.java.
 * Every chart takes a contentDescription for TalkBack and skips its draw-in when animations are off.
 */

/** Colors for charts that work in light and dark, with wallpaper colors or the brand theme. */
object ChartColors {
    /**
     * Okabe–Ito, the usual color-blind-safe set, in a deeper variant for light surfaces and a lighter one for dark.
     * Charts still never rely on color alone: donut slices come with a labeled legend and percentages.
     */
    private val light = listOf(
        Color(0xFF0072B2), Color(0xFFE69F00), Color(0xFF009E73), Color(0xFFCC79A7),
        Color(0xFF56B4E9), Color(0xFFD55E00), Color(0xFF8C7A00),
    )
    private val dark = listOf(
        Color(0xFF56B4E9), Color(0xFFF0B640), Color(0xFF3DD6A3), Color(0xFFE5A3C8),
        Color(0xFF9AD3F5), Color(0xFFFF8A4C), Color(0xFFE8D85A),
    )

    /** True when the current theme's surface is dark. */
    @Composable
    @ReadOnlyComposable
    fun isDark(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    /**
     * The categorical palette: the theme's primary first (so a single series matches the app, including wallpaper
     * colors), then the fixed color-blind-safe set. "Other" uses [other].
     */
    @Composable
    @ReadOnlyComposable
    fun categorical(): List<Color> = listOf(MaterialTheme.colorScheme.primary) + if (isDark()) dark else light

    /** Only the fixed color-blind-safe set (no theme primary), for light or dark surfaces. */
    @Composable
    @ReadOnlyComposable
    fun fixed(): List<Color> = if (isDark()) dark else light

    /** Neutral color for the "Other" slice and tracks. */
    @Composable
    @ReadOnlyComposable
    fun other(): Color = MaterialTheme.colorScheme.outline

    /** A gain (+10): green that reads on both light and dark surfaces. */
    @Composable
    @ReadOnlyComposable
    fun positive(): Color = if (isDark()) Color(0xFF81C995) else Color(0xFF1E7B34)

    /** Nearly low: amber that reads on both light and dark surfaces. */
    @Composable
    @ReadOnlyComposable
    fun warning(): Color = if (isDark()) Color(0xFFF0B640) else Color(0xFFB26A00)

    /** A loss (−2). */
    @Composable
    @ReadOnlyComposable
    fun negative(): Color = MaterialTheme.colorScheme.error
}

/** 0 → 1 draw-in progress, or 1 at once when [animate] is off. Restarts when [key] changes. */
@Composable
private fun drawIn(animate: Boolean, key: Any?): Float {
    val progress = remember(key) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(key, animate) {
        if (animate) progress.animateTo(1f, tween(600)) else progress.snapTo(1f)
    }
    return progress.value
}

/**
 * A line of [values] (oldest first), filling the width. [minLine] draws a dashed line (e.g. the part's minimum),
 * and the last point gets a dot. Fewer than 2 values draws nothing; show a message instead.
 */
@Composable
fun Sparkline(
    values: List<Double>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
    minLine: Double? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    minLineColor: Color = MaterialTheme.colorScheme.error,
    animate: Boolean = !rememberReduceMotion(),
) {
    val data = remember(values) { values.toDoubleArray() }
    val range = remember(data, minLine) { ChartData.range(data, minLine) }
    val progress = drawIn(animate, values)
    val fill = color.copy(alpha = 0.12f)
    Canvas(
        modifier.fillMaxWidth().height(height).semantics { this.contentDescription = contentDescription },
    ) {
        if (data.size < 2) return@Canvas
        val pad = 4.dp.toPx()
        val pts = ChartData.scale(data, range[0], range[1], size.width, size.height, pad)
        val line = Path().apply {
            moveTo(pts[0], pts[1])
            var i = 2
            while (i < pts.size) {
                lineTo(pts[i], pts[i + 1])
                i += 2
            }
        }
        val area = Path().apply {
            addPath(line)
            lineTo(pts[pts.size - 2], size.height - pad)
            lineTo(pts[0], size.height - pad)
            close()
        }
        clipRect(right = size.width * progress) {
            drawPath(area, fill)
            drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (minLine != null) {
            val y = ChartData.yOf(minLine, range[0], range[1], size.height, pad)
            drawLine(
                minLineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
            )
        }
        if (progress >= 1f) {
            val end = Offset(pts[pts.size - 2], pts[pts.size - 1])
            drawCircle(color, radius = 4.dp.toPx(), center = end)
            drawCircle(Color.White.copy(alpha = 0.9f), radius = 1.6.dp.toPx(), center = end)
        }
    }
}

/** One donut segment; [color] comes from [ChartColors]. */
data class DonutSlice(val label: String, val value: Double, val color: Color)

/**
 * A donut of [slices] drawn clockwise from 12 o'clock, with small gaps between them. Tapping a segment calls
 * [onSelect] with its index; the [selected] one is drawn wider, the others faded. Keep a labeled legend next to it.
 */
@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    selected: Int? = null,
    onSelect: ((Int) -> Unit)? = null,
    thickness: Dp = 22.dp,
    animate: Boolean = !rememberReduceMotion(),
) {
    val total = slices.sumOf { it.value.coerceAtLeast(0.0) }
    val fractions = remember(slices) {
        DoubleArray(slices.size) { i -> if (total > 0) slices[i].value.coerceAtLeast(0.0) / total else 0.0 }
    }
    val progress = drawIn(animate, slices)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val currentSelect by rememberUpdatedState(onSelect)
    val tap = if (onSelect == null) Modifier else Modifier.pointerInput(fractions) {
        detectTapGestures { p ->
            val outer = size.width.coerceAtMost(size.height) / 2f
            val inner = outer - thickness.toPx() - 6.dp.toPx()
            val i = ChartData.sliceAt(fractions, (p.x - size.width / 2f).toDouble(), (p.y - size.height / 2f).toDouble(), inner.toDouble(), outer.toDouble())
            if (i >= 0) currentSelect?.invoke(i)
        }
    }
    Canvas(modifier.then(tap).semantics { this.contentDescription = contentDescription }) {
        val stroke = thickness.toPx()
        val wide = stroke + 6.dp.toPx()
        val d = size.minDimension - wide
        val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
        val arcSize = Size(d, d)
        if (total <= 0) {
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            return@Canvas
        }
        // A gap of about 2dp between segments, unless a segment is too thin for one.
        val gapDeg = if (slices.size > 1) Math.toDegrees((2.dp.toPx() / (d / 2f)).toDouble()).toFloat() else 0f
        var start = -90f
        val sweepAll = 360f * progress
        slices.forEachIndexed { i, s ->
            val full = (fractions[i] * 360.0).toFloat()
            val visible = (sweepAll - (start + 90f)).coerceIn(0f, full)
            val gap = if (full > gapDeg * 2) gapDeg else 0f
            if (visible > gap) {
                val dim = selected != null && selected != i
                drawArc(
                    color = if (dim) s.color.copy(alpha = 0.35f) else s.color,
                    startAngle = start + gap / 2f,
                    sweepAngle = visible - gap,
                    useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(if (selected == i) wide else stroke, cap = StrokeCap.Butt),
                )
            }
            start += full
        }
    }
}

/**
 * A horizontal bar: [value] against a scale up to [max] (default: the larger of value and target × 1.5), with a tick
 * at [target] (e.g. quantity vs. minimum). The bar takes [color]; [lowColor] when value is at or below target.
 */
@Composable
fun BarMeter(
    value: Double,
    contentDescription: String,
    modifier: Modifier = Modifier,
    target: Double? = null,
    max: Double? = null,
    height: Dp = 8.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    lowColor: Color = MaterialTheme.colorScheme.error,
    animate: Boolean = !rememberReduceMotion(),
) {
    val top = (max ?: maxOf(value, (target ?: 0.0) * 1.5)).takeIf { it > 0 } ?: 1.0
    val progress = drawIn(animate, value)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val marker = MaterialTheme.colorScheme.onSurface
    val low = target != null && value <= target
    Canvas(modifier.fillMaxWidth().height(height).semantics { this.contentDescription = contentDescription }) {
        val r = size.height / 2f
        drawLine(track, Offset(r, r), Offset(size.width - r, r), strokeWidth = size.height, cap = StrokeCap.Round)
        val t = (value / top).coerceIn(0.0, 1.0).toFloat() * progress
        if (t > 0f) {
            val x = r + (size.width - 2 * r) * t
            drawLine(if (low) lowColor else color, Offset(r, r), Offset(x, r), strokeWidth = size.height, cap = StrokeCap.Round)
        }
        if (target != null && target > 0) {
            val x = r + (size.width - 2 * r) * (target / top).coerceIn(0.0, 1.0).toFloat()
            drawLine(marker, Offset(x, -2f), Offset(x, size.height + 2f), strokeWidth = 2.dp.toPx())
        }
    }
}

/**
 * A part's stock against its minimum: filled to quantity / (2 × minimum) with a tick at the minimum (the middle).
 * Neutral normally, amber within 25% above the minimum, red at or below it. Draws nothing without a minimum.
 * Changes slide to the new fill when animations are on; the first draw doesn't animate (rows scroll in quietly).
 */
@Composable
fun StockBar(
    quantity: Int,
    minimum: Int?,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
    track: Color = MaterialTheme.colorScheme.surfaceVariant,
    neutral: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    tick: Color = MaterialTheme.colorScheme.onSurface,
    animate: Boolean = !rememberReduceMotion(),
) {
    if (!StockLevel.tracked(minimum)) return
    val band = StockLevel.band(quantity, minimum)
    val color = when (band) {
        StockLevel.Band.LOW -> MaterialTheme.colorScheme.error
        StockLevel.Band.NEAR -> ChartColors.warning()
        else -> neutral
    }
    val target = StockLevel.fill(quantity, minimum)
    val shown by animateFloatAsState(target, if (animate) tween(400) else snap(), label = "stock")
    val description = StockLevel.describe(quantity, minimum)
    Canvas(modifier.height(height).semantics { contentDescription = description }) {
        val r = size.height / 2f
        drawLine(track, Offset(r, r), Offset(size.width - r, r), strokeWidth = size.height, cap = StrokeCap.Round)
        val t = shown.coerceIn(0f, 1f)
        if (t > 0f) {
            val x = r + (size.width - 2 * r) * t
            drawLine(color, Offset(r, r), Offset(x, r), strokeWidth = size.height, cap = StrokeCap.Round)
        }
        val x = r + (size.width - 2 * r) * StockLevel.TICK
        val w = maxOf(1.5.dp.toPx(), size.height / 4f)
        drawLine(tick, Offset(x, -size.height * 0.4f), Offset(x, size.height * 1.4f), strokeWidth = w)
    }
}

/**
 * Price breaks as steps: one equal-width step per break (quantities grow ~10× each, so equal widths read better
 * than a linear axis), height = unit price from 0. The [marked] step (the break that applies now) is filled.
 * Put the quantities and prices in words beside it; this is the picture.
 */
@Composable
fun PriceStepChart(
    prices: DoubleArray,
    marked: Int,
    contentDescription: String,
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    animate: Boolean = !rememberReduceMotion(),
) {
    val progress = drawIn(animate, prices.toList())
    val faint = color.copy(alpha = 0.12f)
    val strong = color.copy(alpha = 0.35f)
    Canvas(
        modifier.fillMaxWidth().height(height).semantics { this.contentDescription = contentDescription },
    ) {
        if (prices.isEmpty()) return@Canvas
        val pad = 4.dp.toPx()
        val max = prices.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val w = size.width / prices.size
        val bottom = size.height - pad
        fun y(p: Double) = (bottom - (bottom - pad) * (p / max) * progress).toFloat()
        val line = Path()
        prices.forEachIndexed { i, p ->
            val x0 = i * w
            val top = y(p)
            drawRect(if (i == marked) strong else faint, Offset(x0, top), Size(w, bottom - top))
            if (i == 0) line.moveTo(x0, top) else line.lineTo(x0, top)
            line.lineTo(x0 + w, top)
        }
        drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(color.copy(alpha = 0.4f), Offset(0f, bottom), Offset(size.width, bottom), strokeWidth = 1.dp.toPx())
    }
}
