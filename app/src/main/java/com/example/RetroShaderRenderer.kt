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
import kotlin.math.max

/**
 * OpenGL ES 2.0 / 3.0 real-time shader pipeline emulating the 2009 OmniVision OV3642 3MP CMOS sensor.
 * Renders CameraX OES SurfaceTexture frames simultaneously to:
 * 1. Live viewfinder SurfaceTexture (TextureView)
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
                // Apply digital zoom and 4:3 aspect ratio crop around center
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

            void main() {
                // 1. Resolution Downsampling: virtual 640x480 grid hybrid nearest/bilinear interpolation
                vec2 virtualGrid = vec2(640.0, 480.0);
                vec2 snappedUv = (floor(vTexCoord * virtualGrid) + 0.5) / virtualGrid;
                vec2 sampleUv = mix(vTexCoord, snappedUv, 0.65);

                // Subtle single-element plastic lens chromatic fringe toward outer corners
                vec2 radial = vRawUv - 0.5;
                float edgeDist = dot(radial, radial);
                vec2 chromaOffset = radial * edgeDist * 0.0045;

                float r = texture2D(sTexture, clamp(sampleUv + chromaOffset, 0.0, 1.0)).r;
                float g = texture2D(sTexture, clamp(sampleUv, 0.0, 1.0)).g;
                float b = texture2D(sTexture, clamp(sampleUv - chromaOffset, 0.0, 1.0)).b;
                vec3 baseColor = vec3(r, g, b);

                // 4. Lightweight 5-tap thresholded optical halation / bloom (> 0.85 luma)
                vec2 stepUv = max(uTexelStep, vec2(1.0 / 640.0, 1.0 / 480.0)) * 2.4;
                vec3 tap1 = texture2D(sTexture, clamp(sampleUv + vec2( stepUv.x,  stepUv.y), 0.0, 1.0)).rgb;
                vec3 tap2 = texture2D(sTexture, clamp(sampleUv + vec2(-stepUv.x,  stepUv.y), 0.0, 1.0)).rgb;
                vec3 tap3 = texture2D(sTexture, clamp(sampleUv + vec2( stepUv.x, -stepUv.y), 0.0, 1.0)).rgb;
                vec3 tap4 = texture2D(sTexture, clamp(sampleUv + vec2(-stepUv.x, -stepUv.y), 0.0, 1.0)).rgb;

                vec3 bloomAccum = max(baseColor - 0.85, 0.0)
                    + max(tap1 - 0.85, 0.0)
                    + max(tap2 - 0.85, 0.0)
                    + max(tap3 - 0.85, 0.0)
                    + max(tap4 - 0.85, 0.0);
                vec3 halation = bloomAccum * vec3(1.12, 1.04, 0.94) * 0.68;

                // 2. Dynamic Range Compression & Highlight Clipping
                float luma = luminance(baseColor);
                // S-curve contrast boost
                vec3 mapped = clamp((baseColor - 0.5) * 1.26 + 0.5, 0.0, 1.0);

                // Crush shadow detail below 0.12 luminance
                float shadowCrush = 1.0 - smoothstep(0.04, 0.14, luma);
                mapped = mix(mapped, mapped * 0.36, shadowCrush);

                // Subtle magenta/pink sensor overload fringe right at the 0.72..0.83 clipping knee
                float fringeMask = smoothstep(0.68, 0.76, luma) * (1.0 - smoothstep(0.77, 0.85, luma));
                mapped += vec3(0.14, -0.04, 0.11) * fringeMask;

                // Hard-clip highlights above 0.78 luminance to pure white
                float clipWhite = smoothstep(0.74, 0.79, luma);
                mapped = mix(mapped, vec3(1.0), clipWhite);

                // Add optical halation glow
                mapped = clamp(mapped + halation, 0.0, 1.0);

                // 3. 2009 White Balance & Color Matrix: warm creamy/yellowish-green midtones
                float postLuma = luminance(mapped);
                float midtoneWeight = clamp(4.0 * postLuma * (1.0 - postLuma), 0.0, 1.0);
                vec3 colorTint = mix(vec3(1.02, 1.01, 0.93), vec3(1.06, 1.02, 0.88), midtoneWeight);
                mapped *= colorTint;

                // Slightly reduced saturation in shadows, lively midtones
                float sat = mix(0.64, 1.08, smoothstep(0.06, 0.38, postLuma));
                mapped = mix(vec3(luminance(mapped)), mapped, sat);

                // 5. Subtle low-frequency time-varying chroma noise in dark areas (< 0.25 luma)
                float darkNoiseMask = 1.0 - smoothstep(0.04, 0.25, postLuma);
                vec2 noiseCell = floor(vRawUv * vec2(320.0, 240.0));
                float tSeed = floor(uTime * 24.0);
                float nR = hash12(noiseCell + vec2(tSeed * 1.3, 17.1)) - 0.5;
                float nG = hash12(noiseCell + vec2(tSeed * 2.1, 53.7)) - 0.5;
                float nB = hash12(noiseCell + vec2(tSeed * 3.7, 91.3)) - 0.5;
                mapped += vec3(nR, nG, nB) * 0.11 * darkNoiseMask;

                // Subtle 2009 edge vignette
                float vignette = 1.0 - dot(radial, radial) * 0.28;
                mapped *= clamp(vignette, 0.78, 1.0);

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

    private var previewSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewWidth: Int = 640
    private var previewHeight: Int = 480

    private var encoderSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var cameraOesTexId: Int = 0
    private var cameraSurfaceTexture: SurfaceTexture? = null

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

    fun attachPreviewSurface(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        glHandler?.post {
            if (isReleased || eglDisplay == EGL14.EGL_NO_DISPLAY) return@post
            destroyPreviewSurface()
            previewWidth = max(1, width)
            previewHeight = max(1, height)
            val attribs = intArrayOf(EGL14.EGL_NONE)
            previewSurface = EGL14.eglCreateWindowSurface(
                eglDisplay,
                eglConfig,
                surfaceTexture,
                attribs,
                0,
            )
            if (previewSurface != EGL14.EGL_NO_SURFACE) {
                makeCurrent(previewSurface)
                drawCurrentFrameToSurface(previewSurface, previewWidth, previewHeight, flipY = false)
            }
        }
    }

    fun updatePreviewSize(width: Int, height: Int) {
        previewWidth = max(1, width)
        previewHeight = max(1, height)
    }

    fun detachPreviewSurface() {
        glHandler?.post {
            destroyPreviewSurface()
        }
    }

    fun attachEncoderSurface(surface: Surface) {
        glHandler?.post {
            if (isReleased || eglDisplay == EGL14.EGL_NO_DISPLAY) return@post
            destroyEncoderSurface()
            val attribs = intArrayOf(EGL14.EGL_NONE)
            encoderSurface = EGL14.eglCreateWindowSurface(
                eglDisplay,
                eglConfig,
                surface,
                attribs,
                0,
            )
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
            // If camera stream is paused/emulator idle, render immediately from current OES texture
            if (cameraSurfaceTexture != null) {
                renderPhotoToFboAndDeliver()
            }
        }
    }

    fun release() {
        isReleased = true
        val handler = glHandler ?: return
        handler.post {
            destroyEncoderSurface()
            destroyPreviewSurface()
            destroyPhotoFbo()
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
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
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
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        eglConfig = chooseConfig(recordable = true) ?: chooseConfig(recordable = false)
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)

        // Create temporary 1x1 pbuffer so we can compile shaders & OES texture immediately
        val pbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay,
            eglConfig,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )
        makeCurrent(pbuffer)

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

        EGL14.eglDestroySurface(eglDisplay, pbuffer)
        onCameraSurfaceTextureReady(st)
    }

    private fun onFrameAvailable(st: SurfaceTexture) {
        if (previewSurface == EGL14.EGL_NO_SURFACE && encoderSurface == EGL14.EGL_NO_SURFACE && pendingPhotoCallback == null) {
            return
        }
        val activeSurface = when {
            previewSurface != EGL14.EGL_NO_SURFACE -> previewSurface
            encoderSurface != EGL14.EGL_NO_SURFACE -> encoderSurface
            else -> return
        }
        makeCurrent(activeSurface)
        st.updateTexImage()
        st.getTransformMatrix(stMatrix)

        if (previewSurface != EGL14.EGL_NO_SURFACE) {
            drawCurrentFrameToSurface(previewSurface, previewWidth, previewHeight, flipY = false)
            sampleCenterLumaRough()
        }

        if (encoderSurface != EGL14.EGL_NO_SURFACE) {
            makeCurrent(encoderSurface)
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

        ensurePhotoFbo()
        val surfaceToBind = when {
            previewSurface != EGL14.EGL_NO_SURFACE -> previewSurface
            encoderSurface != EGL14.EGL_NO_SURFACE -> encoderSurface
            else -> return
        }
        makeCurrent(surfaceToBind)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, photoFboId)
        drawQuad(PHOTO_WIDTH, PHOTO_HEIGHT, flipY = true)

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

    private fun drawCurrentFrameToSurface(
        surface: EGLSurface,
        width: Int,
        height: Int,
        flipY: Boolean,
    ) {
        makeCurrent(surface)
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

        // Compute center-crop ratio to ensure authentic 4:3 output even if sensor delivers 16:9
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
            EGL14.eglDestroySurface(eglDisplay, previewSurface)
            previewSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun destroyEncoderSurface() {
        if (encoderSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, encoderSurface)
            encoderSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun makeCurrent(surface: EGLSurface) {
        EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)
    }

    private fun chooseConfig(recordable: Boolean): EGLConfig? {
        val attribList = mutableListOf(
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
