package com.meshchat.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.app.data.repository.IdentityRepository
import com.meshchat.app.domain.Identity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class OnboardingViewModel(private val identityRepo: IdentityRepository) : ViewModel() {

    private val _identity = MutableStateFlow<Identity?>(null)
    val identity: StateFlow<Identity?> = _identity.asStateFlow()

    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    init {
        viewModelScope.launch {
            val existing = identityRepo.ensureIdentity()
            _identity.value = existing
        }
    }

    fun confirmName(name: String) {
        viewModelScope.launch {
            val trimmed = name.trim().take(24)
            if (trimmed.isNotEmpty()) {
                identityRepo.updateDisplayName(trimmed)
                _identity.value = _identity.value?.copy(displayName = trimmed)
            }
            _done.value = true
        }
    }
}
