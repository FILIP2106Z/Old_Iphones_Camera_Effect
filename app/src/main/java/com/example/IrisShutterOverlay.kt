package com.example

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.ui.theme.IrisEdgeHighlight
import com.example.ui.theme.IrisSteelDark
import com.example.ui.theme.IrisSteelLight
import com.example.ui.theme.IrisSteelMid
import com.example.ui.theme.RetroAmberGold
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

enum class IrisAnimEvent {
    BOOT_OPEN,
    MODE_SWITCH,
    CAPTURE_SNAP,
}

data class IrisTrigger(
    val id: Long,
    val event: IrisAnimEvent,
)

/**
 * Mechanical 8-blade brushed-steel iris aperture overlay.
 * Smoothly opens in 450ms on launch and mode transitions, and snaps on capture.
 */
@Composable
fun IrisShutterOverlay(
    trigger: IrisTrigger,
    modifier: Modifier = Modifier,
) {
    // 0f = fully closed iris, 1f = fully retracted open iris
    val openness = remember { Animatable(0f) }

    LaunchedEffect(trigger.id) {
        when (trigger.event) {
            IrisAnimEvent.BOOT_OPEN -> {
                openness.snapTo(0f)
                openness.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
                )
            }
            IrisAnimEvent.MODE_SWITCH -> {
                openness.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                )
                openness.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
                )
            }
            IrisAnimEvent.CAPTURE_SNAP -> {
                openness.animateTo(
                    targetValue = 0.05f,
                    animationSpec = tween(durationMillis = 75, easing = LinearEasing),
                )
                openness.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 175, easing = FastOutSlowInEasing),
                )
            }
        }
    }

    if (openness.value < 0.995f) {
        Canvas(modifier = modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w * 0.5f
            val cy = h * 0.5f
            val maxRadius = hypot(w.toDouble(), h.toDouble()).toFloat() * 0.65f
            val t = openness.value.coerceIn(0f, 1f)

            // Inner aperture opening expands with t; at t=0 blades interlock completely
            val apertureRadius = t * maxRadius
            val bladeRotationSweep = (1f - t) * 42f

            clipRect {
                // Dark housing behind blades when near-closed
                if (t < 0.12f) {
                    drawCircle(
                        color = Color(0xFF0A0B0D),
                        radius = (0.12f - t) / 0.12f * (maxRadius * 0.18f),
                        center = Offset(cx, cy),
                    )
                }

                val bladeCount = 8
                val bladeLength = maxRadius * 1.45f
                for (i in 0 until bladeCount) {
                    val baseAngleDeg = i * (360f / bladeCount) + bladeRotationSweep
                    rotate(degrees = baseAngleDeg, pivot = Offset(cx, cy)) {
                        // Pivot lies on outer rim; blade sweeps tangentially past center when closed
                        val angleRad = (28.0 * PI / 180.0).toFloat()
                        val innerOffset = apertureRadius
                        val p0 = Offset(cx + innerOffset, cy - maxRadius * 0.28f)
                        val p1 = Offset(cx + innerOffset + bladeLength, cy - maxRadius * 0.95f)
                        val p2 = Offset(cx + innerOffset + bladeLength * 0.95f, cy + maxRadius * 0.92f)
                        val p3 = Offset(
                            cx + innerOffset + cos(angleRad) * (maxRadius * 0.25f),
                            cy + sin(angleRad) * (maxRadius * 0.65f),
                        )

                        val bladePath = Path().apply {
                            moveTo(p0.x, p0.y)
                            lineTo(p1.x, p1.y)
                            lineTo(p2.x, p2.y)
                            lineTo(p3.x, p3.y)
                            close()
                        }

                        val metalBrush = Brush.linearGradient(
                            colors = listOf(IrisSteelLight, IrisSteelMid, IrisSteelDark),
                            start = Offset(cx + innerOffset, cy - maxRadius * 0.45f),
                            end = Offset(cx + innerOffset + maxRadius * 0.85f, cy + maxRadius * 0.55f),
                        )
                        drawPath(path = bladePath, brush = metalBrush)

                        // Machined bevel highlight along blade leading edge
                        drawLine(
                            color = IrisEdgeHighlight,
                            start = p0,
                            end = p3,
                            strokeWidth = 2.2f,
                        )
                        drawLine(
                            color = Color(0xCC090A0C),
                            start = p3,
                            end = p2,
                            strokeWidth = 2.5f,
                        )
                    }
                }

                // Subtle circular lens barrel ring
                drawCircle(
                    color = Color(0x55000000),
                    radius = (maxRadius * 0.72f).coerceAtLeast(10f),
                    center = Offset(cx, cy),
                    style = Stroke(width = 4f),
                )
            }
        }
    }
}

/**
 * Classic iOS 3/4 snapshot suction effect: shrinks and curves the captured
 * frame smoothly into the bottom-left Camera Roll thumbnail well.
 */
@Composable
fun ThumbnailSuctionOverlay(
    bitmap: Bitmap?,
    suctionKey: Long,
    modifier: Modifier = Modifier,
) {
    if (bitmap == null || suctionKey == 0L) return

    val progress = remember(suctionKey) { Animatable(0f) }
    LaunchedEffect(suctionKey) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        )
    }

    if (progress.value < 0.99f) {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val p = progress.value
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }

            // Start centered in viewfinder, end over bottom-left thumbnail square
            val startX = widthPx * 0.14f
            val startY = heightPx * 0.16f
            val endX = with(density) { 20.dp.toPx() }
            val endY = heightPx - with(density) { 82.dp.toPx() }

            val curveLift = sin(p * PI.toFloat()) * with(density) { 28.dp.toPx() }
            val curX = startX + (endX - startX) * p
            val curY = startY + (endY - startY) * p - curveLift
            val scale = 1f - 0.84f * p
            val alpha = if (p > 0.85f) (1f - (p - 0.85f) / 0.15f) else 1f

            val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .offset { IntOffset(curX.roundToInt(), curY.roundToInt()) }
                    .size(maxWidth * 0.72f)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        rotationZ = -9f * p
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                    }
                    .alpha(alpha.coerceIn(0f, 1f))
                    .shadow(10.dp, RoundedCornerShape(4.dp))
                    .clip(RoundedCornerShape(4.dp))
                    .border(2.dp, Color.White, RoundedCornerShape(4.dp)),
            )
        }
    }
}

/**
 * Animated pulsing yellow square focus reticle with iOS 3.1 / iOS 4 edge ticks.
 */
@Composable
fun TapToFocusReticle(
    focusNormalized: Offset?,
    focusEventId: Long,
    modifier: Modifier = Modifier,
) {
    if (focusNormalized == null) return

    val entryScale = remember(focusEventId) { Animatable(1.38f) }
    val fadeAlpha = remember(focusEventId) { Animatable(1f) }
    val pulseTransition = rememberInfiniteTransition(label = "focusPulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = 0.62f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 280, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "focusAlpha",
    )

    LaunchedEffect(focusEventId) {
        entryScale.snapTo(1.38f)
        fadeAlpha.snapTo(1f)
        entryScale.animateTo(1.0f, animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing))
        kotlinx.coroutines.delay(1400)
        fadeAlpha.animateTo(0f, animationSpec = tween(durationMillis = 450))
    }

    if (fadeAlpha.value > 0.01f) {
        Canvas(modifier = modifier.fillMaxSize()) {
            val cx = focusNormalized.x * size.width
            val cy = focusNormalized.y * size.height
            val half = (42.dp.toPx() * entryScale.value)
            val tick = 7.dp.toPx()
            val color = RetroAmberGold.copy(alpha = (pulse * fadeAlpha.value).coerceIn(0f, 1f))
            val stroke = 2.dp.toPx()

            drawRect(
                color = color,
                topLeft = Offset(cx - half, cy - half),
                size = androidx.compose.ui.geometry.Size(half * 2f, half * 2f),
                style = Stroke(width = stroke),
            )
            // Classic 4-side inward focus ticks
            drawLine(color, Offset(cx, cy - half), Offset(cx, cy - half + tick), stroke)
            drawLine(color, Offset(cx, cy + half), Offset(cx, cy + half - tick), stroke)
            drawLine(color, Offset(cx - half, cy), Offset(cx - half + tick, cy), stroke)
            drawLine(color, Offset(cx + half, cy), Offset(cx + half - tick, cy), stroke)
        }
    }
}
