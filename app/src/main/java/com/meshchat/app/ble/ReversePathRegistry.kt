package com.meshchat.app.ble

import java.util.concurrent.ConcurrentHashMap

internal class ReversePathRegistry {
    private val upstreamByPacket = ConcurrentHashMap<String, String>()

    fun remember(packetId: String, fromAddress: String) {
        upstreamByPacket.putIfAbsent(packetId, fromAddress)
    }

    fun takeUpstream(packetId: String): String? =
        upstreamByPacket.remove(packetId)

    fun clear() {
        upstreamByPacket.clear()
    }
}
