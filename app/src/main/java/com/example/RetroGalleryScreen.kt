package com.example

import android.content.Intent
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.ui.theme.ChromeHighlight
import com.example.ui.theme.ChromeShadow
import com.example.ui.theme.ClassicBlueDark
import com.example.ui.theme.ClassicGlossBlue
import com.example.ui.theme.RecordCrimson
import com.example.ui.theme.RetroAmberGold
import com.example.ui.theme.RetroBrushedBottom
import com.example.ui.theme.RetroBrushedMid
import com.example.ui.theme.RetroBrushedTop
import com.example.ui.theme.RetroGraphite
import com.example.ui.theme.TextPrimaryLight
import com.example.ui.theme.TextSecondaryMuted

/**
 * Classic iPhone OS 3.1 / iOS 4 Camera Roll grid and swipeable full-screen media viewer.
 */
@Composable
fun RetroGalleryScreen(
    items: List<RetroMediaItem>,
    onBackToCamera: () -> Unit,
    onLoadExif: suspend (RetroMediaItem) -> ExifMetadataSummary,
    onDeleteItem: (RetroMediaItem) -> Unit,
) {
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    // Keep selected index valid if an item is deleted
    LaunchedEffect(items.size) {
        val current = selectedIndex
        if (current != null) {
            if (items.isEmpty()) {
                selectedIndex = null
            } else if (current >= items.size) {
                selectedIndex = items.lastIndex
            }
        }
    }

    if (selectedIndex != null && items.isNotEmpty()) {
        BackHandler { selectedIndex = null }
        RetroMediaPagerViewer(
            items = items,
            initialIndex = selectedIndex ?: 0,
            onBackToGrid = { selectedIndex = null },
            onLoadExif = onLoadExif,
            onDeleteItem = onDeleteItem,
        )
    } else {
        BackHandler { onBackToCamera() }
        RetroCameraRollGrid(
            items = items,
            onBackToCamera = onBackToCamera,
            onSelectIndex = { idx -> selectedIndex = idx },
        )
    }
}

@Composable
private fun RetroCameraRollGrid(
    items: List<RetroMediaItem>,
    onBackToCamera: () -> Unit,
    onSelectIndex: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RetroGraphite),
    ) {
        // Classic glossy metallic iOS 3.1 / iOS 4 top navigation bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF474C54),
                            Color(0xFF282C31),
                            Color(0xFF191B1E),
                        ),
                    ),
                )
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(52.dp)
                .padding(horizontal = 10.dp),
        ) {
            // Glossy beveled "Camera" return pill
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .height(34.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(ClassicGlossBlue, ClassicBlueDark),
                        ),
                    )
                    .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(6.dp))
                    .clickable(onClick = onBackToCamera)
                    .padding(horizontal = 12.dp)
                    .testTag("gallery_back_camera_button"),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = ChromeHighlight,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.gallery_back_camera),
                        style = MaterialTheme.typography.labelLarge,
                        color = ChromeHighlight,
                    )
                }
            }

            Text(
                text = stringResource(R.string.gallery_title),
                style = MaterialTheme.typography.titleLarge,
                color = ChromeHighlight,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Thin etched metallic divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFF0A0B0D)),
        )

        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.verticalGradient(listOf(RetroBrushedTop, RetroBrushedBottom)),
                            )
                            .border(1.dp, ChromeShadow, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PhotoLibrary,
                            contentDescription = null,
                            tint = RetroAmberGold,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.gallery_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimaryLight,
                    )
                    Text(
                        text = stringResource(R.string.gallery_empty_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondaryMuted,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            val photoCount = items.count { !it.isVideo }
            val videoCount = items.count { it.isVideo }

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("camera_roll_grid"),
                contentPadding = PaddingValues(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .background(Color(0xFF1F2226))
                            .clickable { onSelectIndex(index) }
                            .testTag("camera_roll_tile_$index"),
                    ) {
                        AsyncImage(
                            model = item.uri,
                            contentDescription = item.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )

                        // Authentic iOS 3.1/4 bottom video badge strip with camcorder icon + MM:SS
                        if (item.isVideo) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .background(Color(0xAA000000))
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Videocam,
                                    contentDescription = null,
                                    tint = ChromeHighlight,
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    text = formatDurationMs(item.durationMs),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = ChromeHighlight,
                                )
                            }
                        }
                    }
                }
            }

            // Classic Camera Roll item count footer
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(RetroBrushedMid, RetroBrushedBottom)),
                    )
                    .navigationBarsPadding()
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$photoCount Photos, $videoCount Videos",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondaryMuted,
                )
            }
        }
    }
}

@Composable
private fun RetroMediaPagerViewer(
    items: List<RetroMediaItem>,
    initialIndex: Int,
    onBackToGrid: () -> Unit,
    onLoadExif: suspend (RetroMediaItem) -> ExifMetadataSummary,
    onDeleteItem: (RetroMediaItem) -> Unit,
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
        pageCount = { items.size },
    )
    var showHud by remember { mutableStateOf(true) }
    var showExifDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var exifSummary by remember { mutableStateOf<ExifMetadataSummary?>(null) }

    val activeItem = items.getOrNull(pagerState.currentPage)

    LaunchedEffect(activeItem?.id, showExifDialog) {
        if (showExifDialog && activeItem != null) {
            exifSummary = onLoadExif(activeItem)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("viewer_pager"),
        ) { page ->
            val item = items[page]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { showHud = !showHud },
                contentAlignment = Alignment.Center,
            ) {
                if (item.isVideo) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                val controller = MediaController(ctx)
                                controller.setAnchorView(this)
                                setMediaController(controller)
                                setVideoURI(item.uri)
                                setOnPreparedListener { mp ->
                                    mp.isLooping = false
                                    start()
                                }
                            }
                        },
                        update = { videoView ->
                            videoView.setVideoURI(item.uri)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f),
                    )
                } else {
                    AsyncImage(
                        model = item.uri,
                        contentDescription = item.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // Translucent Top HUD
        AnimatedVisibility(
            visible = showHud,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xB8141619))
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            Brush.verticalGradient(listOf(RetroBrushedTop, RetroBrushedBottom)),
                        )
                        .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(6.dp))
                        .clickable(onClick = onBackToGrid)
                        .padding(horizontal = 10.dp)
                        .testTag("viewer_back_button"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.gallery_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = ChromeHighlight,
                    )
                }

                Text(
                    text = "${pagerState.currentPage + 1} of ${items.size}",
                    style = MaterialTheme.typography.titleMedium,
                    color = ChromeHighlight,
                )

                IconButton(
                    onClick = { showExifDialog = true },
                    modifier = Modifier.testTag("viewer_exif_button"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = stringResource(R.string.viewer_exif_title),
                        tint = RetroAmberGold,
                    )
                }
            }
        }

        // Translucent Bottom Toolbar (Share / Delete)
        AnimatedVisibility(
            visible = showHud && activeItem != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xC816181C))
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        activeItem?.let { item ->
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = if (item.isVideo) "video/mp4" else "image/jpeg"
                                putExtra(Intent.EXTRA_STREAM, item.uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                Intent.createChooser(shareIntent, item.displayName),
                            )
                        }
                    },
                    modifier = Modifier.testTag("viewer_share_button"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = stringResource(R.string.viewer_share),
                        tint = ChromeHighlight,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (activeItem?.isVideo == true) Icons.Filled.Videocam else Icons.Filled.CameraAlt,
                        contentDescription = null,
                        tint = TextSecondaryMuted,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (activeItem?.isVideo == true) "640x480 • 2.2 Mbps" else "2048x1536 • iPhone 3GS",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondaryMuted,
                    )
                }

                IconButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.testTag("viewer_delete_button"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.viewer_delete),
                        tint = RecordCrimson,
                    )
                }
            }
        }
    }

    if (showExifDialog && exifSummary != null) {
        val summary = exifSummary!!
        AlertDialog(
            onDismissRequest = { showExifDialog = false },
            containerColor = RetroBrushedMid,
            titleContentColor = ChromeHighlight,
            textContentColor = TextPrimaryLight,
            title = {
                Text(
                    text = stringResource(R.string.viewer_exif_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExifRow("Make", summary.make)
                    ExifRow("Model", summary.model)
                    ExifRow("Software", summary.software)
                    ExifRow("Dimensions", summary.resolution)
                    ExifRow("Exposure", summary.exposure)
                    ExifRow("Aperture", summary.aperture)
                    ExifRow("Captured", summary.dateTaken)
                }
            },
            confirmButton = {
                TextButton(onClick = { showExifDialog = false }) {
                    Text("Done", color = RetroAmberGold, fontWeight = FontWeight.SemiBold)
                }
            },
        )
    }

    if (showDeleteConfirm && activeItem != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = RetroBrushedMid,
            titleContentColor = ChromeHighlight,
            textContentColor = TextSecondaryMuted,
            title = {
                Text(
                    text = stringResource(R.string.viewer_delete_confirm_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.viewer_delete_confirm_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteItem(activeItem)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = RecordCrimson),
                ) {
                    Text(
                        text = stringResource(R.string.viewer_delete_confirm_action),
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.viewer_cancel), color = ChromeHighlight)
                }
            },
        )
    }
}

@Composable
private fun ExifRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = TextSecondaryMuted)
        Text(text = value, style = MaterialTheme.typography.labelSmall, color = ChromeHighlight)
    }
}

private fun formatDurationMs(durationMs: Long): String {
    val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return "%d:%02d".format(mins, secs)
}
