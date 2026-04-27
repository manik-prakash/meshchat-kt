package com.meshchat.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.app.ble.BleMeshManager
import com.meshchat.app.ble.BleSyncCoordinator
import com.meshchat.app.ble.BleState
import com.meshchat.app.domain.Conversation
import com.meshchat.app.domain.Peer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NearbyUiState(
    val peers: List<Peer> = emptyList(),
    val bleState: BleState = BleState.IDLE,
    val error: String? = null
)

class NearbyViewModel(
    private val bleMeshManager: BleMeshManager,
    private val coordinator: BleSyncCoordinator
) : ViewModel() {

    private val _uiState = MutableStateFlow(NearbyUiState())
    val uiState: StateFlow<NearbyUiState> = _uiState.asStateFlow()

    // Peers accumulated during a scan
    private val scanPeers = mutableListOf<Peer>()

    init {
        viewModelScope.launch {
            bleMeshManager.state.collect { state ->
                _uiState.value = _uiState.value.copy(bleState = state)
            }
        }
        viewModelScope.launch {
            bleMeshManager.events.collect { event ->
                if (event is BleMeshManager.BleEvent.PeerDiscovered) {
                    val peer = event.peer
                    val idx = scanPeers.indexOfFirst { it.displayName == peer.displayName }
                    if (idx >= 0) scanPeers[idx] = peer else scanPeers.add(peer)
                    _uiState.value = _uiState.value.copy(peers = scanPeers.toList())
                }
            }
        }
    }

    fun startScan() {
        scanPeers.clear()
        _uiState.value = _uiState.value.copy(peers = emptyList(), error = null)
        viewModelScope.launch {
            try {
                bleMeshManager.startScan()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    /** Connect to a peer, wait for their handshake, and return the resolved Conversation. */
    suspend fun connectAndGetConversation(peer: Peer): Conversation? {
        val bleId = peer.bleId ?: return null
        return coordinator.connectAndOpen(bleId)
    }

    fun stopScan() = bleMeshManager.stopScan()

    override fun onCleared() {
        super.onCleared()
        bleMeshManager.stopScan()
    }
}
