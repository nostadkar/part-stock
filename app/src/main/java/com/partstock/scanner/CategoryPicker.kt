package com.partstock.scanner

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.partstock.scanner.parse.Categories

/** Button showing the category; tap to pick another bucket. */
@Composable
fun CategoryPicker(value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Category: $value", modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Change category")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Categories.ALL.forEach { c ->
                DropdownMenuItem(text = { Text(c) }, onClick = {
                    open = false
                    onChange(c)
                })
            }
        }
    }
}
