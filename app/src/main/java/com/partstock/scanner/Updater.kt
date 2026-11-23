package com.partstock.scanner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.partstock.scanner.parse.JobText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks this app's GitHub repo for a newer release build and installs it.
 * Every push to main publishes a release tagged "build-N" with part-stock.apk; the app's versionCode is N.
 * The new APK is signed with the same key, so Android installs it over this one and the inventory is kept.
 */
object Updater {
    private const val REPO = "nostadkar/part-stock"
    private const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
    private const val APK_NAME = "part-stock.apk"
    private const val AUTO_CHECK_EVERY_MS = 6L * 60 * 60 * 1000

    /** [notes]: the release text (the commit message for builds from main); blank when there is none. */
    data class Release(val build: Int, val name: String, val apkUrl: String, val sizeBytes: Long, val notes: String = "")

    fun currentBuild(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    fun currentName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    /** Latest release on GitHub, or null if there is none or it has no APK. Throws on network errors. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val conn = URL(LATEST_API).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode == 404) return@withContext null
            if (conn.responseCode != 200) error("GitHub answered ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val build = json.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return@withContext null
            val assets = json.optJSONArray("assets") ?: return@withContext null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val url = a.optString("browser_download_url")
                if (a.optString("name") == APK_NAME && url.startsWith(DOWNLOAD_PREFIX)) {
                    // optString gives "null" for a JSON null, so check for it first.
                    val notes = if (json.isNull("body")) "" else json.optString("body")
                    return@withContext Release(build, json.optString("name", "build $build"), url, a.optLong("size", 0L), notes)
                }
            }
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Newer release for the automatic check at launch: at most every few hours, quiet after "Later". */
    suspend fun autoCheck(context: Context, prefs: Prefs): Release? {
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheck < AUTO_CHECK_EVERY_MS) return null
        val r = try { latest() } catch (e: Exception) { return null } // offline: try again next launch
        prefs.lastUpdateCheck = now
        if (r != null) prefs.latestKnownBuild = r.build
        if (r == null || r.build <= currentBuild(context) || r.build <= prefs.dismissedBuild) return null
        return r
    }

    /**
     * Downloads the APK into the app's cache and checks it really is a newer build of this app. [onProgress] gets
     * (bytes so far, total bytes or 0 when unknown); cancelling the coroutine stops the download and deletes the file.
     */
    suspend fun download(context: Context, r: Release, onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, APK_NAME)
        val conn = URL(r.apkUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode != 200) error("Download failed (${conn.responseCode})")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: r.sizeBytes
            onProgress(0L, total)
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        if (!isActive) {
                            out.close()
                            file.delete()
                            throw kotlinx.coroutines.CancellationException("Download stopped")
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(file.path, 0)
        if (info == null || info.packageName != context.packageName) {
            file.delete()
            error("Downloaded file isn't a Part Stock APK")
        }
        if (PackageInfoCompat.getLongVersionCode(info) <= currentBuild(context)) {
            file.delete()
            error("Downloaded build isn't newer than this one")
        }
        file
    }

    /** Android 8+ asks once to allow installs from this app. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Opens the system installer; it shows its own "Update" confirmation. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * "Update available" dialog with the download size and the release notes' first lines. Update starts the download
 * as a background job ([JobsViewModel]): its progress card shows MB/s, then Android's installer opens.
 */
@Composable
fun UpdateDialog(release: Updater.Release, onLater: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val jobs = LocalJobs.current
    val notes = remember(release) { JobText.firstLines(release.notes, 3) }
    val size = JobText.bytes(release.sizeBytes)
    val downloading = jobs.isRunning(JobKind.UPDATE)

    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("Update available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${release.name} is ready (you have ${Updater.currentName(context)}). " +
                        "It installs over this version and keeps your inventory." +
                        if (size.isNotEmpty()) " Download: $size." else ""
                )
                if (notes.isNotEmpty()) {
                    Text("What's new", style = MaterialTheme.typography.titleSmall)
                    notes.forEach { Text("\u2022 $it", style = MaterialTheme.typography.bodySmall) }
                }
                if (downloading) Text("Downloading; see the card at the bottom.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = {
                    jobs.downloadUpdate(release)
                    onClose()
                },
            ) { Text("Update") }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text("Later") }
        },
    )
}
