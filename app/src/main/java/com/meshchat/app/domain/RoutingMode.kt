package com.meshchat.app.domain

enum class RoutingMode(val id: Byte) {
    DIRECT(0x00),
    BROADCAST(0x01),
    GREEDY(0x02);

    companion object {
        fun fromId(id: Byte): RoutingMode =
            entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Unknown RoutingMode id: 0x${id.toString(16)}")
    }
}
