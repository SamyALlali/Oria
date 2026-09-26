package com.htc.vive.eagle.hackathon.starter.ui.tab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.htc.vive.eagle.hackathon.starter.ViveGlassKitManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val textReceivedMsg : String = "",
    val textTTSInput : String ="",
    val isSimulator: Boolean = false,
    val isStartTranscribe : Boolean =false
)

sealed interface ChatEvent {
    data class TTSTextFieldChanged(val value : String ) :ChatEvent
    data object SendTTSClicked : ChatEvent
    data object STTReceivedMsgClicked : ChatEvent
}

class ChatTabModel( viveGlassKitManager: ViveGlassKitManager
) : ViewModel()
{
    val manager:ViveGlassKitManager = viveGlassKitManager
    val _uiState = MutableStateFlow(ChatUiState())
    val uiState : StateFlow<ChatUiState> = _uiState

    init {
        viewModelScope.launch {
            manager.isSimulator.collect { simulator ->
                _uiState.update {
                    it.copy(isSimulator=simulator)
                }
            }
        }

        viewModelScope.launch {
            manager.textTranscribed.collect {text ->
                _uiState.update {
                    it.copy(textReceivedMsg = text)
                }
            }
        }

        viewModelScope.launch {
            manager.isStartTranscribe.collect { isStart->
                _uiState.update {
                    it.copy(isStartTranscribe = isStart)
                }
            }
        }
    }

    fun onEvent(event: ChatEvent){
        when(event){
            is ChatEvent.TTSTextFieldChanged ->{
                _uiState.update { it.copy(textTTSInput = event.value) }
            }

            ChatEvent.SendTTSClicked ->{
                manager.speakText(uiState.value.textTTSInput)
            }

            ChatEvent.STTReceivedMsgClicked ->{
                viewModelScope.launch {
                    manager.startTranscription()
                }
            }
        }
    }
}