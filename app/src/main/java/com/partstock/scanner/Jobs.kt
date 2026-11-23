package com.partstock.scanner

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.partstock.scanner.parse.Categories
import com.partstock.scanner.parse.JobText
import com.partstock.scanner.parse.PriceRefresh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The long jobs that show a progress card instead of a modal dialog. */
enum class JobKind { PRICES, ZIP, UPDATE }

/** A running job, as its card shows it. [fraction] null = indeterminate bar. */
data class JobProgress(
    val kind: JobKind,
    val title: String,
    val detail: String = "",
    val fraction: Float? = null,
)

/** A finished job's outcome, shown on its card until dismissed. [action] is the card's button ("View", "Install"). */
data class JobResult(
    val kind: JobKind,
    val title: String,
    val text: String,
    val error: Boolean = false,
    val action: String? = null,
)

/** What a price refresh changed, for the result dialog. */
data class PriceRefreshOutcome(
    val counts: String,
    /** "Stopped: rate limit. 5 not checked", or blank when it ran to the end. */
    val stopped: String,
    val movers: List<PriceRefresh.Change>,
    val changedIds: Set<Long>,
)

/** The app's [JobsViewModel], provided at the top of the activity's content. */
val LocalJobs = staticCompositionLocalOf<JobsViewModel> { error("No JobsViewModel provided") }

/**
 * Runs the long jobs (price refresh, zip export, app update download) outside any screen or dialog, so they keep
 * going while you browse; [running] and [results] feed the progress cards above the bottom bar.
 */
class JobsViewModel(private val app: Context, private val db: InventoryDb) : ViewModel() {
    private val _running = MutableStateFlow<Map<JobKind, JobProgress>>(emptyMap())
    val running: StateFlow<Map<JobKind, JobProgress>> = _running.asStateFlow()
    private val _results = MutableStateFlow<Map<JobKind, JobResult>>(emptyMap())
    val results: StateFlow<Map<JobKind, JobResult>> = _results.asStateFlow()
    private val _priceOutcome = MutableStateFlow<PriceRefreshOutcome?>(null)
    val priceOutcome: StateFlow<PriceRefreshOutcome?> = _priceOutcome.asStateFlow()
    private val jobs = HashMap<JobKind, Job>()

    /** Activities to start (share sheet) with the activity's context; collected by the app. */
    private val launches = Channel<Intent>(Channel.BUFFERED)
    val toLaunch = launches.receiveAsFlow()

    /** The release being downloaded or waiting to be installed (Retry / Install on its card). */
    var release: Updater.Release? = null
        private set
    private var apk: java.io.File? = null

    fun isRunning(kind: JobKind): Boolean = jobs[kind]?.isActive == true

    private fun progress(p: JobProgress) = _running.update { it + (p.kind to p) }

    private fun finish(kind: JobKind, result: JobResult?) {
        _running.update { it - kind }
        _results.update { if (result == null) it - kind else it + (kind to result) }
    }

    fun dismiss(kind: JobKind) {
        _results.update { it - kind }
        if (kind == JobKind.PRICES) _priceOutcome.value = null
    }

    /** Stops a running job; it cleans up and leaves its result card. */
    fun cancel(kind: JobKind) {
        jobs[kind]?.cancel()
    }

    override fun onCleared() {
        jobs.values.forEach { it.cancel() }
    }

    // ---- Refresh prices ----

    /**
     * Looks up every [targets] part on Mouser, one request every [PriceRefresh.MOUSER_PACE_MS] (Mouser allows about
     * 30 a minute), and updates price, description, link and category (unless picked by hand). Stops on a
     * rate-limit or key error; parts done so far stay updated. Old and new unit costs are kept for the result.
     */
    fun startPriceRefresh(key: String, targets: List<PartRow>) {
        if (isRunning(JobKind.PRICES) || targets.isEmpty()) return
        dismiss(JobKind.PRICES)
        jobs[JobKind.PRICES] = viewModelScope.launch {
            var done = 0
            var updated = 0
            var notFound = 0
            var failed = 0
            var stopped: String? = null
            val changes = ArrayList<PriceRefresh.Change>()
            fun show() = progress(
                JobProgress(
                    JobKind.PRICES, "Refreshing prices",
                    "${JobText.count(minOf(done + 1, targets.size), targets.size)} · updated $updated",
                    done.toFloat() / targets.size,
                ),
            )
            show()
            try {
                for ((i, p) in targets.withIndex()) {
                    if (i > 0) delay(PriceRefresh.MOUSER_PACE_MS)
                    try {
                        val r = MouserApi.lookup(key, p.distributorPn.trim(), maxOf(1, p.quantity))
                        if (r == null) {
                            notFound++
                        } else {
                            val cat = Categories.classify(r.category, r.description, p.description)
                            // The part as it was just before this update (not the list's copy, which may be older).
                            val old = withContext(Dispatchers.IO) {
                                db.applyLookup(
                                    p.id,
                                    LookupUpdate(r.unitPrice, r.description, cat, r.url, "Mouser", r.datasheetUrl, r.specs),
                                )
                            }
                            if (old != null) {
                                changes += PriceRefresh.Change(p.id, p.key(), old.unitCost, r.unitPrice ?: old.unitCost)
                                updated++
                            }
                        }
                    } catch (e: MouserException) {
                        if (e.rateLimited || e.badKey) {
                            stopped = e.message ?: "Mouser error"
                            break
                        }
                        failed++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                    }
                    done = i + 1
                    show()
                }
            } catch (e: CancellationException) {
                stopped = "you stopped it"
                throw e
            } finally {
                withContext(NonCancellable) {
                    if (updated > 0) withContext(Dispatchers.IO) { db.notifyChanged() }
                    val notChecked = targets.size - done
                    val stopText = stopped?.let { s ->
                        "Stopped: $s" + if (notChecked > 0) ". $notChecked not checked" else ""
                    }.orEmpty()
                    val counts = PriceRefresh.resultCounts(changes, notFound, failed)
                    _priceOutcome.value = PriceRefreshOutcome(
                        counts, stopText, PriceRefresh.movers(changes, MOVERS), PriceRefresh.changedIds(changes).toSet(),
                    )
                    finish(
                        JobKind.PRICES,
                        JobResult(
                            JobKind.PRICES,
                            if (stopped == null) "Prices refreshed" else "Price refresh stopped",
                            if (stopText.isEmpty()) counts else "$counts. $stopText",
                            error = stopped != null && stopped != "you stopped it",
                            action = "View",
                        ),
                    )
                }
            }
        }
    }

    // ---- Inventory + photos zip ----

    /** Builds the zip, then opens the share sheet. */
    fun shareZip() = zip(null)

    /** Writes the zip to a file the user picked. */
    fun saveZip(uri: Uri) = zip(uri)

    private fun zip(uri: Uri?) {
        if (isRunning(JobKind.ZIP)) return
        dismiss(JobKind.ZIP)
        jobs[JobKind.ZIP] = viewModelScope.launch {
            val title = "Exporting inventory + photos"
            progress(JobProgress(JobKind.ZIP, title))
            val onProgress = { n: Int, total: Int ->
                progress(
                    JobProgress(
                        JobKind.ZIP, title,
                        if (total > 0) "Photo ${JobText.count(minOf(n + 1, total), total)}" else "",
                        if (total > 0) n.toFloat() / total else null,
                    ),
                )
            }
            var result: JobResult? = null
            try {
                val job = coroutineContext[Job]
                val cancelled = { job?.isActive == false }
                if (uri == null) {
                    val chooser = withContext(Dispatchers.IO) { Export.shareZip(app, db, onProgress, cancelled) }
                    launches.send(chooser)
                } else {
                    val n = withContext(Dispatchers.IO) { Export.saveZip(app, uri, db, onProgress, cancelled) }
                    result = JobResult(JobKind.ZIP, "Export saved", "Saved with $n photo${if (n == 1) "" else "s"}")
                }
            } catch (e: CancellationException) {
                result = JobResult(JobKind.ZIP, "Export stopped", "No zip was kept")
                throw e
            } catch (e: Exception) {
                result = JobResult(JobKind.ZIP, "Export failed", e.message ?: "Couldn't write the zip", error = true)
            } finally {
                finish(JobKind.ZIP, result)
            }
            if (result != null && !result.error) {
                delay(RESULT_SHOWN_MS)
                if (_results.value[JobKind.ZIP] == result) dismiss(JobKind.ZIP)
            }
        }
    }

    // ---- App update ----

    /** Downloads [r] with a progress card (size, MB/s), then opens Android's installer. */
    fun downloadUpdate(r: Updater.Release) {
        if (isRunning(JobKind.UPDATE)) return
        dismiss(JobKind.UPDATE)
        release = r
        apk = null
        jobs[JobKind.UPDATE] = viewModelScope.launch {
            val title = "Downloading ${r.name}"
            val started = System.currentTimeMillis()
            progress(JobProgress(JobKind.UPDATE, title, JobText.bytes(r.sizeBytes)))
            var result: JobResult? = null
            try {
                val file = Updater.download(app, r) { done, total ->
                    progress(
                        JobProgress(
                            JobKind.UPDATE, title,
                            JobText.download(done, total, System.currentTimeMillis() - started),
                            if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null,
                        ),
                    )
                }
                apk = file
                result = install()
            } catch (e: CancellationException) {
                result = JobResult(JobKind.UPDATE, "Update stopped", "Nothing was installed")
                throw e
            } catch (e: Exception) {
                result = JobResult(JobKind.UPDATE, "Update failed", e.message ?: "Download failed", error = true, action = "Retry")
            } finally {
                finish(JobKind.UPDATE, result)
            }
        }
    }

    /** Opens the installer, or asks for the "install apps" permission first (the card then offers Install). */
    private fun install(): JobResult? {
        val file = apk ?: return null
        return if (Updater.canInstall(app)) {
            Updater.install(app, file)
            null
        } else {
            Updater.openInstallPermission(app)
            JobResult(
                JobKind.UPDATE, "Update downloaded",
                "Allow Part Stock to install apps, then tap Install.", action = "Install",
            )
        }
    }

    /** The update card's button: Install (after allowing installs) or Retry (after a failed download). */
    fun updateAction() {
        if (apk != null) {
            finish(JobKind.UPDATE, install())
        } else {
            release?.let(::downloadUpdate)
        }
    }

    companion object {
        /** Biggest price moves listed after a refresh. */
        const val MOVERS = 8
        const val RESULT_SHOWN_MS = 6_000L
    }
}

/**
 * The progress and result cards, stacked above the bottom bar (and the undo snackbar). Tapping the price refresh card
 * opens its details: progress while it runs, what changed once it's done.
 */
@Composable
fun JobCards(jobs: JobsViewModel, onShowChanged: (Set<Long>) -> Unit, modifier: Modifier = Modifier) {
    val running by jobs.running.collectAsStateWithLifecycle()
    val results by jobs.results.collectAsStateWithLifecycle()
    var details by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        JobKind.entries.forEach { kind ->
            val p = running[kind]
            val r = results[kind]
            AnimatedVisibility(visible = p != null || r != null) {
                when {
                    p != null -> ProgressCard(
                        p,
                        onCancel = { jobs.cancel(kind) },
                        onOpen = if (kind == JobKind.PRICES) ({ details = true }) else null,
                    )
                    r != null -> ResultCard(
                        r,
                        onAction = {
                            when (kind) {
                                JobKind.PRICES -> details = true
                                JobKind.UPDATE -> jobs.updateAction()
                                JobKind.ZIP -> jobs.dismiss(kind)
                            }
                        },
                        onDismiss = { jobs.dismiss(kind) },
                    )
                }
            }
        }
    }

    if (details) {
        val p = running[JobKind.PRICES]
        val outcome by jobs.priceOutcome.collectAsStateWithLifecycle()
        when {
            p != null -> PriceProgressDialog(p, onStop = { jobs.cancel(JobKind.PRICES) }, onHide = { details = false })
            outcome != null -> PriceResultDialog(
                outcome!!,
                onShowChanged = { ids ->
                    details = false
                    jobs.dismiss(JobKind.PRICES)
                    onShowChanged(ids)
                },
                onClose = { details = false },
            )
            else -> LaunchedEffect(Unit) { details = false }
        }
    }
}

@Composable
private fun ProgressCard(p: JobProgress, onCancel: () -> Unit, onOpen: (() -> Unit)?) {
    ElevatedCard(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).let { m ->
            if (onOpen != null) m.clickable(onClickLabel = "Show details", onClick = onOpen) else m
        },
    ) {
        Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(p.title, style = MaterialTheme.typography.titleSmall)
                if (p.detail.isNotBlank()) {
                    Text(p.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val f = p.fraction
                if (f != null) {
                    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 2.dp))
                }
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun ResultCard(r: JobResult, onAction: () -> Unit, onDismiss: () -> Unit) {
    ElevatedCard(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(
                    r.title, style = MaterialTheme.typography.titleSmall,
                    color = if (r.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Text(r.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            r.action?.let { TextButton(onClick = onAction) { Text(it) } }
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss") }
        }
    }
}

/** Price refresh details while it runs: it keeps going after Hide. */
@Composable
private fun PriceProgressDialog(p: JobProgress, onStop: () -> Unit, onHide: () -> Unit) {
    AlertDialog(
        onDismissRequest = onHide,
        title = { Text("Refresh prices") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.detail)
                LinearProgressIndicator(progress = { p.fraction ?: 0f }, modifier = Modifier.fillMaxWidth())
                Text(
                    "One request every ${PriceRefresh.MOUSER_PACE_MS / 1000.0} s to stay under Mouser's limit. " +
                        "It keeps running while you use the rest of the app.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onHide) { Text("Hide") } },
        dismissButton = { TextButton(onClick = onStop) { Text("Stop") } },
    )
}

/** What a finished refresh changed: the biggest movers, the counts, and a link to the changed parts. */
@Composable
private fun PriceResultDialog(o: PriceRefreshOutcome, onShowChanged: (Set<Long>) -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Price refresh") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(o.counts)
                if (o.stopped.isNotBlank()) {
                    Text(o.stopped, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (o.movers.isNotEmpty()) {
                    Text("Biggest changes", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                    LazyColumn(Modifier.heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(o.movers, key = { it.id }) { c ->
                            val up = c.kind() == PriceRefresh.Kind.UP
                            Column {
                                Text(c.key, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    PriceRefresh.moverText(c),
                                    style = MaterialTheme.typography.bodySmall,
                                    // Dearer is bad news (red), cheaper is good (green).
                                    color = if (up) ChartColors.negative() else ChartColors.positive(),
                                    modifier = Modifier.semantics {
                                        contentDescription = (if (up) "Up " else "Down ") +
                                            PriceRefresh.percentText(PriceRefresh.percent(c.oldCost, c.newCost) ?: 0.0) +
                                            ", from ${PriceRefresh.cost(c.oldCost ?: 0.0)} to ${PriceRefresh.cost(c.newCost ?: 0.0)}"
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (o.changedIds.isNotEmpty()) {
                TextButton(onClick = { onShowChanged(o.changedIds) }) {
                    Text("Show ${o.changedIds.size} part${if (o.changedIds.size == 1) "" else "s"} with a price change")
                }
            } else {
                TextButton(onClick = onClose) { Text("Close") }
            }
        },
        dismissButton = { if (o.changedIds.isNotEmpty()) TextButton(onClick = onClose) { Text("Close") } },
    )
}
