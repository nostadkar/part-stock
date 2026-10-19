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
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
     * photos so the review screen can show what was read, plus the raw values of any barcodes found on them.
     */
    onFrames: (List<List<OcrLine>>, Bitmap?, List<String>) -> Unit,
    /** Read the label's barcodes too (Settings → Read label barcodes). */
    readBarcodes: Boolean = true,
    onError: (String) -> Unit,
    /** Batch mode: bags queue in [batchTray] instead of opening the review screen (MainActivity decides). */
    batchMode: Boolean = false,
    onBatchMode: (Boolean) -> Unit = {},
    /** Extra controls next to the Batch chip while batch mode is on (the bin). */
    batchTop: @Composable () -> Unit = {},
    /** The queue, shown above the shutter in batch mode; [reading] = a capture is being read right now. */
    batchTray: @Composable (reading: Boolean) -> Unit = {},
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
                    val input = InputImage.fromFilePath(context, uri)
                    val lines = OcrEngine.recognize(input)
                    val codes = if (readBarcodes) BarcodeEngine.scan(input) else emptyList()
                    onFrames(listOf(lines), loadPhoto(context, uri), codes)
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
                val codes = mutableListOf<String>()
                for (i in 1..BURST_FRAMES) {
                    progress = "Reading $i/$BURST_FRAMES… hold still"
                    // Keep the last photo of the burst for the review screen.
                    val f = captureAndRead(
                        imageCapture, context, keepPhoto = i == BURST_FRAMES, readBarcodes = readBarcodes,
                    )
                    frames.add(f.lines)
                    codes.addAll(f.codes)
                    if (f.photo != null) photo = f.photo
                }
                onFrames(frames, photo, codes)
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
                        // In batch mode the queue fills the bottom, so the guide moves up.
                        .align(if (batchMode) Alignment.TopCenter else Alignment.Center)
                        .padding(top = if (batchMode) 96.dp else 0.dp)
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

            Column(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = batchMode,
                        onClick = { onBatchMode(!batchMode) },
                        label = { Text("Batch mode") },
                        leadingIcon = if (batchMode) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        ),
                    )
                    if (batchMode) batchTop()
                }
                if (hasCamera) {
                    Text(
                        if (batchMode) "Fit each label in the box and tap the shutter; keep going with the next bag."
                        else "Fit the label in the box and hold still. Tap to focus. Tilt the bag a little if the foil glares.",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (batchMode) batchTray(busy)
                Row(
                    Modifier.fillMaxWidth().padding(bottom = if (batchMode) 12.dp else 28.dp),
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
}

private const val BURST_FRAMES = 3

