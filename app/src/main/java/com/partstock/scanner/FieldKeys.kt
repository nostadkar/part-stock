package com.partstock.scanner

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Keyboard helpers shared by every form: Next/Done keys, number pads, caps only where codes are typed,
 * and focused fields scrolled above the soft keyboard. Next moves to the following field and Done hides
 * the keyboard (Compose's default actions); nothing here saves anything.
 */

/** Plain text: sentence case (descriptions, notes, names). */
fun textKeys(last: Boolean = false) = KeyboardOptions(
    capitalization = KeyboardCapitalization.Sentences,
    imeAction = if (last) ImeAction.Done else ImeAction.Next,
)

/** Part numbers and bins: all-caps hint, no autocorrect. */
fun codeKeys(last: Boolean = false) = KeyboardOptions(
    capitalization = KeyboardCapitalization.Characters,
    autoCorrectEnabled = false,
    imeAction = if (last) ImeAction.Done else ImeAction.Next,
)

/** Counts (whole numbers) or prices (decimal). */
fun numberKeys(last: Boolean = false, decimal: Boolean = false) = KeyboardOptions(
    keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
    imeAction = if (last) ImeAction.Done else ImeAction.Next,
)

/** Scrolls this field into view above the keyboard when it gets focus. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.bringIntoViewOnFocus(): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    var was by remember { mutableStateOf(false) }
    this
        .bringIntoViewRequester(requester)
        .onFocusEvent { state ->
            if (state.isFocused && !was) {
                scope.launch {
                    // Let the keyboard slide in (imePadding shrinks the area) before scrolling.
                    delay(300)
                    requester.bringIntoView()
                }
            }
            was = state.isFocused
        }
}

/**
 * OutlinedTextField for a number: number keyboard with Next/Done, and the whole value selected when the
 * field gets focus so typing replaces it. Same String value/onValueChange as a normal field.
 */
@Composable
fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    decimal: Boolean = false,
    last: Boolean = false,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    supportingText: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
) {
    var selection by remember { mutableStateOf(TextRange(value.length)) }
    var focusedAt by remember { mutableLongStateOf(0L) }
    val len = value.length
    val shown = TextFieldValue(value, TextRange(selection.start.coerceIn(0, len), selection.end.coerceIn(0, len)))
    OutlinedTextField(
        value = shown,
        onValueChange = { v ->
            // The tap that focused the field also places the cursor; keep the select-all instead.
            val justFocused = SystemClock.uptimeMillis() - focusedAt < 500
            selection = if (v.text == value && v.selection.collapsed && justFocused) TextRange(0, len) else v.selection
            if (v.text != value) {
                focusedAt = 0L
                onValueChange(v.text)
            }
        },
        label = label?.let { { Text(it) } },
        singleLine = true,
        supportingText = supportingText,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        placeholder = placeholder,
        isError = isError,
        enabled = enabled,
        keyboardOptions = numberKeys(last, decimal),
        keyboardActions = keyboardActions,
        modifier = modifier
            .onFocusChanged {
                if (it.isFocused && focusedAt == 0L) {
                    focusedAt = SystemClock.uptimeMillis()
                    selection = TextRange(0, len)
                } else if (!it.isFocused) {
                    focusedAt = 0L
                }
            }
            .bringIntoViewOnFocus(),
    )
}
