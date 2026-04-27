package com.meshchat.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.app.data.repository.IdentityRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class BootstrapDest { PENDING, ONBOARDING, NEARBY }

class BootstrapViewModel(private val identityRepo: IdentityRepository) : ViewModel() {
    private val _dest = MutableStateFlow(BootstrapDest.PENDING)
    val dest: StateFlow<BootstrapDest> = _dest.asStateFlow()

    init {
        viewModelScope.launch {
            val identity = identityRepo.getIdentity()
            _dest.value = if (identity != null && identity.hasCompletedOnboarding)
                BootstrapDest.NEARBY else BootstrapDest.ONBOARDING
        }
    }
}
