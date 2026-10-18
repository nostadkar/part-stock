package com.partstock.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.camera.core.Camera
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.common.InputImage
import com.partstock.scanner.parse.OcrLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One photo read by OCR: its lines, the upright image size, optionally a downscaled copy of the photo, and the raw
 * values of any barcodes found on it (only when asked for).
 */
class CapturedFrame(
    val lines: List<OcrLine>,
    val width: Int,
    val height: Int,
    val photo: Bitmap?,
    val codes: List<String> = emptyList(),
)

/** Longest side of the photo kept for the review screen (enough to zoom into a label, small in memory). */
private const val PHOTO_MAX_SIDE = 2000

/** Focuses and meters on a point of the preview; gives up quietly after a moment. */
internal suspend fun focusAt(camera: Camera, view: PreviewView, x: Float, y: Float) {
    val point = view.meteringPointFactory.createPoint(x, y)
    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
        .setAutoCancelDuration(4, TimeUnit.SECONDS)
        .build()
    try {
        withTimeoutOrNull(1500) { camera.cameraControl.startFocusAndMetering(action).awaitResult() }
    } catch (e: Exception) {
        // Focus not supported or cancelled by a newer request: capture anyway.
    }
}

internal suspend fun <T> ListenableFuture<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addListener({
        try {
            cont.resume(get())
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
    }, Runnable::run)
}

/**
 * Takes one photo and runs OCR on it; with [keepPhoto], also returns an upright, downscaled copy; with
 * [readBarcodes], also reads the barcodes on the same photo.
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
internal suspend fun captureAndRead(
    imageCapture: ImageCapture,
    context: Context,
    keepPhoto: Boolean = false,
    readBarcodes: Boolean = false,
): CapturedFrame {
    val proxy = suspendCancellableCoroutine<ImageProxy> { cont ->
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    if (cont.isActive) cont.resume(image) else image.close()
                }

                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            },
        )
    }
    try {
        val rotation = proxy.imageInfo.rotationDegrees
        val media = proxy.image ?: error("Camera returned no image")
        val input = InputImage.fromMediaImage(media, rotation)
        val lines = OcrEngine.recognize(input)
        val codes = if (readBarcodes) BarcodeEngine.scan(input) else emptyList()
        val sideways = rotation == 90 || rotation == 270
        val w = if (sideways) proxy.height else proxy.width
        val h = if (sideways) proxy.width else proxy.height
        val photo = if (keepPhoto) {
            try {
                withContext(Dispatchers.Default) { uprightScaled(proxy.toBitmap(), rotation) }
            } catch (e: Exception) {
                null // the photo is only for comparing; the reading still counts
            }
        } else null
        return CapturedFrame(lines, w, h, photo, codes)
    } finally {
        proxy.close()
    }
}

private fun uprightScaled(src: Bitmap, rotation: Int): Bitmap {
    val scale = minOf(1f, PHOTO_MAX_SIDE.toFloat() / maxOf(src.width, src.height))
    if (rotation == 0 && scale >= 1f) return src
    val m = Matrix().apply {
        postScale(scale, scale)
        postRotate(rotation.toFloat())
    }
    val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    if (out !== src) src.recycle()
    return out
}

/** A picked photo for the review screen, upright (Android 9+) and downscaled; null if it can't be decoded. */
internal suspend fun loadPhoto(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                var sample = 1
                while (longest / (sample * 2) >= PHOTO_MAX_SIDE) sample *= 2
                decoder.setTargetSampleSize(sample)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PHOTO_MAX_SIDE) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        }
    } catch (e: Exception) {
        null
    }
}
