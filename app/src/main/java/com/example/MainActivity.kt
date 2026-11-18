package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraIos
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ui.theme.ChromeBevelBorder
import com.example.ui.theme.ChromeHighlight
import com.example.ui.theme.ChromeLight
import com.example.ui.theme.ChromeMid
import com.example.ui.theme.ChromeShadow
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.PillBorder
import com.example.ui.theme.PillGlossTop
import com.example.ui.theme.PillSurface
import com.example.ui.theme.RecordCrimson
import com.example.ui.theme.RecordCrimsonDark
import com.example.ui.theme.RetroAmberGold
import com.example.ui.theme.RetroBrushedBottom
import com.example.ui.theme.RetroBrushedMid
import com.example.ui.theme.RetroBrushedTop
import com.example.ui.theme.RetroGraphite
import com.example.ui.theme.TextPrimaryLight
import com.example.ui.theme.TextSecondaryMuted
import kotlinx.serialization.Serializable

@Serializable
object CameraRoute

@Serializable
object GalleryRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Retro3GSCamApp()
            }
        }
    }
}

@Composable
fun Retro3GSCamApp(
    viewModel: RetroCameraViewModel = viewModel(),
) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    NavHost(
        navController = navController,
        startDestination = CameraRoute,
    ) {
        composable<CameraRoute> {
            CameraScreen(
                uiState = uiState,
                viewModel = viewModel,
                onOpenGallery = {
                    viewModel.refreshCameraRoll()
                    navController.navigate(GalleryRoute)
                },
            )
        }
        composable<GalleryRoute> {
            RetroGalleryScreen(
                items = uiState.cameraRollItems,
                onBackToCamera = { navController.popBackStack() },
                onLoadExif = { item -> viewModel.loadExif(item) },
                onDeleteItem = { item -> viewModel.deleteMediaItem(item) },
            )
        }
    }
}

@Composable
private fun CameraScreen(
    uiState: CameraUiState,
    viewModel: RetroCameraViewModel,
    onOpenGallery: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionRequestedOnce by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        permissionRequestedOnce = true
        hasCameraPermission = grants[Manifest.permission.CAMERA] == true
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            )
        }
    }

    LaunchedEffect(uiState.statusMessage) {
        val msg = uiState.statusMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(RetroGraphite),
    ) {
        if (!hasCameraPermission) {
            RetroPermissionGate(
                showSettingsFallback = permissionRequestedOnce,
                onRequestPermissions = {
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                    )
                },
                onOpenSettings = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    )
                    context.startActivity(intent)
                },
            )
        } else {
            DisposableEffect(lifecycleOwner) {
                viewModel.bindCamera(lifecycleOwner)
                onDispose { }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                // Viewfinder Region
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    val isPortrait = maxWidth < maxHeight
                    val aspect = if (isPortrait) 3f / 4f else 4f / 3f
                    var viewfinderSize by remember { mutableStateOf(IntSize.Zero) }

                    Box(
                        modifier = Modifier
                            .then(
                                if (isPortrait) {
                                    Modifier.fillMaxWidth().aspectRatio(aspect)
                                } else {
                                    Modifier.height(maxHeight).aspectRatio(aspect)
                                },
                            )
                            .clip(RoundedCornerShape(2.dp))
                            .onSizeChanged { viewfinderSize = it }
                            .pointerInput(viewfinderSize) {
                                detectTapGestures { offset ->
                                    if (viewfinderSize.width > 0 && viewfinderSize.height > 0) {
                                        viewModel.onViewfinderTap(
                                            normalizedX = (offset.x / viewfinderSize.width).coerceIn(0.05f, 0.95f),
                                            normalizedY = (offset.y / viewfinderSize.height).coerceIn(0.05f, 0.95f),
                                            viewWidth = viewfinderSize.width.toFloat(),
                                            viewHeight = viewfinderSize.height.toFloat(),
                                        )
                                    }
                                }
                            },
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                TextureView(ctx).apply {
                                    isOpaque = true
                                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                        override fun onSurfaceTextureAvailable(
                                            surface: SurfaceTexture,
                                            width: Int,
                                            height: Int,
                                        ) {
                                            viewModel.attachPreviewSurface(surface, width, height)
                                        }

                                        override fun onSurfaceTextureSizeChanged(
                                            surface: SurfaceTexture,
                                            width: Int,
                                            height: Int,
                                        ) {
                                            viewModel.updatePreviewSize(width, height)
                                        }

                                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                            viewModel.detachPreviewSurface()
                                            return true
                                        }

                                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )

                        TapToFocusReticle(
                            focusNormalized = uiState.focusNormalized,
                            focusEventId = uiState.focusEventId,
                        )

                        IrisShutterOverlay(trigger = uiState.irisTrigger)
                    }

                    // Top Floating Skeuomorphic Glossy Pills (Flash, REC Timer, Flip Camera)
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlossyFlashPill(
                            flashMode = uiState.flashMode,
                            enabled = uiState.cameraFacing == CameraFacing.BACK,
                            onClick = { viewModel.cycleFlashMode() },
                        )

                        if (uiState.isRecording) {
                            RecordingTimerPill(seconds = uiState.recordingSeconds)
                        } else {
                            Spacer(Modifier.width(1.dp))
                        }

                        if (uiState.hasFrontCamera) {
                            GlossyFlipCameraPill(
                                enabled = !uiState.isRecording && !uiState.isCapturing,
                                onClick = { viewModel.toggleFrontBackCamera() },
                            )
                        } else {
                            Spacer(Modifier.width(48.dp))
                        }
                    }

                    // iOS 4 Digital Zoom Slider / Compact Badge at Bottom of Viewfinder
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = uiState.isZoomSliderVisible,
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            Ios4ZoomSliderBar(
                                zoomRatio = uiState.zoomRatio,
                                onZoomChanged = { viewModel.onZoomChanged(it) },
                            )
                        }
                        if (!uiState.isZoomSliderVisible) {
                            Box(
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(PillSurface)
                                    .border(1.dp, PillBorder, RoundedCornerShape(16.dp))
                                    .clickable { viewModel.showZoomSlider() }
                                    .padding(horizontal = 12.dp, vertical = 5.dp)
                                    .testTag("zoom_pill_button"),
                            ) {
                                Text(
                                    text = "%.1fx".format(uiState.zoomRatio),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ChromeHighlight,
                                )
                            }
                        }
                    }
                }

                // Bottom Brushed Dark-Silver Chrome Control Bar
                BrushedSilverBottomBar(
                    uiState = uiState,
                    onOpenGallery = onOpenGallery,
                    onShutterClick = { viewModel.onShutterPressed() },
                    onToggleMode = { targetMode -> viewModel.toggleMode(targetMode) },
                )
            }

            // Full-screen suction effect flying captured photo into bottom-left thumbnail
            ThumbnailSuctionOverlay(
                bitmap = uiState.suctionBitmap,
                suctionKey = uiState.suctionKey,
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 56.dp),
        )
    }
}

@Composable
private fun GlossyFlashPill(
    flashMode: FlashMode,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val label = when (flashMode) {
        FlashMode.AUTO -> stringResource(R.string.flash_auto)
        FlashMode.ON -> stringResource(R.string.flash_on)
        FlashMode.OFF -> stringResource(R.string.flash_off)
    }
    val icon = when (flashMode) {
        FlashMode.AUTO -> Icons.Filled.FlashAuto
        FlashMode.ON -> Icons.Filled.FlashOn
        FlashMode.OFF -> Icons.Filled.FlashOff
    }

    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xCC2A2D33), Color(0xCC111214)),
                ),
            )
            .border(1.dp, PillGlossTop, RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .testTag("flash_button"),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = stringResource(R.string.cd_flash_button),
                tint = if (flashMode == FlashMode.ON) RetroAmberGold else ChromeHighlight,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = ChromeHighlight,
            )
        }
    }
}

@Composable
private fun GlossyFlipCameraPill(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .alpha(if (enabled) 1f else 0.5f)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xCC2A2D33), Color(0xCC111214)),
                ),
            )
            .border(1.dp, PillGlossTop, RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .testTag("flip_camera_button"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.FlipCameraIos,
            contentDescription = stringResource(R.string.cd_flip_camera),
            tint = ChromeHighlight,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun RecordingTimerPill(seconds: Int) {
    val pulseTransition = rememberInfiniteTransition(label = "recLed")
    val ledAlpha by pulseTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 550),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recAlpha",
    )

    val mins = seconds / 60
    val secs = seconds % 60
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xCC141518))
            .border(1.dp, PillGlossTop, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .alpha(ledAlpha)
                .clip(CircleShape)
                .background(RecordCrimson),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = "%02d:%02d".format(mins, secs),
            style = MaterialTheme.typography.labelLarge.copy(
                fontFamily = MaterialTheme.typography.labelSmall.fontFamily,
            ),
            color = ChromeHighlight,
        )
    }
}

@Composable
private fun Ios4ZoomSliderBar(
    zoomRatio: Float,
    onZoomChanged: (Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xC4131518))
            .border(1.dp, PillGlossTop, RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "1x",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondaryMuted,
        )
        Slider(
            value = zoomRatio,
            onValueChange = onZoomChanged,
            valueRange = 1.0f..5.0f,
            colors = SliderDefaults.colors(
                thumbColor = ChromeHighlight,
                activeTrackColor = RetroAmberGold,
                inactiveTrackColor = Color(0x55FFFFFF),
            ),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .testTag("digital_zoom_slider"),
        )
        Text(
            text = "%.1fx".format(zoomRatio),
            style = MaterialTheme.typography.labelSmall,
            color = RetroAmberGold,
        )
    }
}

@Composable
private fun BrushedSilverBottomBar(
    uiState: CameraUiState,
    onOpenGallery: () -> Unit,
    onShutterClick: () -> Unit,
    onToggleMode: (CaptureMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        RetroBrushedTop,
                        RetroBrushedMid,
                        RetroBrushedBottom,
                    ),
                ),
            )
            .navigationBarsPadding(),
    ) {
        // Top brushed chrome highlight seam
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0x66FFFFFF)),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: Square Thumbnail Preview of Last Captured Media
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .shadow(6.dp, RoundedCornerShape(4.dp))
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF23262B))
                    .border(2.dp, Color.White, RoundedCornerShape(4.dp))
                    .clickable(onClick = onOpenGallery)
                    .testTag("gallery_thumbnail_button"),
                contentAlignment = Alignment.Center,
            ) {
                val thumb = uiState.latestThumbnail
                if (thumb != null) {
                    val imageBitmap = remember(thumb) { thumb.asImageBitmap() }
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = stringResource(R.string.cd_gallery_thumbnail),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (uiState.latestMediaIsVideo) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .background(Color(0xAA000000))
                                .padding(2.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Videocam,
                                contentDescription = null,
                                tint = ChromeHighlight,
                                modifier = Modifier.size(11.dp),
                            )
                        }
                    }
                } else {
                    Icon(
                        imageVector = Icons.Filled.Photo,
                        contentDescription = stringResource(R.string.cd_gallery_thumbnail),
                        tint = TextSecondaryMuted,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            // Center: Glossy Metallic Physical Shutter Button
            GlossyChromeShutterButton(
                mode = uiState.captureMode,
                isRecording = uiState.isRecording,
                onClick = onShutterClick,
            )

            // Right: Skeuomorphic Photo / Video Toggle Switch
            SkeuomorphicModeSwitch(
                mode = uiState.captureMode,
                enabled = !uiState.isRecording && !uiState.isCapturing,
                onToggle = {
                    val target = if (uiState.captureMode == CaptureMode.PHOTO) {
                        CaptureMode.VIDEO
                    } else {
                        CaptureMode.PHOTO
                    }
                    onToggleMode(target)
                },
            )
        }
    }
}

@Composable
private fun GlossyChromeShutterButton(
    mode: CaptureMode,
    isRecording: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "shutterScale",
    )

    val cd = when {
        mode == CaptureMode.PHOTO -> stringResource(R.string.cd_shutter_photo)
        isRecording -> stringResource(R.string.cd_shutter_video_stop)
        else -> stringResource(R.string.cd_shutter_video_start)
    }

    // Outer recessed dark bezel well
    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .size(width = 88.dp, height = 52.dp)
            .shadow(6.dp, RoundedCornerShape(26.dp))
            .clip(RoundedCornerShape(26.dp))
            .background(ChromeBevelBorder)
            .padding(2.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        ChromeHighlight,
                        ChromeLight,
                        ChromeMid,
                        ChromeShadow,
                    ),
                ),
            )
            .border(1.dp, Color(0x99FFFFFF), RoundedCornerShape(24.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .testTag("shutter_button"),
        contentAlignment = Alignment.Center,
    ) {
        if (mode == CaptureMode.PHOTO) {
            Icon(
                imageVector = Icons.Filled.CameraAlt,
                contentDescription = cd,
                tint = Color(0xFF2B2E33),
                modifier = Modifier.size(26.dp),
            )
        } else {
            // Glowing crimson tally dot (circles when idle, sharp square when recording)
            Box(
                modifier = Modifier
                    .size(if (isRecording) 18.dp else 22.dp)
                    .shadow(4.dp, if (isRecording) RoundedCornerShape(4.dp) else CircleShape)
                    .clip(if (isRecording) RoundedCornerShape(4.dp) else CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0xFFFF6662), RecordCrimson, RecordCrimsonDark),
                        ),
                    )
                    .border(1.dp, Color(0x66000000), if (isRecording) RoundedCornerShape(4.dp) else CircleShape),
            )
        }
    }
}

@Composable
private fun SkeuomorphicModeSwitch(
    mode: CaptureMode,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val thumbOffset by animateDpAsState(
        targetValue = if (mode == CaptureMode.PHOTO) 2.dp else 36.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "modeThumbOffset",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.alpha(if (enabled) 1f else 0.55f),
    ) {
        // Tiny Photo / Video indicator icons above track
        Row(
            modifier = Modifier
                .width(66.dp)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.CameraAlt,
                contentDescription = null,
                tint = if (mode == CaptureMode.PHOTO) RetroAmberGold else TextSecondaryMuted,
                modifier = Modifier.size(13.dp),
            )
            Icon(
                imageVector = Icons.Filled.Videocam,
                contentDescription = null,
                tint = if (mode == CaptureMode.VIDEO) RecordCrimson else TextSecondaryMuted,
                modifier = Modifier.size(14.dp),
            )
        }

        // Physical recessed metallic slider track + sliding chrome knob
        Box(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .width(66.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0C0D0F), Color(0xFF24272B)),
                    ),
                )
                .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(15.dp))
                .clickable(enabled = enabled, onClick = onToggle)
                .testTag("mode_toggle_switch"),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .size(26.dp)
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(ChromeHighlight, ChromeMid, ChromeShadow),
                        ),
                    )
                    .border(1.dp, Color(0x99FFFFFF), CircleShape),
            )
        }
    }
}

@Composable
private fun RetroPermissionGate(
    showSettingsFallback: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(RetroGraphite)
            .windowInsetsPadding(WindowInsets.statusBars)
            .navigationBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .shadow(16.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(RetroBrushedTop, RetroBrushedMid, RetroBrushedBottom),
                    ),
                )
                .border(1.dp, ChromeShadow, RoundedCornerShape(18.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(listOf(ChromeHighlight, ChromeMid, ChromeShadow)),
                    )
                    .border(2.dp, ChromeBevelBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.CameraAlt,
                    contentDescription = null,
                    tint = RetroGraphite,
                    modifier = Modifier.size(34.dp),
                )
            }

            Text(
                text = stringResource(R.string.permission_title),
                style = MaterialTheme.typography.titleLarge,
                color = ChromeHighlight,
                textAlign = TextAlign.Center,
            )

            Text(
                text = stringResource(R.string.permission_body),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondaryMuted,
                textAlign = TextAlign.Center,
            )

            Button(
                onClick = onRequestPermissions,
                colors = ButtonDefaults.buttonColors(
                    containerColor = RetroAmberGold,
                    contentColor = RetroGraphite,
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("grant_camera_permission_button"),
            ) {
                Text(
                    text = stringResource(R.string.permission_grant_button),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                )
            }

            if (showSettingsFallback) {
                Button(
                    onClick = onOpenSettings,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2E3238),
                        contentColor = ChromeHighlight,
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("open_settings_button"),
                ) {
                    Text(
                        text = stringResource(R.string.permission_settings_button),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
