package com.meshchat.app.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RelayActivity(
    val packetId: String,
    val sourceDisplayName: String,
    val destinationDisplayName: String,
    val timestamp: Long
)

class RelayActivityTracker {
    private val _latestRelay = MutableStateFlow<RelayActivity?>(null)
    val latestRelay: StateFlow<RelayActivity?> = _latestRelay.asStateFlow()

    fun recordRelay(
        packetId: String,
        sourceDisplayName: String,
        destinationDisplayName: String,
        timestamp: Long = System.currentTimeMillis()
    ) {
        _latestRelay.value = RelayActivity(
            packetId = packetId,
            sourceDisplayName = sourceDisplayName,
            destinationDisplayName = destinationDisplayName,
            timestamp = timestamp
        )
    }
}
