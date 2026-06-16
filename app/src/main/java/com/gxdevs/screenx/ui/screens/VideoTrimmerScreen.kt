package com.gxdevs.screenx.ui.screens

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.gxdevs.screenx.utils.RecordedVideo
import com.gxdevs.screenx.utils.VideoHelper
import com.gxdevs.screenx.utils.VideoTrimmer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

// ─── Edit Mode Enum ──────────────────────────────────────────────────────────

enum class EditMode { TRIM, DELETE, SPLIT }

// ─── Screen root ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoTrimmerScreen(
    initialVideo: RecordedVideo?,
    videos: List<RecordedVideo>,
    onBackClick: () -> Unit,
    onTrimSuccess: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedUri by remember { mutableStateOf<Uri?>(initialVideo?.uri) }
    var videoName by remember { mutableStateOf(initialVideo?.name ?: "") }
    var videoDurationMs by remember { mutableStateOf(initialVideo?.duration ?: 0L) }

    val systemVideoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
            videoName = "External_Video_${System.currentTimeMillis()}.mp4"
            coroutineScope.launch(Dispatchers.IO) {
                val dur = queryVideoDuration(context, uri)
                withContext(Dispatchers.Main) { videoDurationMs = dur }
            }
        }
    }

    if (selectedUri == null) {
        // ── Video picker ──────────────────────────────────────────────────────
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Select Video to Edit", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background)
                    .navigationBarsPadding()
                    .padding(16.dp)
            ) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .clickable { systemVideoPickerLauncher.launch("video/*") }
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.FolderOpen, null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(
                                    "Choose from Device",
                                    fontWeight = FontWeight.Bold, fontSize = 18.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    "Browse files from internal storage",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                Text("Recent Captures", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))

                if (videos.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No recordings available", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(videos) { video ->
                            RecentSelectCard(video = video, onClick = {
                                selectedUri = video.uri
                                videoName = video.name
                                videoDurationMs = video.duration
                            })
                        }
                    }
                }
            }
        }
    } else {
        TrimmerEditor(
            videoUri = selectedUri!!,
            videoName = videoName,
            videoDurationMs = videoDurationMs,
            onBackClick = { selectedUri = null },
            onTrimSuccess = onTrimSuccess
        )
    }
}

// ─── Editor ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimmerEditor(
    videoUri: Uri,
    videoName: String,
    videoDurationMs: Long,
    onBackClick: () -> Unit,
    onTrimSuccess: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    // ── State ─────────────────────────────────────────────────────────────────
    var editMode by remember { mutableStateOf(EditMode.TRIM) }

    // Selection range [0..1]
    var startFrac by remember { mutableStateOf(0f) }
    var endFrac   by remember { mutableStateOf(1f) }
    // Playhead fraction
    var playFrac  by remember { mutableStateOf(0f) }

    // True while the user's finger is dragging on the timeline
    // Prevents the position-tracking loop from fighting the drag writes
    var isDragging by remember { mutableStateOf(false) }

    var currentPosMs by remember { mutableStateOf(0L) }
    var isProcessing by remember { mutableStateOf(false) }
    var processingProgress by remember { mutableStateOf(0f) }
    var processingLabel by remember { mutableStateOf("") }

    val startMs = (startFrac * videoDurationMs).toLong()
    val endMs   = (endFrac   * videoDurationMs).toLong()
    val splitMs = (playFrac  * videoDurationMs).toLong()

    // ── ExoPlayer ────────────────────────────────────────────────────────────
    val exoPlayer = remember(videoUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            prepare()
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = false
        }
    }

    var isPlaying by remember { mutableStateOf(false) }
    LaunchedEffect(exoPlayer) {
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        })
    }

    // Seek to start of selection on mode/range change
    LaunchedEffect(editMode, startMs) {
        if (editMode == EditMode.TRIM) exoPlayer.seekTo(startMs)
    }

    // Position tracking + loop-within-selection
    // Skips playFrac update while isDragging to prevent jitter
    LaunchedEffect(exoPlayer, editMode, startMs, endMs) {
        while (true) {
            delay(33)
            val pos = exoPlayer.currentPosition
            currentPosMs = pos
            // Only sync playFrac from player when not dragging
            if (!isDragging) {
                playFrac = if (videoDurationMs > 0)
                    (pos.toFloat() / videoDurationMs).coerceIn(0f, 1f) else 0f
            }

            if (exoPlayer.isPlaying) {
                when (editMode) {
                    EditMode.TRIM -> {
                        if (pos >= endMs) exoPlayer.seekTo(startMs)
                        else if (pos < startMs) exoPlayer.seekTo(startMs)
                    }
                    EditMode.DELETE -> {
                        if (pos in startMs until endMs) exoPlayer.seekTo(endMs)
                    }
                    EditMode.SPLIT -> { /* free play */ }
                }
            }
        }
    }

    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    // ── Thumbnails ────────────────────────────────────────────────────────────
    val thumbnailCount = 12
    var thumbnails by remember(videoUri) { mutableStateOf<List<Bitmap?>>(List(thumbnailCount) { null }) }
    LaunchedEffect(videoUri, videoDurationMs) {
        if (videoDurationMs <= 0) return@LaunchedEffect
        // Recycle previous bitmaps before loading new ones to prevent OOM
        val old = thumbnails
        thumbnails = withContext(Dispatchers.IO) {
            old.forEach { it?.recycle() }
            loadFrameThumbnails(context, videoUri, videoDurationMs, thumbnailCount)
        }
    }

    // ── Timeline layout width (pixels → fraction) ─────────────────────────────
    var timelineWidthPx by remember { mutableStateOf(0f) }

    // ── Scaffold ──────────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (editMode) {
                            EditMode.TRIM   -> "Trim Video"
                            EditMode.DELETE -> "Delete Segment"
                            EditMode.SPLIT  -> "Split Video"
                        },
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    // Disabled while processing to prevent nav into invalid state
                    IconButton(onClick = onBackClick, enabled = !isProcessing) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Save / Execute button
                    Button(
                        onClick = {
                            when (editMode) {
                                EditMode.TRIM   -> executeTrim(context, coroutineScope, videoUri, videoName, startMs, endMs, { isProcessing = it }, { processingProgress = it }, { processingLabel = it }, onTrimSuccess)
                                EditMode.DELETE -> executeDelete(context, coroutineScope, videoUri, videoName, videoDurationMs, startMs, endMs, { isProcessing = it }, { processingProgress = it }, { processingLabel = it }, onTrimSuccess)
                                EditMode.SPLIT  -> executeSplit(context, coroutineScope, videoUri, videoName, videoDurationMs, splitMs, { isProcessing = it }, { processingProgress = it }, { processingLabel = it }, onTrimSuccess)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        enabled = !isProcessing
                    ) {
                        Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when (editMode) {
                                EditMode.TRIM   -> "Save Trim"
                                EditMode.DELETE -> "Save Delete"
                                EditMode.SPLIT  -> "Split & Save"
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0D0D0F)
                )
            )
        },
        containerColor = Color(0xFF0D0D0F)
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .background(Color(0xFF0D0D0F)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // ── Video player ──────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.4f)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Playback controls overlay
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                            )
                        )
                        .padding(bottom = 12.dp, top = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Timestamp
                    Text(
                        formatTime(currentPosMs),
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(end = 16.dp)
                    )

                    IconButton(
                        onClick = { exoPlayer.seekTo((currentPosMs - 5000).coerceAtLeast(0)) }
                    ) {
                        Icon(Icons.Default.Replay5, "-5s", tint = Color.White)
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() },
                        modifier = Modifier
                            .size(52.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            "Play/Pause", tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { exoPlayer.seekTo((currentPosMs + 5000).coerceAtMost(videoDurationMs)) }
                    ) {
                        Icon(Icons.Default.Forward5, "+5s", tint = Color.White)
                    }

                    Text(
                        formatTime(videoDurationMs),
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }
            }

            // ── Mode selector tabs ────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .background(Color(0xFF1A1A1F), RoundedCornerShape(14.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                EditMode.entries.forEach { mode ->
                    val selected = editMode == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else Color.Transparent
                            )
                            .clickable { editMode = mode }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = when (mode) {
                                    EditMode.TRIM   -> Icons.Default.Crop
                                    EditMode.DELETE -> Icons.Default.DeleteSweep
                                    EditMode.SPLIT  -> Icons.Default.ContentCut
                                },
                                contentDescription = null,
                                tint = if (selected) Color.White else Color.Gray,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = when (mode) {
                                    EditMode.TRIM   -> "Trim"
                                    EditMode.DELETE -> "Delete"
                                    EditMode.SPLIT  -> "Split"
                                },
                                color = if (selected) Color.White else Color.Gray,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── Timeline ──────────────────────────────────────────────────
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1F)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {

                    // Info row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        when (editMode) {
                            EditMode.TRIM -> {
                                InfoChip("In", formatTime(startMs), MaterialTheme.colorScheme.primary)
                                InfoChip("Duration", formatTime(endMs - startMs), Color(0xFF4FC3F7))
                                InfoChip("Out", formatTime(endMs), MaterialTheme.colorScheme.secondary)
                            }
                            EditMode.DELETE -> {
                                InfoChip("Del Start", formatTime(startMs), Color(0xFFEF5350))
                                InfoChip("Removed", formatTime(endMs - startMs), Color(0xFFFF8A65))
                                InfoChip("Del End", formatTime(endMs), Color(0xFFEF5350))
                            }
                            EditMode.SPLIT -> {
                                InfoChip("Part A", formatTime(splitMs), MaterialTheme.colorScheme.primary)
                                InfoChip("Split At", formatTime(splitMs), Color(0xFFFFD54F))
                                InfoChip("Part B", formatTime(videoDurationMs - splitMs), MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // ── Frame strip + handles ─────────────────────────────
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .onGloballyPositioned { coords ->
                                timelineWidthPx = coords.size.width.toFloat()
                            }
                    ) {
                        // Frame thumbnails
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp))
                        ) {
                            thumbnails.forEach { bmp ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .background(Color(0xFF2C2C2E))
                                ) {
                                    if (bmp != null) {
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }
                        }

                        // Dimmed overlay outside selection
                        if (editMode == EditMode.TRIM || editMode == EditMode.DELETE) {
                            val dimLeft  = editMode == EditMode.TRIM
                            val dimRight = editMode == EditMode.TRIM
                            val dimMid   = editMode == EditMode.DELETE

                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val W = size.width
                                val H = size.height
                                val sX = startFrac * W
                                val eX = endFrac * W

                                if (dimLeft && sX > 0) {
                                    drawRect(
                                        color = Color(0xAA000000),
                                        topLeft = Offset(0f, 0f),
                                        size = GeoSize(sX, H)
                                    )
                                }
                                if (dimRight && eX < W) {
                                    drawRect(
                                        color = Color(0xAA000000),
                                        topLeft = Offset(eX, 0f),
                                        size = GeoSize(W - eX, H)
                                    )
                                }
                                if (dimMid) {
                                    drawRect(
                                        color = Color(0x88EF5350),
                                        topLeft = Offset(sX, 0f),
                                        size = GeoSize(eX - sX, H)
                                    )
                                }
                            }
                        }

                        // Selection border
                        if (editMode != EditMode.SPLIT) {
                            val borderColor = when (editMode) {
                                EditMode.DELETE -> Color(0xFFEF5350)
                                else -> MaterialTheme.colorScheme.primary
                            }
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val W = size.width
                                val H = size.height
                                val sX = startFrac * W
                                val eX = endFrac * W
                                drawRect(
                                    color = borderColor,
                                    topLeft = Offset(sX, 0f),
                                    size = GeoSize(eX - sX, H),
                                    style = Stroke(width = 3f)
                                )
                            }
                        }

                        // Playhead / split line
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val x = playFrac * size.width
                            val color = if (editMode == EditMode.SPLIT) Color(0xFFFFD54F) else Color.White
                            drawLine(
                                color = color,
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = 3f
                            )
                            drawCircle(color = color, radius = 6f, center = Offset(x, 0f))
                        }

                        // Drag target — latches which handle was grabbed at touch-down
                        // to prevent handles jumping around during a slow drag
                        val handleTouchPx = with(density) { 36.dp.toPx() }
                        // 0 = scrub, 1 = start handle, 2 = end handle
                        var dragTarget by remember { mutableStateOf(0) }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(editMode, timelineWidthPx, videoDurationMs) {
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            if (timelineWidthPx <= 0f) return@detectDragGestures
                                            isDragging = true
                                            val sX = startFrac * timelineWidthPx
                                            val eX = endFrac   * timelineWidthPx
                                            dragTarget = when {
                                                editMode == EditMode.SPLIT -> 0
                                                abs(offset.x - sX) <= handleTouchPx -> 1
                                                abs(offset.x - eX) <= handleTouchPx -> 2
                                                else -> 0
                                            }
                                        },
                                        onDragEnd   = { isDragging = false },
                                        onDragCancel = { isDragging = false },
                                        onDrag = { change, _ ->
                                            if (timelineWidthPx <= 0f) return@detectDragGestures
                                            val x = change.position.x.coerceIn(0f, timelineWidthPx)
                                            val frac = x / timelineWidthPx

                                            when {
                                                dragTarget == 1 -> { // start handle
                                                    startFrac = frac.coerceIn(0f, endFrac - 0.01f)
                                                    exoPlayer.seekTo((startFrac * videoDurationMs).toLong())
                                                }
                                                dragTarget == 2 -> { // end handle
                                                    endFrac = frac.coerceIn(startFrac + 0.01f, 1f)
                                                    exoPlayer.seekTo((endFrac * videoDurationMs).toLong())
                                                }
                                                else -> { // scrub / split
                                                    playFrac = frac.coerceIn(0f, 1f)
                                                    exoPlayer.seekTo((frac * videoDurationMs).toLong())
                                                }
                                            }
                                            change.consume()
                                        }
                                    )
                                }
                                .pointerInput(editMode, timelineWidthPx, videoDurationMs) {
                                    detectTapGestures { offset ->
                                        if (timelineWidthPx <= 0f) return@detectTapGestures
                                        val frac = (offset.x / timelineWidthPx).coerceIn(0f, 1f)
                                        playFrac = frac
                                        exoPlayer.seekTo((frac * videoDurationMs).toLong())
                                    }
                                }
                        )

                        // Handle visuals
                        if (editMode == EditMode.TRIM || editMode == EditMode.DELETE) {
                            val hColor = if (editMode == EditMode.DELETE) Color(0xFFEF5350) else MaterialTheme.colorScheme.primary
                            // Left handle
                            Box(
                                modifier = Modifier
                                    .offset(x = with(density) { (startFrac * timelineWidthPx - 14f).coerceAtLeast(0f).toDp() })
                                    .width(28.dp)
                                    .fillMaxHeight()
                                    .background(hColor.copy(alpha = 0.9f), RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("◁", color = Color.White, fontSize = 14.sp)
                            }
                            // Right handle
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset(x = with(density) { (endFrac * timelineWidthPx - 14f).coerceIn(0f, (timelineWidthPx - 28f).coerceAtLeast(0f)).toDp() })
                                    .width(28.dp)
                                    .fillMaxHeight()
                                    .background(hColor.copy(alpha = 0.9f), RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("▷", color = Color.White, fontSize = 14.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Time ruler tick marks
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { f ->
                            Text(
                                formatTimeShort((f * videoDurationMs).toLong()),
                                color = Color.Gray,
                                fontSize = 9.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // ── Fine adjustment controls ───────────────────────────
                    when (editMode) {
                        EditMode.TRIM, EditMode.DELETE -> {
                            val label1 = if (editMode == EditMode.TRIM) "In Point" else "Del Start"
                            val label2 = if (editMode == EditMode.TRIM) "Out Point" else "Del End"
                            val accent  = if (editMode == EditMode.DELETE) Color(0xFFEF5350) else MaterialTheme.colorScheme.primary

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FineAdjustColumn(
                                    label = label1,
                                    value = formatTime(startMs),
                                    accent = accent,
                                    modifier = Modifier.weight(1f),
                                    // Guard: videoDurationMs==0 would divide by zero
                                    onMinus = { if (videoDurationMs > 0) startFrac = (startFrac - 100f / videoDurationMs).coerceIn(0f, endFrac - 0.001f) },
                                    onPlus  = { if (videoDurationMs > 0) startFrac = (startFrac + 100f / videoDurationMs).coerceIn(0f, endFrac - 0.001f) }
                                )
                                FineAdjustColumn(
                                    label = label2,
                                    value = formatTime(endMs),
                                    accent = accent,
                                    modifier = Modifier.weight(1f),
                                    onMinus = { if (videoDurationMs > 0) endFrac = (endFrac - 100f / videoDurationMs).coerceIn(startFrac + 0.001f, 1f) },
                                    onPlus  = { if (videoDurationMs > 0) endFrac = (endFrac + 100f / videoDurationMs).coerceIn(startFrac + 0.001f, 1f) }
                                )
                            }
                        }
                        EditMode.SPLIT -> {
                            // Quick split position controls
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "Drag timeline or use fine controls to set split point",
                                    color = Color.Gray, fontSize = 11.sp,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(8.dp))
                                FineAdjustColumn(
                                    label = "Split Point",
                                    value = formatTime(splitMs),
                                    accent = Color(0xFFFFD54F),
                                    modifier = Modifier.fillMaxWidth(0.6f),
                                    onMinus = {
                                        val newMs = (splitMs - 100L).coerceAtLeast(0L)
                                        playFrac = newMs.toFloat() / videoDurationMs
                                        exoPlayer.seekTo(newMs)
                                    },
                                    onPlus = {
                                        val newMs = (splitMs + 100L).coerceAtMost(videoDurationMs)
                                        playFrac = newMs.toFloat() / videoDurationMs
                                        exoPlayer.seekTo(newMs)
                                    }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // Help text
                    Text(
                        text = when (editMode) {
                            EditMode.TRIM   -> "Drag handles or tap timeline to set In/Out points. Only the selected region is saved."
                            EditMode.DELETE -> "Drag handles to mark the segment to remove. Both sides are joined together."
                            EditMode.SPLIT  -> "Set the split point. Two separate video files will be saved."
                        },
                        color = Color.Gray,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }

    // ── Processing overlay ────────────────────────────────────────────────────
    if (isProcessing) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1F)),
                modifier = Modifier.fillMaxWidth(0.85f)
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(52.dp),
                        strokeWidth = 4.dp
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(processingLabel, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { processingProgress },
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.DarkGray,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${(processingProgress * 100).toInt()}%",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Please keep ScreenX open",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// ─── Small composable helpers ─────────────────────────────────────────────────

@Composable
private fun InfoChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = Color.Gray)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun FineAdjustColumn(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = Color.Gray)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accent)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallAdjustBtn("-0.1s", onMinus)
            SmallAdjustBtn("+0.1s", onPlus)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallAdjustBtn("-1s") {
                repeat(10) { onMinus() }
            }
            SmallAdjustBtn("+1s") {
                repeat(10) { onPlus() }
            }
        }
    }
}

@Composable
private fun SmallAdjustBtn(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
        modifier = Modifier.height(26.dp)
    ) {
        Text(text, fontSize = 9.sp, color = Color.White)
    }
}

// ─── Video editing operations ─────────────────────────────────────────────────

/** Throttled progress helper — only dispatches when delta > 1% to avoid coroutine floods. */
private fun makeProgressCallback(
    scope: kotlinx.coroutines.CoroutineScope,
    setProgress: (Float) -> Unit
): (Float) -> Unit {
    var lastReported = -1f
    return { p ->
        if (p - lastReported >= 0.01f || p >= 1f) {
            lastReported = p
            scope.launch(Dispatchers.Main) { setProgress(p) }
        }
    }
}

private fun executeTrim(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    sourceUri: Uri,
    videoName: String,
    startMs: Long,
    endMs: Long,
    setProcessing: (Boolean) -> Unit,
    setProgress: (Float) -> Unit,
    setLabel: (String) -> Unit,
    onSuccess: () -> Unit
) {
    if (endMs <= startMs + 500L) {
        Toast.makeText(context, "Selection must be at least 0.5 seconds", Toast.LENGTH_SHORT).show()
        return
    }
    setProcessing(true)
    setLabel("Trimming Video…")
    val progressCb = makeProgressCallback(scope, setProgress)
    scope.launch(Dispatchers.IO) {
        var tempFile: File? = null
        try {
            tempFile = File(context.cacheDir, "trim_${System.currentTimeMillis()}.mp4")
            VideoTrimmer.trim(
                context = context,
                sourceUri = sourceUri,
                outputFile = tempFile,
                startMs = startMs,
                endMs = endMs,
                progressCallback = progressCb
            )
            saveVideoToMediaStore(context, tempFile, videoName, endMs - startMs)
            tempFile = null  // saveVideoToMediaStore deletes it
            withContext(Dispatchers.Main) {
                setProcessing(false)
                Toast.makeText(context, "Trimmed video saved!", Toast.LENGTH_SHORT).show()
                onSuccess()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            tempFile?.delete()
            withContext(Dispatchers.Main) {
                setProcessing(false)
                Toast.makeText(context, "Trim failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

private fun executeDelete(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    sourceUri: Uri,
    videoName: String,
    totalDurationMs: Long,
    cutStartMs: Long,
    cutEndMs: Long,
    setProcessing: (Boolean) -> Unit,
    setProgress: (Float) -> Unit,
    setLabel: (String) -> Unit,
    onSuccess: () -> Unit
) {
    if (cutEndMs <= cutStartMs + 500L) {
        Toast.makeText(context, "Delete segment must be at least 0.5 seconds", Toast.LENGTH_SHORT).show()
        return
    }
    setProcessing(true)
    setLabel("Deleting Segment…")
    val progressCb = makeProgressCallback(scope, setProgress)
    scope.launch(Dispatchers.IO) {
        var tempFile: File? = null
        try {
            tempFile = File(context.cacheDir, "delete_${System.currentTimeMillis()}.mp4")
            VideoTrimmer.cutMiddle(
                context = context,
                sourceUri = sourceUri,
                outputFile = tempFile,
                cutStartMs = cutStartMs,
                cutEndMs = cutEndMs,
                progressCallback = progressCb
            )
            val outDuration = (totalDurationMs - (cutEndMs - cutStartMs)).coerceAtLeast(0L)
            saveVideoToMediaStore(context, tempFile, videoName, outDuration)
            tempFile = null
            withContext(Dispatchers.Main) {
                setProcessing(false)
                Toast.makeText(context, "Segment deleted & saved!", Toast.LENGTH_SHORT).show()
                onSuccess()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            tempFile?.delete()
            withContext(Dispatchers.Main) {
                setProcessing(false)
                Toast.makeText(context, "Delete failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

private fun executeSplit(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    sourceUri: Uri,
    videoName: String,
    totalDurationMs: Long,
    splitMs: Long,
    setProcessing: (Boolean) -> Unit,
    setProgress: (Float) -> Unit,
    setLabel: (String) -> Unit,
    onSuccess: () -> Unit
) {
    if (splitMs < 500L) {
        Toast.makeText(context, "Split point must be at least 0.5s from start", Toast.LENGTH_SHORT).show()
        return
    }
    // Guard: Part B would be empty if split is at the very end
    if (totalDurationMs > 0 && splitMs >= totalDurationMs - 500L) {
        Toast.makeText(context, "Split point must be at least 0.5s from end", Toast.LENGTH_SHORT).show()
        return
    }
    setProcessing(true)
    setLabel("Splitting Video…")
    val progressCb = makeProgressCallback(scope, setProgress)
    scope.launch(Dispatchers.IO) {
        val ts   = System.currentTimeMillis()
        val outA = File(context.cacheDir, "splitA_$ts.mp4")
        val outB = File(context.cacheDir, "splitB_${ts + 1}.mp4")
        try {
            VideoTrimmer.split(
                context = context,
                sourceUri = sourceUri,
                outputA = outA,
                outputB = outB,
                splitMs = splitMs,
                progressCallback = progressCb
            )
            val cleanName  = videoName.substringBefore(".mp4")
            val partBDurMs = (totalDurationMs - splitMs).coerceAtLeast(0L)
            saveVideoToMediaStore(context, outA, "${cleanName}_part1.mp4", splitMs)
            outA.takeIf { it.exists() }?.delete()  // deleted by saveVideoToMediaStore; just in case
            saveVideoToMediaStore(context, outB, "${cleanName}_part2.mp4", partBDurMs)
            outB.takeIf { it.exists() }?.delete()
            withContext(Dispatchers.Main) {
                setProcessing(false)
                Toast.makeText(context, "Split into Part 1 & Part 2 — saved!", Toast.LENGTH_SHORT).show()
                onSuccess()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Clean up temp files on failure
            outA.takeIf { it.exists() }?.delete()
            outB.takeIf { it.exists() }?.delete()
            withContext(Dispatchers.Main) {
                setProcessing(false)
                Toast.makeText(context, "Split failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

// ─── Utility functions ────────────────────────────────────────────────────────

private suspend fun loadFrameThumbnails(
    context: Context,
    uri: Uri,
    durationMs: Long,
    count: Int
): List<Bitmap?> {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        (0 until count).map { i ->
            val timeUs = (i.toLong() * durationMs / count) * 1000L
            try {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (_: Exception) { null }
        }
    } catch (_: Exception) {
        List(count) { null }
    } finally {
        retriever.release()
    }
}

private fun formatTime(ms: Long): String {
    if (ms < 0) return "00:00.0"
    val milli = (ms % 1000) / 100
    val sec   = (ms / 1000) % 60
    val min   = (ms / 60000) % 60
    val hour  = ms / 3600000
    return if (hour > 0)
        String.format("%d:%02d:%02d.%d", hour, min, sec, milli)
    else
        String.format("%02d:%02d.%d", min, sec, milli)
}

private fun formatTimeShort(ms: Long): String {
    if (ms < 0) return "0s"
    val sec = ms / 1000
    val min = sec / 60
    return if (min > 0) "${min}m${sec % 60}s" else "${sec}s"
}

private fun queryVideoDuration(context: Context, uri: Uri): Long {
    var duration = 0L
    try {
        context.contentResolver.query(
            uri, arrayOf(MediaStore.Video.Media.DURATION), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION))
            }
        }
    } catch (_: Exception) {}
    if (duration <= 0L) {
        try {
            val r = MediaMetadataRetriever()
            r.setDataSource(context, uri)
            duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            r.release()
        } catch (_: Exception) {}
    }
    return duration
}

private fun saveVideoToMediaStore(
    context: Context,
    tempFile: File,
    originalName: String,
    durationMs: Long
) {
    val cleanName = originalName.substringBefore(".mp4")
    val outName = "${cleanName}_${System.currentTimeMillis()}.mp4"
    val contentValues = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, outName)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenX")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
    }
    val resolver = context.contentResolver
    val videoUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return
    try {
        resolver.openOutputStream(videoUri)?.use { out ->
            tempFile.inputStream().use { it.copyTo(out) }
        }
        val updateValues = ContentValues().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Video.Media.IS_PENDING, 0)
            if (durationMs != Long.MAX_VALUE) put(MediaStore.Video.Media.DURATION, durationMs)
            put(MediaStore.Video.Media.SIZE, tempFile.length())
        }
        resolver.update(videoUri, updateValues, null, null)
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        tempFile.delete()
    }
}

// ─── Recent videos card ───────────────────────────────────────────────────────

@Composable
fun RecentSelectCard(video: RecordedVideo, onClick: () -> Unit) {
    val context = LocalContext.current
    var thumbnail by remember(video.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(video.uri) {
        thumbnail = withContext(Dispatchers.IO) {
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(context, video.uri)
                val bmp = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                r.release()
                bmp
            } catch (_: Exception) { null }
        }
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail!!.asImageBitmap(), null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Default.PlayCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(video.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Duration: ${VideoHelper.formatDuration(video.duration)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
