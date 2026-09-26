package com.htc.vive.eagle.hackathon.starter.ui.tab

import android.graphics.Bitmap
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.htc.vive.eagle.hackathon.starter.ViveGlassKitManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class CameraUiState(
    val isVideoRecording : Boolean = false,
    val isImageCapturing : Boolean = false,
    val isSimulator : Boolean = false,
    val previewRatio : Float = 1.8f
)

sealed interface CameraEvent{
    data object TakePhotoClicked : CameraEvent
    data object StartRecordingClicked : CameraEvent
    data object StopRecordingClicked : CameraEvent
}

const val TAG = "CameraTab"
class CameraTabModel(viveGlassKitManager: ViveGlassKitManager) : ViewModel()
{
    private val manager : ViveGlassKitManager = viveGlassKitManager
    private var _uiState = MutableStateFlow(CameraUiState())
    val uiState : StateFlow<CameraUiState> = _uiState

    private val _latestImageReceived = MutableStateFlow<Bitmap?>(null)
    val latestImageReceived : StateFlow<Bitmap?> = _latestImageReceived

    init {
        viewModelScope.launch{
            manager.imageReceived.collect {bmp ->
                _latestImageReceived.value = bmp
            }
        }
        viewModelScope.launch {

            manager.isVideoStreaming.collect { isStreaming->
                _uiState.update {
                    it.copy(isVideoRecording = isStreaming)
                }
            }
        }

        viewModelScope.launch {
            manager.isImageCapturing.collect { isCapturing ->
                _uiState.update {
                    it.copy(isImageCapturing = isCapturing)
                }
            }
        }

        viewModelScope.launch {
            manager.previewRatio.collect{ ratio ->
                _uiState.update {
                    it.copy(previewRatio = ratio)
                }
            }
        }

        viewModelScope.launch {
            manager.isSimulator.collect { simulator ->
                _uiState.update {
                    it.copy(isSimulator=simulator)
                }
            }
        }
    }

    fun onEvent(event: CameraEvent)
    {
        when(event)
        {
            CameraEvent.TakePhotoClicked->{
                clearPreview()
                viewModelScope.launch {
                    try {
                        manager.captureImage()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            CameraEvent.StartRecordingClicked ->{
                clearPreview()
                viewModelScope.launch {
                    try {
                        manager.startVideoStreaming()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            CameraEvent.StopRecordingClicked ->{
                manager.stopVideoStreaming()
            }
        }
    }

    fun clearPreview()
    {
        _latestImageReceived.value = null
    }

    fun attachSurface(surface: Surface) {
        manager.attachPreviewSurface(surface)
    }

    fun detachSurface()
    {
        manager.detachAndStopPreview()
    }
}
