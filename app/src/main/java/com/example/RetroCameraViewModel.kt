package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CameraUiState(
    val captureMode: CaptureMode = CaptureMode.PHOTO,
    val flashMode: FlashMode = FlashMode.AUTO,
    val cameraFacing: CameraFacing = CameraFacing.BACK,
    val hasFrontCamera: Boolean = false,
    val hasFlash: Boolean = false,
    val zoomRatio: Float = 1.0f,
    val isZoomSliderVisible: Boolean = false,
    val focusNormalized: Offset? = null,
    val focusEventId: Long = 0L,
    val isCapturing: Boolean = false,
    val isRecording: Boolean = false,
    val recordingSeconds: Int = 0,
    val irisTrigger: IrisTrigger = IrisTrigger(id = 1L, event = IrisAnimEvent.BOOT_OPEN),
    val suctionBitmap: Bitmap? = null,
    val suctionKey: Long = 0L,
    val latestThumbnail: Bitmap? = null,
    val latestMediaIsVideo: Boolean = false,
    val cameraRollItems: List<RetroMediaItem> = emptyList(),
    val statusMessage: String? = null,
)

class RetroCameraViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CameraRollRepository(application)
    private val soundEngine = RetroSoundEngine(application)

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    lateinit var cameraEngine: CameraEngine
        private set

    val shaderRenderer: RetroShaderRenderer = RetroShaderRenderer { cameraSurfaceTexture ->
        cameraEngine.onCameraSurfaceTextureReady(cameraSurfaceTexture)
    }

    private var timerJob: Job? = null
    private var zoomHideJob: Job? = null

    init {
        cameraEngine = CameraEngine(
            context = application,
            shaderRenderer = shaderRenderer,
            repository = repository,
            scope = viewModelScope,
            onError = { msg ->
                _uiState.update { it.copy(statusMessage = msg) }
            },
        )
        shaderRenderer.start()
        refreshCameraRoll()
    }

    fun bindCamera(lifecycleOwner: LifecycleOwner) {
        cameraEngine.initialize(lifecycleOwner) { hasFront, hasFlash ->
            _uiState.update {
                it.copy(
                    hasFrontCamera = hasFront,
                    hasFlash = hasFlash,
                )
            }
        }
    }

    fun attachPreviewSurface(surface: Any, width: Int, height: Int) {
        shaderRenderer.attachPreviewSurface(surface, width, height)
    }

    fun updatePreviewSize(width: Int, height: Int) {
        shaderRenderer.updatePreviewSize(width, height)
    }

    fun detachPreviewSurface() {
        shaderRenderer.detachPreviewSurfaceSync()
    }

    fun toggleMode(targetMode: CaptureMode) {
        val current = _uiState.value
        if (current.captureMode == targetMode || current.isRecording || current.isCapturing) return

        cameraEngine.setCaptureMode(targetMode)
        _uiState.update {
            it.copy(
                captureMode = targetMode,
                irisTrigger = IrisTrigger(System.nanoTime(), IrisAnimEvent.MODE_SWITCH),
            )
        }
    }

    fun cycleFlashMode() {
        val next = when (_uiState.value.flashMode) {
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON -> FlashMode.OFF
            FlashMode.OFF -> FlashMode.AUTO
        }
        cameraEngine.setFlashMode(next)
        _uiState.update { it.copy(flashMode = next) }
    }

    fun toggleFrontBackCamera() {
        val current = _uiState.value
        if (!current.hasFrontCamera || current.isRecording || current.isCapturing) return

        val nextFacing = if (current.cameraFacing == CameraFacing.BACK) {
            CameraFacing.FRONT
        } else {
            CameraFacing.BACK
        }
        _uiState.update {
            it.copy(
                cameraFacing = nextFacing,
                zoomRatio = 1.0f,
                irisTrigger = IrisTrigger(System.nanoTime(), IrisAnimEvent.MODE_SWITCH),
            )
        }
        cameraEngine.setZoom(1.0f)
        cameraEngine.switchFacing(nextFacing) { hasFlash ->
            _uiState.update { it.copy(hasFlash = hasFlash) }
        }
    }

    fun onViewfinderTap(normalizedX: Float, normalizedY: Float, viewWidth: Float, viewHeight: Float) {
        soundEngine.playFocusTick()
        cameraEngine.tapToFocus(
            x = normalizedX * viewWidth,
            y = normalizedY * viewHeight,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
        )
        _uiState.update {
            it.copy(
                focusNormalized = Offset(normalizedX, normalizedY),
                focusEventId = System.nanoTime(),
                isZoomSliderVisible = true,
            )
        }
        scheduleZoomHide()
    }

    fun onZoomChanged(zoom: Float) {
        val clamped = zoom.coerceIn(1.0f, 5.0f)
        cameraEngine.setZoom(clamped)
        _uiState.update { it.copy(zoomRatio = clamped, isZoomSliderVisible = true) }
        scheduleZoomHide()
    }

    fun showZoomSlider() {
        _uiState.update { it.copy(isZoomSliderVisible = true) }
        scheduleZoomHide()
    }

    fun onShutterPressed() {
        val state = _uiState.value
        if (state.captureMode == CaptureMode.PHOTO) {
            capturePhoto()
        } else {
            toggleVideoRecording()
        }
    }

    private fun capturePhoto() {
        if (_uiState.value.isCapturing) return
        _uiState.update {
            it.copy(
                isCapturing = true,
                irisTrigger = IrisTrigger(System.nanoTime(), IrisAnimEvent.CAPTURE_SNAP),
            )
        }
        soundEngine.playShutterClick()

        viewModelScope.launch {
            val result = cameraEngine.captureRetroPhoto()
            if (result != null) {
                val (item, bitmap) = result
                val previewThumb = Bitmap.createScaledBitmap(bitmap, 256, 192, true)
                _uiState.update { state ->
                    state.copy(
                        isCapturing = false,
                        suctionBitmap = previewThumb,
                        suctionKey = System.nanoTime(),
                        latestThumbnail = previewThumb,
                        latestMediaIsVideo = false,
                        cameraRollItems = listOf(item) + state.cameraRollItems,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(isCapturing = false, statusMessage = "Could not save photo")
                }
            }
        }
    }

    private fun toggleVideoRecording() {
        if (_uiState.value.isRecording) {
            stopVideoRecording()
        } else {
            startVideoRecording()
        }
    }

    private fun startVideoRecording() {
        soundEngine.playVideoStartBeep()
        val started = cameraEngine.startVideoRecording()
        if (started) {
            _uiState.update { it.copy(isRecording = true, recordingSeconds = 0) }
            timerJob?.cancel()
            timerJob = viewModelScope.launch {
                while (true) {
                    delay(1000L)
                    _uiState.update { it.copy(recordingSeconds = it.recordingSeconds + 1) }
                }
            }
        }
    }

    private fun stopVideoRecording() {
        timerJob?.cancel()
        timerJob = null
        soundEngine.playVideoStopBeep()
        _uiState.update { it.copy(isRecording = false) }

        viewModelScope.launch {
            val result = cameraEngine.stopVideoRecording()
            if (result != null) {
                val (item, thumb) = result
                _uiState.update { state ->
                    state.copy(
                        latestThumbnail = thumb ?: state.latestThumbnail,
                        latestMediaIsVideo = true,
                        cameraRollItems = listOf(item) + state.cameraRollItems,
                    )
                }
            }
        }
    }

    fun refreshCameraRoll() {
        viewModelScope.launch {
            val items = repository.loadRetroCameraRoll()
            val latest = items.firstOrNull()
            val thumb = latest?.let { repository.loadThumbnail(it) }
            _uiState.update {
                it.copy(
                    cameraRollItems = items,
                    latestThumbnail = thumb,
                    latestMediaIsVideo = latest?.isVideo == true,
                )
            }
        }
    }

    suspend fun loadExif(item: RetroMediaItem): ExifMetadataSummary {
        return repository.readExifSummary(item)
    }

    fun deleteMediaItem(item: RetroMediaItem) {
        viewModelScope.launch {
            if (repository.deleteItem(item)) {
                refreshCameraRoll()
            }
        }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    private fun scheduleZoomHide() {
        zoomHideJob?.cancel()
        zoomHideJob = viewModelScope.launch {
            delay(3200L)
            _uiState.update { it.copy(isZoomSliderVisible = false) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        zoomHideJob?.cancel()
        cameraEngine.release()
        shaderRenderer.release()
        soundEngine.release()
    }
}
