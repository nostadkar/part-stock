package com.partstock.scanner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen viewer over all of a part's photos: swipe between them, "2 / 4" at the top, the thumbnails along the
 * bottom (tap one to jump). Pinch to zoom, double-tap to zoom in or reset; swiping to the next photo works only while
 * the current one isn't zoomed. Delete (tap twice) removes the photo on screen.
 */
@Composable
fun PartPhotoPager(
    photos: List<PartPhoto>,
    start: Int,
    onDismiss: () -> Unit,
    onDelete: (PartPhoto) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    val pager = rememberPagerState(initialPage = start) { photos.size }
    var zoomed by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage) {
        confirmDelete = false
        zoomed = false
    }
    val current = pager.currentPage.coerceIn(0, (photos.size - 1).coerceAtLeast(0))

    fun jumpTo(i: Int) {
        scope.launch { if (reduceMotion) pager.scrollToPage(i) else pager.animateScrollToPage(i) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pager,
                key = { photos.getOrNull(it)?.id ?: -it.toLong() },
                userScrollEnabled = !zoomed,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val photo = photos.getOrNull(page) ?: return@HorizontalPager
                // null = still loading; (null, true) = the file is missing (e.g. after restoring from a cloud backup).
                val loaded by produceState<Pair<ImageBitmap?, Boolean>?>(null, photo.file) {
                    value = withContext(Dispatchers.IO) { PartPhotos.full(context, photo.file)?.asImageBitmap() } to true
                }
                val bmp = loaded?.first
                when {
                    bmp != null -> ZoomablePhoto(
                        bmp,
                        description = "Part photo ${page + 1} of ${photos.size}",
                        active = page == pager.currentPage,
                        onZoomed = { if (page == pager.currentPage) zoomed = it },
                    )
                    loaded != null -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "This photo's file isn't on the phone any more (photos aren't in Android cloud backups). " +
                                "Delete removes it from the part.",
                            color = Color.White, textAlign = TextAlign.Center,
                        )
                    }
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }

            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (photos.size > 1) {
                    Text(
                        "${current + 1} / ${photos.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Box(Modifier.weight(1f))
                TextButton(onClick = {
                    if (confirmDelete) {
                        confirmDelete = false
                        photos.getOrNull(current)?.let(onDelete)
                    } else {
                        confirmDelete = true
                    }
                }) {
                    Text(if (confirmDelete) "Tap again to delete" else "Delete", color = Color.White)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
            }

            if (photos.size > 1) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .navigationBarsPadding()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        photos.forEachIndexed { i, p ->
                            val selected = i == current
                            PhotoThumb(
                                p.file, 56.dp,
                                modifier = Modifier.border(
                                    BorderStroke(if (selected) 2.dp else 0.dp, if (selected) Color.White else Color.Transparent),
                                    RoundedCornerShape(6.dp),
                                ),
                                onClick = { jumpTo(i) },
                                contentDescription = "Photo ${i + 1} of ${photos.size}" + if (selected) ", showing" else "",
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One photo with pinch-zoom, pan while zoomed and double-tap zoom/reset (as in the single-photo viewer). A one-finger
 * drag at normal size is left alone, so the pager can swipe. Leaving the page ([active] false) resets the zoom.
 */
@Composable
private fun ZoomablePhoto(image: ImageBitmap, description: String, active: Boolean, onZoomed: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(active) {
        if (!active) {
            scale = 1f
            offset = Offset.Zero
        }
    }
    LaunchedEffect(scale > 1f) { onZoomed(scale > 1f) }
    Image(
        image,
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers > 1 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 8f)
                            offset = if (scale == 1f) Offset.Zero else offset + event.calculatePan()
                            // Taken here, so the pager doesn't also swipe while zooming or panning.
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
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
}
