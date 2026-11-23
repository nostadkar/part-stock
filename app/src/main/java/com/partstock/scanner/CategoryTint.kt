package com.partstock.scanner

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.partstock.scanner.parse.CategoryTints
import com.partstock.scanner.parse.PackageShapes

/**
 * A category's colors: [accent] is the raw tint, [container]/[onContainer] the no-photo tile (4.5:1 contrast), and
 * [onSurface] the tint made readable as text on the normal surface (sticky headers).
 */
data class CategoryTint(val accent: Color, val container: Color, val onContainer: Color, val onSurface: Color)

/**
 * The stable tint for [category]: with wallpaper colors (Android 12+) it is the theme's primary turned round the hue
 * wheel per category, otherwise one of the fixed color-blind-safe chart colors.
 */
@Composable
@ReadOnlyComposable
fun categoryTint(category: String): CategoryTint {
    val scheme = MaterialTheme.colorScheme
    val accent = if (AppTheme.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        CategoryTints.derived(scheme.primary.toArgb(), category)
    } else {
        CategoryTints.pick(ChartColors.fixed().map { it.toArgb() }.toIntArray(), category)
    }
    val surface = scheme.surface.toArgb()
    val on = scheme.onSurface.toArgb()
    val tile = CategoryTints.tile(accent, surface, on)
    return CategoryTint(
        accent = Color(accent),
        container = Color(tile[0]),
        onContainer = Color(tile[1]),
        onSurface = Color(CategoryTints.readable(accent, surface, on, 4.5)),
    )
}

/** A small outline drawing of a common package (0603 chip, SOT-23, SOIC, QFN, DIP, TO-220); nothing for others. */
@Composable
fun PackageIcon(pkg: String, color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val kind = PackageShapes.kind(pkg)
    if (kind.isEmpty()) return
    Canvas(modifier.size(size)) { drawPackage(kind, color) }
}

private fun DrawScope.drawPackage(kind: String, color: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = (w / 12f).coerceAtLeast(1f))
    fun body(x: Float, y: Float, bw: Float, bh: Float, r: Float = w * 0.06f) =
        drawRoundRect(color, Offset(x, y), Size(bw, bh), CornerRadius(r), style = stroke)
    fun pad(x: Float, y: Float, pw: Float, ph: Float) = drawRect(color, Offset(x, y), Size(pw, ph))
    when (kind) {
        PackageShapes.CHIP_0402, PackageShapes.CHIP_0603, PackageShapes.CHIP_0805 -> {
            // Chip: a body with metal ends; bigger codes draw a bigger chip.
            val scale = when (kind) { PackageShapes.CHIP_0402 -> 0.6f; PackageShapes.CHIP_0603 -> 0.8f; else -> 1f }
            val bw = w * 0.9f * scale
            val bh = bw * 0.5f
            val x = (w - bw) / 2f
            val y = (h - bh) / 2f
            body(x, y, bw, bh)
            pad(x, y, bw * 0.22f, bh)
            pad(x + bw * 0.78f, y, bw * 0.22f, bh)
        }
        PackageShapes.SOT23 -> {
            body(w * 0.2f, h * 0.3f, w * 0.6f, h * 0.4f)
            pad(w * 0.25f, h * 0.7f, w * 0.12f, h * 0.18f)
            pad(w * 0.63f, h * 0.7f, w * 0.12f, h * 0.18f)
            pad(w * 0.44f, h * 0.12f, w * 0.12f, h * 0.18f)
        }
        PackageShapes.SOIC, PackageShapes.DIP -> {
            val dip = kind == PackageShapes.DIP
            val bx = if (dip) w * 0.25f else w * 0.22f
            val bw = w - 2 * bx
            body(bx, h * 0.08f, bw, h * 0.84f)
            drawCircle(color, w * 0.05f, Offset(bx + w * 0.12f, h * 0.2f))
            for (i in 0 until 4) {
                val y = h * (0.16f + i * 0.2f)
                val len = if (dip) w * 0.2f else w * 0.16f
                pad(bx - len, y, len, h * 0.08f)
                pad(bx + bw, y, len, h * 0.08f)
            }
        }
        PackageShapes.QFN -> {
            body(w * 0.12f, h * 0.12f, w * 0.76f, h * 0.76f)
            pad(w * 0.34f, h * 0.34f, w * 0.32f, h * 0.32f)
            for (i in 0 until 3) {
                val t = w * (0.27f + i * 0.2f)
                pad(t, h * 0.14f, w * 0.06f, h * 0.08f)
                pad(t, h * 0.78f, w * 0.06f, h * 0.08f)
                pad(w * 0.14f, t, w * 0.08f, h * 0.06f)
                pad(w * 0.78f, t, w * 0.08f, h * 0.06f)
            }
        }
        PackageShapes.TO220 -> {
            body(w * 0.18f, h * 0.04f, w * 0.64f, h * 0.22f) // tab
            drawCircle(color, w * 0.07f, Offset(w * 0.5f, h * 0.15f), style = stroke)
            body(w * 0.18f, h * 0.26f, w * 0.64f, h * 0.34f)
            for (i in 0 until 3) pad(w * (0.27f + i * 0.2f), h * 0.6f, w * 0.06f, h * 0.36f)
        }
    }
}
