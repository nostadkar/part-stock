package com.partstock.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import android.annotation.SuppressLint
import android.view.MotionEvent
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.partstock.scanner.parse.OcrLine
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    partCount: Int,
    onInventory: () -> Unit,
    onSettings: () -> Unit,
    /**
     * One list of OCR lines per photo (the camera sends a short burst; a picked photo sends one), plus one of the
     * photos so the review screen can show what was read.
     */
    onFrames: (List<List<OcrLine>>, Bitmap?) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCamera = it
    }
    LaunchedEffect(Unit) { if (!hasCamera) permissionLauncher.launch(Manifest.permission.CAMERA) }

    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    var progress by remember { mutableStateOf<String?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    val lines = OcrEngine.recognize(InputImage.fromFilePath(context, uri))
                    onFrames(listOf(lines), loadPhoto(context, uri))
                } catch (e: Exception) {
                    onError("Couldn't read photo: ${e.message}")
                } finally {
                    busy = false
                }
            }
        }
    }

    fun capture() {
        busy = true
        scope.launch {
            try {
                // Focus and expose on the label box first: a soft photo is the main cause of misreads.
                val cam = camera
                val view = previewView
                if (cam != null && view != null) focusAt(cam, view, view.width / 2f, view.height / 2f)
                // Read a short burst and let the frames vote on each field, so one slip doesn't stick.
                val frames = mutableListOf<List<OcrLine>>()
                var photo: Bitmap? = null
                for (i in 1..BURST_FRAMES) {
                    progress = "Reading $i/$BURST_FRAMES… hold still"
                    // Keep the last photo of the burst for the review screen.
                    val f = captureAndRead(imageCapture, context, keepPhoto = i == BURST_FRAMES)
                    frames.add(f.lines)
                    if (f.photo != null) photo = f.photo
                }
                onFrames(frames, photo)
            } catch (e: Exception) {
                onError("Capture failed: ${e.message}")
            } finally {
                progress = null
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Part Stock") },
                actions = {
                    AssistChip(
                        onClick = onInventory,
                        label = { Text("Inventory ($partCount)") },
                        leadingIcon = { Icon(Icons.Filled.Inventory2, contentDescription = null) },
                    )
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (hasCamera) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).also { view ->
                            previewView = view
                            // Tap to focus on a spot (e.g. the part number line).
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
                                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                                )
                            }, ContextCompat.getMainExecutor(ctx))
                        }
                    },
                )
                // Framing guide
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.88f)
                        .aspectRatio(1.3f)
                        .border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                )
                progress?.let {
                    Text(
                        it,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                Text(
                    "Fit the label in the box and hold still. Tap to focus. Tilt the bag a little if the foil glares.",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
                )
            } else {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Camera permission is needed to read labels. You can also pick a photo.", textAlign = TextAlign.Center)
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Allow camera")
                    }
                }
            }

            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 28.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !busy,
                    modifier = Modifier.size(56.dp),
                ) { Icon(Icons.Filled.PhotoLibrary, contentDescription = "Pick photo") }

                FilledIconButton(
                    onClick = ::capture,
                    enabled = hasCamera && !busy,
                    modifier = Modifier.size(80.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.onPrimary)
                    else Icon(Icons.Filled.CameraAlt, contentDescription = "Read label", modifier = Modifier.size(36.dp))
                }

                FilledTonalIconButton(
                    onClick = {
                        torch = !torch
                        camera?.cameraControl?.enableTorch(torch)
                    },
                    enabled = hasCamera,
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(if (torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff, contentDescription = "Light")
                }
            }
        }
    }
}

private const val BURST_FRAMES = 3

