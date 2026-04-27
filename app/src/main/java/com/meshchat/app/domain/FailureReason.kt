package com.meshchat.app.domain

enum class FailureReason(val id: Byte) {
    TTL_EXCEEDED(0x00),
    NO_ROUTE(0x01),
    DESTINATION_UNREACHABLE(0x02);

    companion object {
        fun fromId(id: Byte): FailureReason =
            entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Unknown FailureReason id: 0x${id.toString(16)}")
    }
}
