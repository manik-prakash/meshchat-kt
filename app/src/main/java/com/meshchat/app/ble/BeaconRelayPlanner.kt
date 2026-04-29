package com.meshchat.app.ble

import com.meshchat.app.data.db.entity.NeighborEntity

internal object BeaconRelayPlanner {
    fun selectRelayTargets(
        neighbors: List<NeighborEntity>,
        inboundAddress: String
    ): List<String> = neighbors
        .filter { it.relayCapable && !it.bleAddress.isNullOrBlank() && it.bleAddress != inboundAddress }
        .mapNotNull { it.bleAddress }
}
