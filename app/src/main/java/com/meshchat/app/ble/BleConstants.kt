package com.meshchat.app.ble

object BleConstants {
    const val SERVICE_UUID          = "12345678-1234-5678-1234-56789abcdef0"
    const val HANDSHAKE_CHAR_UUID   = "12345678-1234-5678-1234-56789abcdef1"
    const val MESSAGE_CHAR_UUID     = "12345678-1234-5678-1234-56789abcdef2"
    const val ACK_CHAR_UUID         = "12345678-1234-5678-1234-56789abcdef3"
    const val CCCD_UUID             = "00002902-0000-1000-8000-00805f9b34fb"

    const val PEER_NAME_PREFIX      = "MC_"
    const val ADV_NAME_MAX_LEN      = 8
    const val SCAN_DURATION_MS      = 12_000L
    const val CHUNK_SIZE            = 18
}
