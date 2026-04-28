package com.meshchat.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.app.data.repository.IdentityRepository
import com.meshchat.app.domain.Identity
import com.meshchat.app.runtime.MeshRuntimeRepository
import com.meshchat.app.runtime.MeshRuntimeStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val identityRepo: IdentityRepository,
    private val meshRuntimeRepository: MeshRuntimeRepository
) : ViewModel() {

    private val _identity = MutableStateFlow<Identity?>(null)
    val identity: StateFlow<Identity?> = _identity.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    /** True when the persistent relay toggle was flipped on but background location is missing. */
    private val _pendingRelayEnable = MutableStateFlow(false)
    val pendingRelayEnable: StateFlow<Boolean> = _pendingRelayEnable.asStateFlow()

    val runtimeStatus: StateFlow<MeshRuntimeStatus> = meshRuntimeRepository.runtimeStatus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), meshRuntimeRepository.runtimeStatus.value)

    init {
        viewModelScope.launch {
            _identity.value = identityRepo.ensureIdentity()
        }
    }

    fun saveDisplayName(name: String) {
        val trimmed = name.trim().take(24)
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            identityRepo.updateDisplayName(trimmed)
            _identity.value = _identity.value?.copy(displayName = trimmed)
            _saved.value = true
        }
    }

    /**
     * Called when the persistent mesh switch changes.
     * [hasBgLocation] is provided by the Screen, which can check the permission at call time.
     */
    fun setPersistentMeshEnabled(enabled: Boolean, hasBgLocation: Boolean = true) {
        if (enabled && !hasBgLocation) {
            _pendingRelayEnable.value = true
        } else {
            _pendingRelayEnable.value = false
            meshRuntimeRepository.setPersistentMeshEnabled(enabled)
        }
    }

    /** Called after the background location permission dialog resolves. */
    fun onBgLocationResult(granted: Boolean) {
        _pendingRelayEnable.value = false
        // Enable relay regardless — degraded (foreground-only) relay is still useful.
        meshRuntimeRepository.setPersistentMeshEnabled(true)
    }

    fun dismissBgLocationPrompt() {
        _pendingRelayEnable.value = false
    }

    fun clearSaved() { _saved.value = false }
}
