package com.meshchat.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.app.data.repository.IdentityRepository
import com.meshchat.app.domain.Identity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(private val identityRepo: IdentityRepository) : ViewModel() {

    private val _identity = MutableStateFlow<Identity?>(null)
    val identity: StateFlow<Identity?> = _identity.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

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

    fun clearSaved() { _saved.value = false }
}
