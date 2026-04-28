package com.meshchat.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import com.meshchat.app.permission.PermissionHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MeshCapability(
    val hasHardware: Boolean          = true,
    val canScan: Boolean              = false,
    val canAdvertise: Boolean         = false,
    val hasLocation: Boolean          = false,
    val hasBackgroundLocation: Boolean = false,
    val hasNotifications: Boolean     = false
) {
    /** All permissions that improve UX are granted. */
    val fullyEnabled: Boolean get() = canScan && canAdvertise && hasLocation
    /** Can receive but not be passively discovered. */
    val advertisingDegraded: Boolean get() = canScan && !canAdvertise
    /** Core BLE missing — mesh is non-functional. */
    val meshUnavailable: Boolean get() = !hasHardware || !canScan
}

class PermissionViewModel : ViewModel() {
    private val _capability = MutableStateFlow(MeshCapability())
    val capability: StateFlow<MeshCapability> = _capability.asStateFlow()

    fun refresh(context: Context) {
        _capability.value = MeshCapability(
            hasHardware           = PermissionHelper.hasBluetoothHardware(context),
            canScan               = PermissionHelper.hasCoreBle(context),
            canAdvertise          = PermissionHelper.hasAdvertising(context),
            hasLocation           = PermissionHelper.hasLocation(context),
            hasBackgroundLocation = PermissionHelper.hasBackgroundLocation(context),
            hasNotifications      = PermissionHelper.hasNotifications(context)
        )
    }
}
