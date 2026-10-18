package com.partstock.scanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Part photos: JPEGs in the app's private files folder (photos/), longest side [MAX_SIDE] px. The database keeps the
 * file names (table part_photos). Photos of a deleted part go to photos/trash/ so Undo can bring them back, and are
 * removed for good after [InventoryDb.SNAPSHOT_DAYS] days. The photos folder is left out of Android cloud backup
 * (see res/xml/backup_rules.xml): a few hundred photos would pass the 25 MB backup limit and stop backups altogether.
 */
object PartPhotos {
    const val MAX_SIDE = 1600
    private const val QUALITY = 85

    fun dir(context: Context): File = File(context.applicationContext.filesDir, "photos").apply { mkdirs() }

    fun file(context: Context, name: String): File = File(dir(context), name)

    /** Scales [bmp] down to [MAX_SIDE] and writes it as a new JPEG. Returns the file name. Call off the main thread. */
    fun save(context: Context, bmp: Bitmap): String {
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height))
        val out = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
        } else bmp
        val name = "p-" + UUID.randomUUID().toString().replace("-", "").take(16) + ".jpg"
        try {
            FileOutputStream(file(context, name)).use { out.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        } finally {
            if (out !== bmp) out.recycle()
        }
        return name
    }

    /** Copies a picked or camera photo into the photos folder (upright, downscaled). Null if it can't be read. */
    suspend fun importUri(context: Context, uri: Uri): String? {
        val bmp = loadPhoto(context, uri) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                save(context, bmp)
            } finally {
                bmp.recycle()
            }
        }
    }

    private val thumbs = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** A small copy (about [px] px) for lists, cached; null if the file is missing (e.g. after a backup restore). */
    fun thumbnail(context: Context, name: String, px: Int): Bitmap? {
        val key = "$name@$px"
        thumbs.get(key)?.let { return it }
        val f = file(context, name)
        if (!f.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        thumbs.put(key, bmp)
        return bmp
    }

    /** The whole photo for the full-screen viewer; null if missing. */
    fun full(context: Context, name: String): Bitmap? {
        val f = file(context, name)
        return if (f.isFile) BitmapFactory.decodeFile(f.path) else null
    }

    fun forget(name: String) {
        thumbs.snapshot().keys.filter { it.startsWith("$name@") }.forEach { thumbs.remove(it) }
    }
}

/** Square thumbnail of a part photo, loaded off the main thread. Shows nothing if the file is missing. */
@Composable
fun PhotoThumb(name: String, size: Dp, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val context = LocalContext.current
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val image by produceState<ImageBitmap?>(null, name, px) {
        value = withContext(Dispatchers.IO) { PartPhotos.thumbnail(context, name, px)?.asImageBitmap() }
    }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        image?.let { Image(it, contentDescription = "Part photo", contentScale = ContentScale.Crop, modifier = Modifier.size(size)) }
    }
}

/**
 * The photos strip in a part's dialog: thumbnails (tap for full screen, with Delete there) and buttons to add a photo
 * from the camera or the gallery. Changes are saved straight away.
 */
@Composable
fun PartPhotosRow(db: InventoryDb, partId: Long, onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val photos = remember(version) { db.photos(partId) }
    var viewing by remember { mutableStateOf<PartPhoto?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun add(uri: Uri?) {
        if (uri == null) return
        busy = true
        scope.launch {
            val name = try {
                PartPhotos.importUri(context, uri)
            } catch (e: Exception) {
                null
            }
            if (name == null) {
                msg = "Couldn't read that photo"
            } else {
                withContext(Dispatchers.IO) { db.addPhoto(partId, name) }
                msg = null
                version++
                onChanged()
            }
            busy = false
        }
    }

    val pickers = rememberPhotoPickers(onPicked = ::add, onError = { msg = it })

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (photos.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                photos.forEach { p -> PhotoThumb(p.file, 64.dp, onClick = { viewing = p }) }
            }
        }
        Row {
            TextButton(onClick = pickers.camera, enabled = !busy) { Text("Take photo") }
            TextButton(onClick = pickers.gallery, enabled = !busy) { Text("Add from gallery") }
        }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }

    viewing?.let { p ->
        fun delete() {
            db.deletePhoto(p.id)
            viewing = null
            version++
            onChanged()
        }
        // null = still loading; a missing file (e.g. after restoring the app from a cloud backup) can be removed.
        val loaded by produceState<Pair<Bitmap?, Boolean>?>(null, p.file) {
            value = withContext(Dispatchers.IO) { PartPhotos.full(context, p.file) to true }
        }
        val bmp = loaded?.first
        if (bmp != null) {
            PhotoViewer(bmp, onDismiss = { viewing = null }, description = "Part photo", onDelete = ::delete)
        } else if (loaded != null) {
            AlertDialog(
                onDismissRequest = { viewing = null },
                title = { Text("Photo missing") },
                text = { Text("This photo's file isn't on the phone any more (photos aren't in Android cloud backups).") },
                confirmButton = { TextButton(onClick = ::delete) { Text("Remove it") } },
                dismissButton = { TextButton(onClick = { viewing = null }) { Text("Cancel") } },
            )
        }
    }
}

class PhotoPickers(val camera: () -> Unit, val gallery: () -> Unit)

/**
 * Camera and gallery launchers for adding a photo. The camera goes through the system camera app (it needs the
 * camera permission, which the scan screen already asks for). [onPicked] gets a URI to copy from.
 */
@Composable
fun rememberPhotoPickers(onPicked: (Uri?) -> Unit, onError: (String) -> Unit): PhotoPickers {
    val context = LocalContext.current
    val shot = remember {
        val f = File(File(context.cacheDir, "camera").apply { mkdirs() }, "shot.jpg")
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> onPicked(uri) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) onPicked(shot) }
    fun startCamera() {
        try {
            camera.launch(shot)
        } catch (e: Exception) {
            onError("No camera app available")
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else onError("Camera permission is needed to take a photo")
    }
    return PhotoPickers(
        camera = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
            else permission.launch(Manifest.permission.CAMERA)
        },
        gallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
    )
}
