package com.htc.vive.eagle.hackathon.starter.ui.tab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.htc.vive.eagle.hackathon.starter.ViveGlassKitManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GlassUiState(
    val isConnected : Boolean = false,
    val textConnectButton : String = "Connect",
    val isSimulator : Boolean = false
)

enum class DeviceMode(val label: String){
    EAGLE("Eagle"),
    SIMULATOR("Simulator"),
    NULL("")
}

sealed interface GlassEvent{
    data object ConnectClicked : GlassEvent
    data class DeviceModeChanged(val mode : DeviceMode) : GlassEvent
}

class GlassTabModel(viveGlassKitManager: ViveGlassKitManager): ViewModel()
{
    private val manager:ViveGlassKitManager = viveGlassKitManager
    private val _uiState = MutableStateFlow(GlassUiState())
    val uiState: StateFlow<GlassUiState> = _uiState

    init {
        viewModelScope.launch {
            manager.connection.collect { connected ->
                _uiState.update {
                    it.copy(
                        isConnected = connected,
                        textConnectButton = if (!connected) "Connect" else "Disconnect"
                    )
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

    fun onEvent(event: GlassEvent)
    {
        when (event){
            GlassEvent.ConnectClicked ->{
                if(!uiState.value.isConnected)
                    manager.connect()
                else
                    manager.disconnect()
            }

            is GlassEvent.DeviceModeChanged ->{

                when(event.mode)
                {
                    DeviceMode.EAGLE ->
                    {
                        manager.setSimulator(false)
                    }
                    DeviceMode.SIMULATOR ->
                    {
                        manager.setSimulator(true)
                    }
                    else -> {}
                }
            }
        }
    }
}