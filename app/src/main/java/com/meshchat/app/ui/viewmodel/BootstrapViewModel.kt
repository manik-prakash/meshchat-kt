package com.meshchat.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.app.data.repository.IdentityRepository
import com.meshchat.app.permission.PermissionHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class BootstrapDest { PENDING, ONBOARDING, PERMISSIONS, NEARBY }

class BootstrapViewModel(
    private val identityRepo: IdentityRepository,
    context: Context
) : ViewModel() {

    private val _dest = MutableStateFlow(BootstrapDest.PENDING)
    val dest: StateFlow<BootstrapDest> = _dest.asStateFlow()

    init {
        // Capture permission state before the coroutine so context is not retained.
        val hasPerms = PermissionHelper.hasCoreBle(context)
        viewModelScope.launch {
            val identity = identityRepo.getIdentity()
            _dest.value = when {
                identity == null || !identity.hasCompletedOnboarding -> BootstrapDest.ONBOARDING
                !hasPerms -> BootstrapDest.PERMISSIONS
                else -> BootstrapDest.NEARBY
            }
        }
    }
}
