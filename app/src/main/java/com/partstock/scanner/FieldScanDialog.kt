package com.partstock.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.partstock.scanner.parse.FieldReader
import com.partstock.scanner.parse.OcrLine
import kotlinx.coroutines.launch

/**
 * Full-screen camera for re-reading one field: frame just that part of the label in the box and tap the shutter.
 * Returns the OCR lines whose centre is inside the box (mapped from the preview to the photo).
 */
@Composable
fun FieldScanDialog(fieldName: String, onDismiss: () -> Unit, onRead: (List<OcrLine>) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    LaunchedEffect(Unit) { if (!hasCamera) permission.launch(Manifest.permission.CAMERA) }

    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var previewBounds by remember { mutableStateOf<Rect?>(null) }
    var boxBounds by remember { mutableStateOf<Rect?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // Release the camera when the dialog closes; the scan screen binds it again when it comes back.
    DisposableEffect(Unit) {
        onDispose {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    future.get().unbindAll()
                } catch (_: Exception) {
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    fun capture() {
        busy = true
        message = null
        scope.launch {
            try {
                val view = previewView
                val cam = camera
                val box = boxBounds
                val pv = previewBounds
                if (cam != null && view != null && box != null && pv != null) {
                    focusAt(cam, view, box.center.x - pv.left, box.center.y - pv.top)
                }
                val frame = captureAndRead(imageCapture, context)
                val inside = if (box != null && pv != null) linesInBox(frame, box, pv) else frame.lines
                if (inside.isEmpty()) {
                    message = "Nothing read inside the box. Move closer or tilt the bag to cut the glare."
                } else {
                    onRead(inside)
                }
            } catch (e: Exception) {
                message = "Capture failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (hasCamera) {
                AndroidView(
                    modifier = Modifier.fillMaxSize().onGloballyPositioned { previewBounds = it.boundsInRoot() },
                    factory = { ctx ->
                        PreviewView(ctx).also { view ->
                            // TextureView: a SurfaceView can draw behind a dialog window.
                            view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            view.scaleType = PreviewView.ScaleType.FILL_CENTER
                            previewView = view
                            @SuppressLint("ClickableViewAccessibility")
                            view.setOnTouchListener { v, ev ->
                                if (ev.action == MotionEvent.ACTION_UP) {
                                    camera?.let { cam -> scope.launch { focusAt(cam, view, ev.x, ev.y) } }
                                    v.performClick()
                                }
                                true
                            }
                            val future = ProcessCameraProvider.getInstance(ctx)
                            future.addListener({
                                val provider = future.get()
                                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                                provider.unbindAll()
                                camera = provider.bindToLifecycle(
                                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture,
                                )
                            }, ContextCompat.getMainExecutor(ctx))
                        }
                    },
                )
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.9f)
                        .height(if (fieldName == "description") 150.dp else 90.dp)
                        .border(2.dp, Color.White, RoundedCornerShape(10.dp))
                        .onGloballyPositioned { boxBounds = it.boundsInRoot() },
                )
            } else {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Camera permission is needed to re-read a field.", color = Color.White, textAlign = TextAlign.Center)
                    Button(onClick = { permission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Allow camera")
                    }
                }
            }
            Column(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Fit just the $fieldName in the box",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
                }
                message?.let {
                    Text(it, color = Color.White, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
            }
            FilledIconButton(
                onClick = ::capture,
                enabled = hasCamera && !busy && camera != null,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp).size(76.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(30.dp), color = MaterialTheme.colorScheme.onPrimary)
                else Icon(Icons.Filled.CameraAlt, contentDescription = "Read $fieldName", modifier = Modifier.size(34.dp))
            }
        }
    }
}

/**
 * Maps the on-screen box to photo coordinates (the preview fills the view and is centre-cropped, like the photo) and
 * keeps the lines centred inside it, with a little slack above and below.
 */
private fun linesInBox(frame: CapturedFrame, box: Rect, preview: Rect): List<OcrLine> {
    val w = preview.width
    val h = preview.height
    if (w <= 0f || h <= 0f || frame.width <= 0 || frame.height <= 0) return frame.lines
    val s = maxOf(w / frame.width, h / frame.height)
    val dx = (w - frame.width * s) / 2f
    val dy = (h - frame.height * s) / 2f
    val left = (box.left - preview.left - dx) / s
    val right = (box.right - preview.left - dx) / s
    val top = (box.top - preview.top - dy) / s
    val bottom = (box.bottom - preview.top - dy) / s
    val slack = (bottom - top) * 0.15f
    return FieldReader.inBox(frame.lines, left.toDouble(), (top - slack).toDouble(), right.toDouble(), (bottom + slack).toDouble())
}

/** Full-screen photo: pinch to zoom, drag to pan, double-tap to reset, tap the X to close. */
@Composable
fun PhotoViewer(
    photo: Bitmap,
    onDismiss: () -> Unit,
    description: String = "Photo that was read",
    /** Shows a Delete button (tap twice) when set. */
    onDelete: (() -> Unit)? = null,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val image = remember(photo) { photo.asImageBitmap() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(
                image,
                contentDescription = description,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 3f
                            }
                        })
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
            Row(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onDelete != null) {
                    TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                        Text(if (confirmDelete) "Tap again to delete" else "Delete", color = Color.White)
                    }
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
            }
        }
    }
}
