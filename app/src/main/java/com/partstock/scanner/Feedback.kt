package com.partstock.scanner

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.launch

/**
 * One feedback style for results ("Saved", "Renamed on 3 parts", "Save failed: …"): a themed snackbar instead of a
 * Toast. Short messages go away by themselves; errors stay until dismissed and offer Retry when the action can be
 * repeated. The innermost [FeedbackHost] on screen shows them (a full-screen page brings its own, so nothing hides
 * behind it); with none (no composition, e.g. a background worker) it falls back to a Toast. Undo snackbars stay in
 * [UndoOffer].
 */
object Feedback {
    enum class Kind { SUCCESS, INFO, ERROR }

    class Message(val text: String, val kind: Kind, val retry: (() -> Unit)?)

    private val hosts = CopyOnWriteArrayList<(Message) -> Unit>()

    /** Done: "Saved", "Renamed on 3 parts". */
    fun success(context: Context, text: String) = show(context, Message(text, Kind.SUCCESS, null))

    /** Nothing went wrong, but nothing happened either: "Nothing to move", "No text found…". */
    fun info(context: Context, text: String) = show(context, Message(text, Kind.INFO, null))

    /** Failed; [retry] repeats the action (save to file, export). */
    fun error(context: Context, text: String, retry: (() -> Unit)? = null) = show(context, Message(text, Kind.ERROR, retry))

    fun show(context: Context, m: Message) {
        val host = hosts.lastOrNull()
        if (host != null) host(m) else Toast.makeText(context, m.text, Toast.LENGTH_LONG).show()
    }

    internal fun register(host: (Message) -> Unit): () -> Unit {
        hosts.add(host)
        return { hosts.remove(host) }
    }
}

/** A feedback message as snackbar visuals (so the host can style it; Undo snackbars use the default visuals). */
private class FeedbackVisuals(
    override val message: String,
    val kind: Feedback.Kind,
    override val actionLabel: String?,
) : SnackbarVisuals {
    override val withDismissAction: Boolean get() = kind == Feedback.Kind.ERROR
    override val duration: SnackbarDuration
        get() = if (kind == Feedback.Kind.ERROR) SnackbarDuration.Indefinite else SnackbarDuration.Short
}

/**
 * The snackbar host for [Feedback] (and, on the main screen, [UndoOffer]). While it is on screen it receives every
 * [Feedback] message; put one in a full-screen page's Scaffold so messages show above the page.
 */
@Composable
fun FeedbackHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    DisposableEffect(state) {
        val unregister = Feedback.register { m ->
            scope.launch {
                // A newer message replaces a plain one; an error waits to be dismissed, and Undo isn't cut short.
                val current = state.currentSnackbarData
                val visuals = current?.visuals
                if (visuals is FeedbackVisuals && visuals.kind != Feedback.Kind.ERROR) current.dismiss()
                val r = state.showSnackbar(FeedbackVisuals(m.text, m.kind, if (m.retry != null) "Retry" else null))
                if (r == SnackbarResult.ActionPerformed) m.retry?.invoke()
            }
        }
        onDispose { unregister() }
    }
    SnackbarHost(state, modifier) { data -> FeedbackSnackbar(data) }
}

@Composable
private fun FeedbackSnackbar(data: SnackbarData) {
    val v = data.visuals as? FeedbackVisuals
    if (v == null) {
        Snackbar(data)
        return
    }
    val error = v.kind == Feedback.Kind.ERROR
    val colors = MaterialTheme.colorScheme
    val container = if (error) colors.errorContainer else colors.inverseSurface
    val content = if (error) colors.onErrorContainer else colors.inverseOnSurface
    Snackbar(
        modifier = Modifier.semantics {
            liveRegion = if (error) LiveRegionMode.Assertive else LiveRegionMode.Polite
        },
        action = v.actionLabel?.let { label ->
            {
                TextButton(onClick = { data.performAction() }) {
                    Text(label, color = if (error) colors.error else colors.inversePrimary)
                }
            }
        },
        dismissAction = if (v.withDismissAction) {
            {
                IconButton(onClick = { data.dismiss() }) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = content)
                }
            }
        } else null,
        containerColor = container,
        contentColor = content,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (v.kind) {
                    Feedback.Kind.ERROR -> Icons.Filled.Error
                    Feedback.Kind.SUCCESS -> Icons.Filled.CheckCircle
                    Feedback.Kind.INFO -> Icons.Filled.Info
                },
                contentDescription = if (error) "Error" else null,
                tint = if (error) colors.error else content,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(v.message)
        }
    }
}
