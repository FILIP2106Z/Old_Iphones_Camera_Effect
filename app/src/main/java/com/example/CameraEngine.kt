package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

enum class CaptureMode { PHOTO, VIDEO }
enum class FlashMode { AUTO, ON, OFF }
enum class CameraFacing { BACK, FRONT }

/**
 * CameraX + Camera2Interop engine powering unprocessed 2009 sensor capture,
 * tap-to-focus AE/AF lock, positive EV highlight clipping bias,
 * 2048x1536 JPEG FBO capture, and 640x480 2.2Mbps H.264/AAC video recording.
 */
class CameraEngine(
    private val context: Context,
    private val shaderRenderer: RetroShaderRenderer,
    private val repository: CameraRollRepository,
    private val scope: CoroutineScope,
    private val onError: (String) -> Unit,
) {
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var activeSurface: Surface? = null
    private var cameraSurfaceTexture: SurfaceTexture? = null
    private var lifecycleOwner: LifecycleOwner? = null

    private var currentFacing: CameraFacing = CameraFacing.BACK
    private var currentFlash: FlashMode = FlashMode.AUTO
    private var currentMode: CaptureMode = CaptureMode.PHOTO
    private var currentZoom: Float = 1.0f

    private var videoRecorder: RetroVideoRecorder? = null

    fun initialize(owner: LifecycleOwner, onBound: (hasFrontCamera: Boolean, hasFlash: Boolean) -> Unit) {
        lifecycleOwner = owner
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val hasFront = runCatching {
                    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                }.getOrDefault(false)
                bindCameraUseCases(onBound = { hasFlash -> onBound(hasFront, hasFlash) })
            } catch (e: Exception) {
                onError(e.localizedMessage ?: "Camera initialization failed")
            }
        }, mainExecutor)
    }

    fun onCameraSurfaceTextureReady(surfaceTexture: SurfaceTexture) {
        cameraSurfaceTexture = surfaceTexture
        Handler(Looper.getMainLooper()).post {
            if (cameraProvider != null && lifecycleOwner != null) {
                bindCameraUseCases()
            }
        }
    }

    fun switchFacing(facing: CameraFacing, onBound: (hasFlash: Boolean) -> Unit) {
        currentFacing = facing
        bindCameraUseCases(onBound)
    }

    fun setCaptureMode(mode: CaptureMode) {
        currentMode = mode
        updateTorchForVideoIdle()
    }

    fun setFlashMode(flash: FlashMode) {
        currentFlash = flash
        updateTorchForVideoIdle()
    }

    fun setZoom(zoomRatio: Float) {
        currentZoom = zoomRatio.coerceIn(1.0f, 5.0f)
        val cam = camera
        if (cam != null) {
            val zoomState = cam.cameraInfo.zoomState.value
            val minHw = zoomState?.minZoomRatio ?: 1.0f
            val maxHw = zoomState?.maxZoomRatio ?: 1.0f
            val targetHw = currentZoom.coerceIn(minHw, maxHw)
            cam.cameraControl.setZoomRatio(targetHw)
            val remainingShaderZoom = (currentZoom / targetHw).coerceIn(1.0f, 5.0f)
            shaderRenderer.setShaderZoom(remainingShaderZoom)
        } else {
            shaderRenderer.setShaderZoom(currentZoom)
        }
    }

    fun tapToFocus(x: Float, y: Float, viewWidth: Float, viewHeight: Float) {
        val cam = camera ?: return
        if (viewWidth <= 0f || viewHeight <= 0f) return
        val factory = SurfaceOrientedMeteringPointFactory(viewWidth, viewHeight)
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        )
            .setAutoCancelDuration(5, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    suspend fun captureRetroPhoto(): Pair<RetroMediaItem, Bitmap>? = withContext(Dispatchers.Main) {
        val cam = camera
        val hasFlash = cam?.cameraInfo?.hasFlashUnit() == true && currentFacing == CameraFacing.BACK
        val shouldFlash = hasFlash && when (currentFlash) {
            FlashMode.ON -> true
            FlashMode.AUTO -> shaderRenderer.getEstimatedSceneLuma() < 0.28f
            FlashMode.OFF -> false
        }

        if (shouldFlash && cam != null) {
            cam.cameraControl.enableTorch(true)
            delay(280)
        }

        val deferred = CompletableDeferred<Bitmap>()
        shaderRenderer.capturePhoto2048x1536 { bitmap ->
            deferred.complete(bitmap)
        }
        val capturedBitmap = withTimeoutOrNull(2500L) { deferred.await() }

        if (shouldFlash && cam != null) {
            cam.cameraControl.enableTorch(false)
        }

        if (capturedBitmap == null) return@withContext null

        val savedItem = repository.saveJpeg2048x1536WithExif(capturedBitmap)
        if (savedItem != null) {
            savedItem to capturedBitmap
        } else {
            null
        }
    }

    fun startVideoRecording(): Boolean {
        if (videoRecorder != null) return false
        val cam = camera
        if (cam != null && cam.cameraInfo.hasFlashUnit() && currentFacing == CameraFacing.BACK) {
            val useTorch = currentFlash == FlashMode.ON ||
                (currentFlash == FlashMode.AUTO && shaderRenderer.getEstimatedSceneLuma() < 0.25f)
            cam.cameraControl.enableTorch(useTorch)
        }

        val outputFile = File(context.cacheDir, "rec_3gs_${System.currentTimeMillis()}.mp4")
        val hasAudioPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        val recorder = RetroVideoRecorder(
            outputFile = outputFile,
            recordAudio = hasAudioPerm,
            shaderRenderer = shaderRenderer,
            onError = onError,
        )
        return if (recorder.start()) {
            videoRecorder = recorder
            true
        } else {
            cam?.cameraControl?.enableTorch(false)
            false
        }
    }

    suspend fun stopVideoRecording(): Pair<RetroMediaItem, Bitmap?>? {
        val recorder = videoRecorder ?: return null
        videoRecorder = null
        camera?.cameraControl?.enableTorch(false)

        val (mp4File, durationMs) = withContext(Dispatchers.IO) {
            recorder.stop()
        }
        if (mp4File == null || durationMs <= 0L) return null

        val savedItem = repository.saveVideoMp4ToMediaStore(mp4File, durationMs) ?: return null
        val thumb = repository.loadThumbnail(savedItem)
        return savedItem to thumb
    }

    fun release() {
        scope.launch(Dispatchers.IO) {
            videoRecorder?.stop()
            videoRecorder = null
        }
        activeSurface?.release()
        activeSurface = null
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
    }

    private fun updateTorchForVideoIdle() {
        val cam = camera ?: return
        if (videoRecorder != null && currentMode == CaptureMode.VIDEO && currentFacing == CameraFacing.BACK && cam.cameraInfo.hasFlashUnit()) {
            cam.cameraControl.enableTorch(currentFlash == FlashMode.ON)
        } else if (videoRecorder == null) {
            cam.cameraControl.enableTorch(false)
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindCameraUseCases(onBound: ((hasFlash: Boolean) -> Unit)? = null) {
        val provider = cameraProvider ?: return
        val owner = lifecycleOwner ?: return
        val st = cameraSurfaceTexture ?: return

        val hasFront = runCatching { provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
        val hasBack = runCatching { provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
        val selector = when {
            currentFacing == CameraFacing.FRONT && hasFront -> CameraSelector.DEFAULT_FRONT_CAMERA
            hasBack -> {
                currentFacing = CameraFacing.BACK
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            hasFront -> {
                currentFacing = CameraFacing.FRONT
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            else -> {
                onError("No camera hardware available")
                return
            }
        }

        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                AspectRatioStrategy(
                    AspectRatio.RATIO_4_3,
                    AspectRatioStrategy.FALLBACK_RULE_AUTO,
                ),
            )
            .build()

        val previewBuilder = Preview.Builder()
            .setResolutionSelector(resolutionSelector)

        // Camera2Interop tuning: strip modern sharpening, denoising, antibanding & auto-HDR
        Camera2Interop.Extender(previewBuilder).apply {
            setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF,
            )
            setCaptureRequestOption(
                CaptureRequest.CONTROL_SCENE_MODE,
                CaptureRequest.CONTROL_SCENE_MODE_DISABLED,
            )
            setCaptureRequestOption(
                CaptureRequest.CONTROL_MODE,
                CaptureRequest.CONTROL_MODE_AUTO,
            )
            setCaptureRequestOption(
                CaptureRequest.EDGE_MODE,
                CaptureRequest.EDGE_MODE_OFF,
            )
            setCaptureRequestOption(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CaptureRequest.NOISE_REDUCTION_MODE_OFF,
            )
        }

        val preview = previewBuilder.build()
        preview.setSurfaceProvider(cameraExecutor) { request ->
            val res = request.resolution
            st.setDefaultBufferSize(res.width, res.height)
            shaderRenderer.setStreamResolution(res.width, res.height)
            activeSurface?.release()
            val surface = Surface(st)
            activeSurface = surface
            request.provideSurface(surface, cameraExecutor) {
                // Surface released when CameraX finishes
            }
        }

        try {
            provider.unbindAll()
            val boundCamera = provider.bindToLifecycle(owner, selector, preview)
            camera = boundCamera

            // Apply positive exposure compensation (+1.2 EV) for authentic 2009 sky blowout
            val exposureState = boundCamera.cameraInfo.exposureState
            if (exposureState.isExposureCompensationSupported) {
                val step = exposureState.exposureCompensationStep.toFloat()
                if (step > 0f) {
                    val targetIdx = (1.2f / step).roundToInt().coerceIn(
                        exposureState.exposureCompensationRange.lower,
                        exposureState.exposureCompensationRange.upper,
                    )
                    boundCamera.cameraControl.setExposureCompensationIndex(targetIdx)
                }
            }

            setZoom(currentZoom)
            onBound?.invoke(boundCamera.cameraInfo.hasFlashUnit() && currentFacing == CameraFacing.BACK)
        } catch (e: Exception) {
            onError(e.localizedMessage ?: "Failed to bind camera")
        }
    }
}

/**
 * Hardware-accelerated 640x480 @ 30fps 2.2 Mbps H.264 Baseline + AAC MP4 recorder
 * consuming shader-processed GL frames via MediaCodec input Surface.
 */
private class RetroVideoRecorder(
    private val outputFile: File,
    private val recordAudio: Boolean,
    private val shaderRenderer: RetroShaderRenderer,
    private val onError: (String) -> Unit,
) {
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private val muxerLock = Object()

    private val isRecording = AtomicBoolean(false)
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var startTimeMs: Long = 0L

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        return try {
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val vCodec = createVideoCodec()
            videoCodec = vCodec
            val surface = vCodec.createInputSurface()
            inputSurface = surface
            vCodec.start()

            if (recordAudio) {
                val sampleRate = 44100
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(4096)

                val ar = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2,
                )
                if (ar.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord = ar
                    val audioFormat = MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_AAC,
                        sampleRate,
                        1,
                    ).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf * 2)
                    }
                    val aCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    aCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    aCodec.start()
                    audioCodec = aCodec
                } else {
                    ar.release()
                }
            }

            isRecording.set(true)
            startTimeMs = System.currentTimeMillis()
            shaderRenderer.attachEncoderSurface(surface)

            videoThread = Thread({ drainVideoEncoder() }, "RetroVideoDrain").apply { start() }
            if (audioCodec != null && audioRecord != null) {
                audioRecord?.startRecording()
                audioThread = Thread({ encodeAndDrainAudio() }, "RetroAudioEncode").apply { start() }
            }
            true
        } catch (e: Exception) {
            onError(e.localizedMessage ?: "Video recorder start failed")
            cleanup()
            false
        }
    }

    fun stop(): Pair<File?, Long> {
        if (!isRecording.getAndSet(false)) {
            return null to 0L
        }
        val durationMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(500L)

        shaderRenderer.detachEncoderSurface()
        runCatching { videoCodec?.signalEndOfInputStream() }
        runCatching { videoThread?.join(2500) }
        runCatching { audioThread?.join(2500) }

        cleanup()
        return if (outputFile.exists() && outputFile.length() > 0L) {
            outputFile to durationMs
        } else {
            null to 0L
        }
    }

    private fun createVideoCodec(): MediaCodec {
        val baseFormat = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            RetroShaderRenderer.VIDEO_WIDTH,
            RetroShaderRenderer.VIDEO_HEIGHT,
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_200_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        // Attempt H.264 Baseline Profile first; fall back cleanly if emulator encoder rejects explicit profile
        return try {
            val baselineFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                RetroShaderRenderer.VIDEO_WIDTH,
                RetroShaderRenderer.VIDEO_HEIGHT,
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 2_200_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel3)
            }
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(baselineFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        } catch (_: Exception) {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(baseFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        }
    }

    private fun drainVideoEncoder() {
        val codec = videoCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        var eosWaitRetries = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!isRecording.get()) {
                        eosWaitRetries++
                        if (eosWaitRetries > 120) break
                    }
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        if (!muxerStarted) {
                            videoTrackIndex = muxer?.addTrack(codec.outputFormat) ?: -1
                            maybeStartMuxerLocked()
                        }
                    }
                }
                idx >= 0 -> {
                    val encodedData: ByteBuffer? = codec.getOutputBuffer(idx)
                    if (encodedData != null && bufferInfo.size > 0 &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        waitForMuxerStart()
                        synchronized(muxerLock) {
                            if (muxerStarted && videoTrackIndex >= 0) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer?.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        }
    }

    private fun encodeAndDrainAudio() {
        val ar = audioRecord ?: return
        val codec = audioCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        val tempBuf = ByteArray(2048)
        val audioStartNs = System.nanoTime()

        while (isRecording.get()) {
            val readBytes = ar.read(tempBuf, 0, tempBuf.size)
            if (readBytes > 0) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)
                    inBuf?.clear()
                    inBuf?.put(tempBuf, 0, readBytes)
                    val ptsUs = (System.nanoTime() - audioStartNs) / 1000L
                    codec.queueInputBuffer(inIdx, 0, readBytes, ptsUs, 0)
                }
            }
            drainAudioOutput(codec, bufferInfo, endOfStream = false)
        }

        val eosIdx = codec.dequeueInputBuffer(10_000)
        if (eosIdx >= 0) {
            val ptsUs = (System.nanoTime() - audioStartNs) / 1000L
            codec.queueInputBuffer(eosIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drainAudioOutput(codec, bufferInfo, endOfStream = true)
    }

    private fun drainAudioOutput(codec: MediaCodec, bufferInfo: MediaCodec.BufferInfo, endOfStream: Boolean) {
        while (true) {
            val idx = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        if (!muxerStarted) {
                            audioTrackIndex = muxer?.addTrack(codec.outputFormat) ?: -1
                            maybeStartMuxerLocked()
                        }
                    }
                }
                idx >= 0 -> {
                    val encodedData = codec.getOutputBuffer(idx)
                    if (encodedData != null && bufferInfo.size > 0 &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        waitForMuxerStart()
                        synchronized(muxerLock) {
                            if (muxerStarted && audioTrackIndex >= 0) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer?.writeSampleData(audioTrackIndex, encodedData, bufferInfo)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        return
                    }
                }
            }
        }
    }

    private fun maybeStartMuxerLocked() {
        val expectsAudio = audioCodec != null
        val ready = videoTrackIndex >= 0 && (!expectsAudio || audioTrackIndex >= 0)
        if (ready && !muxerStarted) {
            muxer?.start()
            muxerStarted = true
            muxerLock.notifyAll()
        }
    }

    private fun waitForMuxerStart() {
        synchronized(muxerLock) {
            val deadline = System.currentTimeMillis() + 1500L
            while (!muxerStarted && System.currentTimeMillis() < deadline) {
                runCatching { muxerLock.wait(200) }
            }
        }
    }

    private fun cleanup() {
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null

        runCatching { videoCodec?.stop() }
        runCatching { videoCodec?.release() }
        videoCodec = null

        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        audioCodec = null

        synchronized(muxerLock) {
            if (muxerStarted) {
                runCatching { muxer?.stop() }
                muxerStarted = false
            }
            runCatching { muxer?.release() }
            muxer = null
        }

        inputSurface?.release()
        inputSurface = null
    }
}
