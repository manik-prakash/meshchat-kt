package com.meshchat.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.app.data.db.entity.NeighborEntity
import com.meshchat.app.data.repository.ConversationRepository
import com.meshchat.app.data.repository.MeshRepository
import com.meshchat.app.domain.Conversation
import com.meshchat.app.domain.Peer
import com.meshchat.app.runtime.MeshRuntimeRepository
import com.meshchat.app.runtime.MeshRuntimeStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class NearbyUiState(
    val peers: List<Peer> = emptyList(),
    val neighbors: List<NeighborEntity> = emptyList(),
    val runtimeStatus: MeshRuntimeStatus = MeshRuntimeStatus(),
    val error: String? = null
)

class NearbyViewModel(
    private val conversationRepository: ConversationRepository,
    private val meshRepository: MeshRepository,
    private val meshRuntimeRepository: MeshRuntimeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(NearbyUiState())
    val uiState: StateFlow<NearbyUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                conversationRepository.getPeers(),
                meshRepository.getNeighbors(),
                meshRuntimeRepository.runtimeStatus
            ) { peers, neighbors, runtimeStatus ->
                NearbyUiState(
                    peers = peers,
                    neighbors = neighbors,
                    runtimeStatus = runtimeStatus,
                    error = _uiState.value.error
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun startScan() {
        _uiState.value = _uiState.value.copy(error = null)
        viewModelScope.launch {
            try {
                meshRuntimeRepository.requestDiscoveryNow()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    suspend fun connectAndGetConversation(peer: Peer): Conversation? =
        meshRuntimeRepository.connectAndOpen(peer)
}
