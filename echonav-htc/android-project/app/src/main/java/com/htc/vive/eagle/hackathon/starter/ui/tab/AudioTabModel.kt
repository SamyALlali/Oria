package com.htc.vive.eagle.hackathon.starter.ui.tab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.htc.vive.eagle.hackathon.starter.ViveGlassKitManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AudioUiState(
    val isRecording: Boolean = false,
    val isAudioPlaying : Boolean = false,
    val isSimulator : Boolean = false
)

sealed interface AudioEvent{
    data object PlaySampleAudioClick : AudioEvent
    data object StartRecordingClick : AudioEvent
}

class AudioTabModel(viveGlassKitManager: ViveGlassKitManager) : ViewModel()
{
    private val manager:ViveGlassKitManager = viveGlassKitManager
    private var _uiState = MutableStateFlow(AudioUiState())
    val uiState: StateFlow<AudioUiState> = _uiState

    init {
        viewModelScope.launch {
            manager.isSampleAudioPlaying.collect{ isPlaying ->
                _uiState.update {
                    it.copy(isAudioPlaying = isPlaying)
                }
            }
        }

        viewModelScope.launch {
            manager.isAudioRecording.collect { recording ->
                _uiState.update {
                    it.copy(isRecording = recording)
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

    fun onEvent(event: AudioEvent)
    {
        when(event){

            AudioEvent.PlaySampleAudioClick -> {
                if(!uiState.value.isAudioPlaying)
                    manager.playSampleAudio()
                else
                    manager.stopSampleAudio()
            }

            AudioEvent.StartRecordingClick -> {
                if(!uiState.value.isRecording)
                    viewModelScope.launch {
                        manager.startAudioStreaming()
                    }
                else
                    manager.stopAudioStreaming()
            }
        }
    }
}