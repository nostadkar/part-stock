package com.partstock.scanner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Small read-only chips for a part's value, tolerance, voltage and package (e.g. 10kΩ · 1% · 0603). */
@Composable
fun SpecChips(chips: List<String>, modifier: Modifier = Modifier) {
    if (chips.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        chips.forEach { c ->
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(
                    c,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}
