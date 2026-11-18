package com.example

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * OpenGL ES 2.0 / 3.0 real-time shader pipeline emulating the 2009 OmniVision OV3642 3MP CMOS sensor.
 * Renders CameraX OES SurfaceTexture frames simultaneously to:
 * 1. Live viewfinder Surface (SurfaceView)
 * 2. Offscreen 2048x1536 FBO for 3.1 MP JPEG capture
 * 3. MediaCodec input Surface for 640x480 30FPS H.264 video recording
 */
class RetroShaderRenderer(
    private val onCameraSurfaceTextureReady: (SurfaceTexture) -> Unit,
) {
    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        const val PHOTO_WIDTH = 2048
        const val PHOTO_HEIGHT = 1536
        const val VIDEO_WIDTH = 640
        const val VIDEO_HEIGHT = 480

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uStMatrix;
            uniform float uFlipY;
            uniform float uZoom;
            uniform vec2 uAspectCrop;
            varying vec2 vTexCoord;
            varying vec2 vRawUv;

            void main() {
                gl_Position = aPosition;
                vec2 uv = aTexCoord;
                if (uFlipY > 0.5) {
                    uv.y = 1.0 - uv.y;
                }
                vec2 centered = (uv - 0.5) * uAspectCrop / max(uZoom, 1.0) + 0.5;
                vRawUv = centered;
                vec4 transformed = uStMatrix * vec4(centered, 0.0, 1.0);
                vTexCoord = transformed.xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;

            uniform samplerExternalOES sTexture;
            uniform float uTime;
            uniform vec2 uTexelStep;
            varying vec2 vTexCoord;
            varying vec2 vRawUv;

            float luminance(vec3 c) {
                return dot(c, vec3(0.299, 0.587, 0.114));
            }

            float hash12(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            vec3 sampleClamped(vec2 uv) {
                return texture2D(sTexture, clamp(uv, 0.001, 0.999)).rgb;
            }

            void main() {
                // 1. Authentic 2009 3.1MP Plastic/Glass f/2.8 Optical Softness (smooth MTF rolloff, zero blocky aliasing)
                vec2 opticalStep = vec2(1.0 / 960.0, 1.0 / 720.0) * 1.25;
                vec2 radial = vRawUv - 0.5;
                float edgeDist = dot(radial, radial);
                vec2 chromaShift = radial * edgeDist * 0.0028;

                vec3 cCenter = sampleClamped(vTexCoord);
                vec3 cN = sampleClamped(vTexCoord + vec2(0.0, opticalStep.y));
                vec3 cS = sampleClamped(vTexCoord - vec2(0.0, opticalStep.y));
                vec3 cE = sampleClamped(vTexCoord + vec2(opticalStep.x, 0.0));
                vec3 cW = sampleClamped(vTexCoord - vec2(opticalStep.x, 0.0));

                vec3 softColor = cCenter * 0.44 + (cN + cS + cE + cW) * 0.14;
                // Subtle lateral chromatic aberration on outer edges
                softColor.r = mix(softColor.r, sampleClamped(vTexCoord + chromaShift).r, 0.45);
                softColor.b = mix(softColor.b, sampleClamped(vTexCoord - chromaShift).b, 0.45);

                // 2. Soft Atmospheric Veiling Glare / Halation around bright sky & highlights (wide, gentle, creamy-white)
                vec2 glareStep = vec2(1.0 / 320.0, 1.0 / 240.0) * 3.2;
                vec3 g1 = sampleClamped(vTexCoord + vec2( glareStep.x,  glareStep.y));
                vec3 g2 = sampleClamped(vTexCoord + vec2(-glareStep.x,  glareStep.y));
                vec3 g3 = sampleClamped(vTexCoord + vec2( glareStep.x, -glareStep.y));
                vec3 g4 = sampleClamped(vTexCoord + vec2(-glareStep.x, -glareStep.y));
                vec3 glareAvg = (g1 + g2 + g3 + g4) * 0.25;
                float glareWeight = smoothstep(0.70, 0.98, luminance(glareAvg));
                vec3 veilingGlare = glareAvg * glareWeight * vec3(1.02, 0.99, 0.96) * 0.18;

                // 3. Narrow Dynamic Range Tone Curve (Deep Toe Crush + Smooth Filmic Highlight Shoulder)
                float luma = luminance(softColor);

                // Toe curve: drop deep shadows & foliage into moody charcoal without flat black clipping
                float toeFactor = mix(0.48, 1.0, smoothstep(0.02, 0.36, luma));
                vec3 mapped = softColor * toeFactor;

                // Midtone contrast S-curve
                mapped = clamp((mapped - 0.46) * 1.18 + 0.46, 0.0, 1.0);

                // Gentle highlight roll-off above 0.68 luma toward creamy white (no harsh magenta threshold fringe)
                float postLuma = luminance(mapped);
                float shoulder = smoothstep(0.68, 0.96, postLuma);
                vec3 creamyWhite = vec3(0.99, 0.985, 0.965);
                mapped = mix(mapped, creamyWhite, shoulder * 0.78);

                // Blend soft optical veiling glare
                mapped = clamp(mapped + veilingGlare * (1.0 - shoulder * 0.5), 0.0, 1.0);

                // 4. Authentic iPhone 3GS Color Matrix:
                // - Subtle cool-plum charcoal shadows (like 3GS backlit mountains)
                // - Warm earthy/olive-golden midtones (2009 CMOS warmth)
                // - Slightly muted milky highlights
                float finalLuma = luminance(mapped);
                float shadowW = 1.0 - smoothstep(0.0, 0.28, finalLuma);
                float highW = smoothstep(0.62, 1.0, finalLuma);
                float midW = clamp(1.0 - shadowW - highW, 0.0, 1.0);

                vec3 shadowTint = vec3(0.97, 0.95, 1.01);
                vec3 midTint = vec3(1.04, 1.00, 0.91);
                vec3 highTint = vec3(1.01, 1.00, 0.97);
                mapped *= (shadowTint * shadowW + midTint * midW + highTint * highW);

                // Desaturate deep shadows and bright skies gently; keep midtones warm & natural
                float sat = mix(0.58, 0.94, smoothstep(0.05, 0.35, finalLuma))
                          * mix(1.0, 0.68, smoothstep(0.68, 0.96, finalLuma));
                mapped = mix(vec3(luminance(mapped)), mapped, sat);

                // 5. Fine-Pitch 2009 CMOS Grain in Shadow Regions (< 0.30 luma)
                float noiseMask = 1.0 - smoothstep(0.03, 0.30, finalLuma);
                vec2 grainCell = floor(vRawUv * vec2(960.0, 720.0));
                float tSeed = floor(uTime * 18.0);
                float lumaGrain = (hash12(grainCell + vec2(tSeed * 1.7, 13.3)) - 0.5) * 0.036;
                float cR = (hash12(grainCell + vec2(tSeed * 2.3, 41.9)) - 0.5) * 0.020;
                float cB = (hash12(grainCell + vec2(tSeed * 3.1, 79.1)) - 0.5) * 0.020;
                mapped += vec3(lumaGrain + cR, lumaGrain, lumaGrain + cB) * noiseMask;

                // Subtle miniature f/2.8 lens corner vignette
                float vignette = 1.0 - edgeDist * 0.34;
                mapped *= clamp(vignette, 0.72, 1.0);

                gl_FragColor = vec4(clamp(mapped, 0.0, 1.0), 1.0);
            }
        """

        private val QUAD_COORDS = floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f,
        )

        private val TEX_COORDS = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        )
    }

    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var previewSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewWidth: Int = 640
    private var previewHeight: Int = 480

    private var encoderSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var cameraOesTexId: Int = 0
    private var cameraSurfaceTexture: SurfaceTexture? = null
    private var hasCameraFrame = false
    private var frameCount = 0

    private var programId: Int = 0
    private var aPositionLoc: Int = -1
    private var aTexCoordLoc: Int = -1
    private var uStMatrixLoc: Int = -1
    private var uFlipYLoc: Int = -1
    private var uZoomLoc: Int = -1
    private var uAspectCropLoc: Int = -1
    private var uTimeLoc: Int = -1
    private var uTexelStepLoc: Int = -1

    private var photoFboId: Int = 0
    private var photoFboTexId: Int = 0

    private val stMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val quadBuffer: FloatBuffer = ByteBuffer.allocateDirect(QUAD_COORDS.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(QUAD_COORDS); position(0) }
    private val texBuffer: FloatBuffer = ByteBuffer.allocateDirect(TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(TEX_COORDS); position(0) }

    @Volatile
    private var shaderZoom: Float = 1.0f

    @Volatile
    private var streamWidth: Int = 640

    @Volatile
    private var streamHeight: Int = 480

    @Volatile
    private var lastEstimatedLuma: Float = 0.5f

    private var pendingPhotoCallback: ((Bitmap) -> Unit)? = null
    private var startTimeNs: Long = System.nanoTime()

    @Volatile
    private var isReleased = false

    fun start() {
        if (glThread != null) return
        val thread = HandlerThread("RetroGLThread").apply { start() }
        glThread = thread
        val handler = Handler(thread.looper)
        glHandler = handler
        handler.post { initGl() }
    }

    fun setStreamResolution(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            streamWidth = width
            streamHeight = height
        }
    }

    fun setShaderZoom(zoom: Float) {
        shaderZoom = zoom.coerceIn(1.0f, 5.0f)
    }

    fun getEstimatedSceneLuma(): Float = lastEstimatedLuma

    fun attachPreviewSurface(surface: Any, width: Int, height: Int) {
        glHandler?.post {
            if (isReleased || eglDisplay == EGL14.EGL_NO_DISPLAY || eglConfig == null) return@post
            destroyPreviewSurface()
            previewWidth = max(1, width)
            previewHeight = max(1, height)
            val attribs = intArrayOf(EGL14.EGL_NONE)
            val created = EGL14.eglCreateWindowSurface(
                eglDisplay,
                eglConfig,
                surface,
                attribs,
                0,
            )
            if (created != null && created != EGL14.EGL_NO_SURFACE) {
                previewSurface = created
                makeCurrent(previewSurface)
                if (hasCameraFrame) {
                    drawCurrentFrameToSurface(previewSurface, previewWidth, previewHeight, flipY = false)
                } else {
                    clearSurface(previewSurface, previewWidth, previewHeight)
                }
            }
        }
    }

    fun updatePreviewSize(width: Int, height: Int) {
        glHandler?.post {
            previewWidth = max(1, width)
            previewHeight = max(1, height)
            if (previewSurface != EGL14.EGL_NO_SURFACE) {
                if (hasCameraFrame) {
                    drawCurrentFrameToSurface(previewSurface, previewWidth, previewHeight, flipY = false)
                } else {
                    clearSurface(previewSurface, previewWidth, previewHeight)
                }
            }
        }
    }

    fun detachPreviewSurfaceSync() {
        val handler = glHandler ?: return
        val latch = CountDownLatch(1)
        val posted = handler.post {
            destroyPreviewSurface()
            latch.countDown()
        }
        if (posted) {
            runCatching { latch.await(400, TimeUnit.MILLISECONDS) }
        }
    }

    fun attachEncoderSurface(surface: Surface) {
        glHandler?.post {
            if (isReleased || eglDisplay == EGL14.EGL_NO_DISPLAY || eglConfig == null) return@post
            destroyEncoderSurface()
            val attribs = intArrayOf(EGL14.EGL_NONE)
            val created = EGL14.eglCreateWindowSurface(
                eglDisplay,
                eglConfig,
                surface,
                attribs,
                0,
            )
            if (created != null && created != EGL14.EGL_NO_SURFACE) {
                encoderSurface = created
            }
        }
    }

    fun detachEncoderSurface() {
        glHandler?.post {
            destroyEncoderSurface()
        }
    }

    fun capturePhoto2048x1536(onCaptured: (Bitmap) -> Unit) {
        glHandler?.post {
            if (isReleased) return@post
            pendingPhotoCallback = onCaptured
            renderPhotoToFboAndDeliver()
        }
    }

    fun release() {
        isReleased = true
        val handler = glHandler ?: return
        handler.post {
            destroyEncoderSurface()
            destroyPreviewSurface()
            destroyPhotoFbo()
            unbindCurrentContext()
            if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
                pbufferSurface = EGL14.EGL_NO_SURFACE
            }
            cameraSurfaceTexture?.setOnFrameAvailableListener(null)
            cameraSurfaceTexture?.release()
            cameraSurfaceTexture = null
            if (cameraOesTexId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(cameraOesTexId), 0)
                cameraOesTexId = 0
            }
            if (programId != 0) {
                GLES20.glDeleteProgram(programId)
                programId = 0
            }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                }
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            glThread?.quitSafely()
        }
        glThread = null
        glHandler = null
    }

    private fun initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            return
        }

        eglConfig = chooseConfig(recordable = true) ?: chooseConfig(recordable = false) ?: return
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) return

        // Persistent 1x1 pbuffer fallback surface so offscreen FBO captures always have a valid surface
        pbufferSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay,
            eglConfig,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )
        if (pbufferSurface == EGL14.EGL_NO_SURFACE || !makeCurrent(pbufferSurface)) {
            return
        }

        programId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
        uStMatrixLoc = GLES20.glGetUniformLocation(programId, "uStMatrix")
        uFlipYLoc = GLES20.glGetUniformLocation(programId, "uFlipY")
        uZoomLoc = GLES20.glGetUniformLocation(programId, "uZoom")
        uAspectCropLoc = GLES20.glGetUniformLocation(programId, "uAspectCrop")
        uTimeLoc = GLES20.glGetUniformLocation(programId, "uTime")
        uTexelStepLoc = GLES20.glGetUniformLocation(programId, "uTexelStep")

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        cameraOesTexId = texIds[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraOesTexId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(cameraOesTexId)
        st.setOnFrameAvailableListener({ tex ->
            if (!isReleased) {
                onFrameAvailable(tex)
            }
        }, glHandler)
        cameraSurfaceTexture = st

        onCameraSurfaceTextureReady(st)
    }

    private fun onFrameAvailable(st: SurfaceTexture) {
        val activeSurface = validSurfaceOrFallback() ?: return
        if (!makeCurrent(activeSurface)) return

        st.updateTexImage()
        st.getTransformMatrix(stMatrix)
        hasCameraFrame = true
        frameCount++

        if (previewSurface != EGL14.EGL_NO_SURFACE) {
            drawCurrentFrameToSurface(previewSurface, previewWidth, previewHeight, flipY = false)
            if (frameCount % 15 == 0) {
                sampleCenterLumaRough()
            }
        }

        if (encoderSurface != EGL14.EGL_NO_SURFACE && makeCurrent(encoderSurface)) {
            drawQuad(VIDEO_WIDTH, VIDEO_HEIGHT, flipY = false)
            EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderSurface, st.timestamp)
            EGL14.eglSwapBuffers(eglDisplay, encoderSurface)
        }

        if (pendingPhotoCallback != null) {
            renderPhotoToFboAndDeliver()
        }
    }

    private fun renderPhotoToFboAndDeliver() {
        val callback = pendingPhotoCallback ?: return
        pendingPhotoCallback = null

        val surfaceToBind = validSurfaceOrFallback()
        if (surfaceToBind == null || !makeCurrent(surfaceToBind)) {
            callback(Bitmap.createBitmap(PHOTO_WIDTH, PHOTO_HEIGHT, Bitmap.Config.ARGB_8888))
            return
        }

        ensurePhotoFbo()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, photoFboId)
        if (hasCameraFrame) {
            drawQuad(PHOTO_WIDTH, PHOTO_HEIGHT, flipY = true)
        } else {
            GLES20.glViewport(0, 0, PHOTO_WIDTH, PHOTO_HEIGHT)
            GLES20.glClearColor(0.067f, 0.071f, 0.078f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }

        val pixelBuffer = ByteBuffer.allocateDirect(PHOTO_WIDTH * PHOTO_HEIGHT * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        GLES20.glReadPixels(
            0,
            0,
            PHOTO_WIDTH,
            PHOTO_HEIGHT,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            pixelBuffer,
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        pixelBuffer.rewind()
        val bitmap = Bitmap.createBitmap(PHOTO_WIDTH, PHOTO_HEIGHT, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(pixelBuffer)
        callback(bitmap)
    }

    private fun clearSurface(surface: EGLSurface, width: Int, height: Int) {
        if (!makeCurrent(surface)) return
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0.067f, 0.071f, 0.078f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        EGL14.eglSwapBuffers(eglDisplay, surface)
    }

    private fun drawCurrentFrameToSurface(
        surface: EGLSurface,
        width: Int,
        height: Int,
        flipY: Boolean,
    ) {
        if (!makeCurrent(surface)) return
        drawQuad(width, height, flipY)
        EGL14.eglSwapBuffers(eglDisplay, surface)
    }

    private fun drawQuad(targetWidth: Int, targetHeight: Int, flipY: Boolean) {
        GLES20.glViewport(0, 0, targetWidth, targetHeight)
        GLES20.glClearColor(0.067f, 0.071f, 0.078f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(programId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraOesTexId)

        GLES20.glUniformMatrix4fv(uStMatrixLoc, 1, false, stMatrix, 0)
        GLES20.glUniform1f(uFlipYLoc, if (flipY) 1f else 0f)
        GLES20.glUniform1f(uZoomLoc, shaderZoom)

        val targetAspect = targetWidth.toFloat() / targetHeight.toFloat()
        val streamAspect = streamWidth.toFloat() / streamHeight.toFloat()
        val cropX = if (streamAspect > targetAspect) targetAspect / streamAspect else 1.0f
        val cropY = if (streamAspect < targetAspect) streamAspect / targetAspect else 1.0f
        GLES20.glUniform2f(uAspectCropLoc, cropX, cropY)

        val elapsedSec = ((System.nanoTime() - startTimeNs) / 1_000_000_000.0).toFloat()
        GLES20.glUniform1f(uTimeLoc, elapsedSec)
        GLES20.glUniform2f(uTexelStepLoc, 1f / targetWidth.toFloat(), 1f / targetHeight.toFloat())

        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, quadBuffer)

        texBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
    }

    private fun sampleCenterLumaRough() {
        val sampleBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(
            previewWidth / 2,
            previewHeight / 2,
            1,
            1,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            sampleBuf,
        )
        val r = (sampleBuf.get(0).toInt() and 0xFF) / 255f
        val g = (sampleBuf.get(1).toInt() and 0xFF) / 255f
        val b = (sampleBuf.get(2).toInt() and 0xFF) / 255f
        lastEstimatedLuma = 0.299f * r + 0.587f * g + 0.114f * b
    }

    private fun ensurePhotoFbo() {
        if (photoFboId != 0) return
        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        photoFboId = fboIds[0]

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        photoFboTexId = texIds[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, photoFboTexId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            PHOTO_WIDTH,
            PHOTO_HEIGHT,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, photoFboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            photoFboTexId,
            0,
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun destroyPhotoFbo() {
        if (photoFboTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(photoFboTexId), 0)
            photoFboTexId = 0
        }
        if (photoFboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(photoFboId), 0)
            photoFboId = 0
        }
    }

    private fun destroyPreviewSurface() {
        if (previewSurface != EGL14.EGL_NO_SURFACE) {
            unbindCurrentContext()
            EGL14.eglDestroySurface(eglDisplay, previewSurface)
            previewSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun destroyEncoderSurface() {
        if (encoderSurface != EGL14.EGL_NO_SURFACE) {
            unbindCurrentContext()
            EGL14.eglDestroySurface(eglDisplay, encoderSurface)
            encoderSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun validSurfaceOrFallback(): EGLSurface? {
        return when {
            previewSurface != EGL14.EGL_NO_SURFACE -> previewSurface
            encoderSurface != EGL14.EGL_NO_SURFACE -> encoderSurface
            pbufferSurface != EGL14.EGL_NO_SURFACE -> pbufferSurface
            else -> null
        }
    }

    private fun unbindCurrentContext() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
        }
    }

    private fun makeCurrent(surface: EGLSurface): Boolean {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglContext == EGL14.EGL_NO_CONTEXT || surface == EGL14.EGL_NO_SURFACE) {
            return false
        }
        return EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)
    }

    private fun chooseConfig(recordable: Boolean): EGLConfig? {
        val attribList = mutableListOf(
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
        )
        if (recordable) {
            attribList.add(EGL_RECORDABLE_ANDROID)
            attribList.add(1)
        }
        attribList.add(EGL14.EGL_NONE)

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        val ok = EGL14.eglChooseConfig(
            eglDisplay,
            attribList.toIntArray(),
            0,
            configs,
            0,
            1,
            numConfigs,
            0,
        )
        return if (ok && numConfigs[0] > 0) configs[0] else null
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw IllegalStateException("GL program link failed: $log")
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("GL shader compile failed: $log")
        }
        return shader
    }
}
