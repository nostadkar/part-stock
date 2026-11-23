package com.partstock.scanner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.RemoveShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.partstock.scanner.parse.BuildPlan
import com.partstock.scanner.parse.ProjectStatus

/*
 * Pieces of a project card on the Projects list (#43): BOM lines as a segmented bar (covered / partial / none /
 * no match, at 1 board), a state chip and the blocking parts as small category-icon chips.
 */

/** BOM lines as one bar split by coverage, in the coverage-bar colors; read out as counts. */
@Composable
fun BomSegmentBar(s: ProjectStatus.Summary, modifier: Modifier = Modifier) {
    val segments = listOf(
        s.covered to coverageColor(BuildPlan.Coverage.COVERED),
        s.partial to coverageColor(BuildPlan.Coverage.PARTIAL),
        s.none to coverageColor(BuildPlan.Coverage.NONE),
        s.noMatch to coverageColor(BuildPlan.Coverage.NO_MATCH),
    ).filter { it.first > 0 }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val description = s.barDescription()
    Canvas(modifier.fillMaxWidth().height(8.dp).semantics { contentDescription = description }) {
        val r = size.height / 2f
        drawRoundRect(track, cornerRadius = CornerRadius(r, r))
        if (s.lines <= 0) return@Canvas
        val gap = if (segments.size > 1) 2.dp.toPx() else 0f
        val usable = size.width - gap * (segments.size - 1)
        var x = 0f
        segments.forEach { (count, color) ->
            val w = usable * count / s.lines
            drawRoundRect(color, Offset(x, 0f), Size(w, size.height), CornerRadius(r, r))
            x += w + gap
        }
    }
}

/** A dot legend under the bar ("8 covered · 2 partial · 1 none · 1 no match"), so color isn't the only cue. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BomSegmentLegend(s: ProjectStatus.Summary) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            Triple(s.covered, "covered", BuildPlan.Coverage.COVERED),
            Triple(s.partial, "partial", BuildPlan.Coverage.PARTIAL),
            Triple(s.none, "none", BuildPlan.Coverage.NONE),
            Triple(s.noMatch, "no match", BuildPlan.Coverage.NO_MATCH),
        ).filter { it.first > 0 }.forEach { (n, word, status) ->
            val color = coverageColor(status)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(8.dp)) { drawCircle(color) }
                Text(
                    " $n $word", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "Ready to build ×3" (green), "Missing 3 parts" (amber), "Needs matching (2 lines)" (red) with an icon. */
@Composable
fun ProjectStateChip(s: ProjectStatus.Summary) {
    val (color, icon) = when (s.state) {
        ProjectStatus.State.READY -> ChartColors.positive() to Icons.Filled.CheckCircle
        ProjectStatus.State.MISSING -> ChartColors.warning() to Icons.Filled.RemoveShoppingCart
        ProjectStatus.State.NEEDS_MATCHING -> MaterialTheme.colorScheme.error to Icons.Filled.Link
        else -> MaterialTheme.colorScheme.outline to Icons.Filled.Link
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.16f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Text(
                " " + s.stateText(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/** Up to [max] blocking parts as category-icon chips, then "+N". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BlockerChips(s: ProjectStatus.Summary, max: Int = 3) {
    if (s.blocking.isEmpty()) return
    val shown = s.blocking.take(max)
    val more = s.blocking.size - shown.size
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "Blocking: " + s.blocking.joinToString(", ") {
                it.label + if (it.noMatch) " (no match)" else " (short ${it.missing})"
            }
        },
    ) {
        shown.forEach { b ->
            val tint: Color = if (b.noMatch) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Row(
                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(categoryIcon(b.category), contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
                    Text(
                        " " + b.label, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp),
                    )
                    Text(
                        if (b.noMatch) " ?" else " −${b.missing}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = tint,
                    )
                }
            }
        }
        if (more > 0) {
            Text(
                "+$more", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
    }
}

/** The card body under the project name: state chip, segmented bar, legend and blockers. */
@Composable
fun ProjectCardStatus(s: ProjectStatus.Summary) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
        ProjectStateChip(s)
        if (s.lines > 0) {
            BomSegmentBar(s)
            BomSegmentLegend(s)
        }
        BlockerChips(s)
    }
}
