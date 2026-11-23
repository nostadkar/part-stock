package com.partstock.scanner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.partstock.scanner.parse.BuildPlan

/*
 * Shared pieces for project builds and kit builds: a boards gauge, per-line coverage bars and the "Short for N
 * boards" bar chart, so both read the same. The numbers come from parse/BuildPlan.java.
 */

/** Green when covered, amber when partly, red when none, grey for "No match". */
@Composable
@ReadOnlyComposable
fun coverageColor(status: BuildPlan.Coverage): Color = when (status) {
    BuildPlan.Coverage.COVERED -> ChartColors.positive()
    BuildPlan.Coverage.PARTIAL -> ChartColors.warning()
    BuildPlan.Coverage.NONE -> MaterialTheme.colorScheme.error
    BuildPlan.Coverage.NO_MATCH -> MaterialTheme.colorScheme.outline
}

/** A word for the status, so the bar isn't read by color alone. */
fun coverageWord(status: BuildPlan.Coverage, have: Int, need: Int): String = when (status) {
    BuildPlan.Coverage.COVERED -> "covered"
    BuildPlan.Coverage.PARTIAL -> "short ${need - have}"
    BuildPlan.Coverage.NONE -> "none in stock"
    BuildPlan.Coverage.NO_MATCH -> "no match"
}

/** Have vs. need as a thin bar; a "No match" line gets a full grey bar. */
@Composable
fun CoverageBar(status: BuildPlan.Coverage, coverage: Float, have: Int, need: Int, modifier: Modifier = Modifier) {
    val noMatch = status == BuildPlan.Coverage.NO_MATCH
    BarMeter(
        value = if (noMatch) 1.0 else coverage.toDouble(),
        max = 1.0,
        contentDescription = if (noMatch) "No match in stock" else "Have $have of $need, ${coverageWord(status, have, need)}",
        height = 6.dp,
        color = coverageColor(status),
        lowColor = coverageColor(status),
        animate = false,
        modifier = modifier,
    )
}

/** "3 of 10 boards": what the stock supports against the number typed, as a big number and a bar. */
@Composable
fun BoardsGauge(buildable: Int, boards: Int, unmatched: Int, modifier: Modifier = Modifier) {
    val enough = unmatched == 0 && buildable >= boards
    val color = when {
        unmatched > 0 || buildable == 0 -> MaterialTheme.colorScheme.error
        enough -> ChartColors.positive()
        else -> ChartColors.warning()
    }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    buildable.toString(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold,
                    color = color,
                )
                Text(
                    if (boards > 0) " of $boards board${if (boards == 1) "" else "s"}"
                    else " board${if (buildable == 1) "" else "s"} buildable",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
            }
            if (boards > 0) {
                BarMeter(
                    value = buildable.coerceAtMost(boards).toDouble(),
                    max = boards.toDouble(),
                    contentDescription = "Can build $buildable of $boards boards",
                    height = 10.dp,
                    color = color,
                    lowColor = color,
                    animate = false,
                )
            }
            Text(
                when {
                    unmatched > 0 -> "Can't build yet: $unmatched line${if (unmatched == 1) " has" else "s have"} no match"
                    boards > 0 && enough -> "Enough stock for $boards"
                    boards > 0 -> "Stock runs out after $buildable"
                    else -> "Type how many boards to plan for"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One bar of the shortage chart. */
class ShortBar(val label: String, val missing: Int, val detail: String)

/** The biggest shortages as horizontal bars (longest = most missing); tap one to jump to its line. */
@Composable
fun ShortageChart(bars: List<ShortBar>, modifier: Modifier = Modifier, onTap: ((Int) -> Unit)? = null) {
    if (bars.isEmpty()) return
    val max = bars.maxOf { it.missing }.coerceAtLeast(1)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        bars.forEachIndexed { i, b ->
            Column(
                Modifier.fillMaxWidth()
                    .let { if (onTap != null) it.clickable { onTap(i) } else it }
                    .padding(vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        b.label, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "−${b.missing}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 8.dp),
                    )
                }
                BarMeter(
                    value = b.missing.toDouble(),
                    max = max.toDouble(),
                    contentDescription = "${b.label} short ${b.missing}, ${b.detail}",
                    height = 6.dp,
                    color = MaterialTheme.colorScheme.error,
                    animate = false,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    b.detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
