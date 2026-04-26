package com.meshchat.app.ble

import android.util.Log
import com.meshchat.app.ble.BleConstants.CHUNK_SIZE
import com.meshchat.app.domain.BlePayload
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Binary protocol matching the React Native reference implementation exactly.
 *
 * Packet format:
 *   [type: 1 byte] [payload bytes...]
 *
 * Types:
 *   0x01 handshake:  [nameLen:1] [name:utf8] [idLen:1] [id:utf8]
 *   0x02 message:    [idLen:1] [id:utf8] [senderLen:1] [sender:utf8] [text:utf8 rest]
 *   0x03 ack:        [idLen:1] [id:utf8]
 *
 * Fragmentation for payloads > CHUNK_SIZE:
 *   0xF0 fragStart:    [totalLen:2 BE] [seqTotal:1] [data...]
 *   0xF1 fragContinue: [seqNum:1]      [data...]
 *   0xF2 fragEnd:      [seqNum:1]      [data...]
 */
object MeshProtocolCodec {
    private const val TAG = "MeshCodec"

    private const val TYPE_HANDSHAKE: Byte   = 0x01
    private const val TYPE_MESSAGE: Byte     = 0x02
    private const val TYPE_ACK: Byte         = 0x03
    private const val TYPE_FRAG_START: Byte  = 0xF0.toByte()
    private const val TYPE_FRAG_CONT: Byte   = 0xF1.toByte()
    private const val TYPE_FRAG_END: Byte    = 0xF2.toByte()

    // Header bytes consumed in each fragment kind
    private const val FIRST_DATA_SIZE = CHUNK_SIZE - 4  // type + totalLen(2) + seqTotal
    private const val CONT_DATA_SIZE  = CHUNK_SIZE - 2  // type + seqNum

    // Per-source reassembly state
    private data class ReassemblyBuffer(
        val totalLen: Int,
        val seqTotal: Int,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf()
    )
    private val reassemblyBuffers = ConcurrentHashMap<String, ReassemblyBuffer>()

    // ── Encoding ────────────────────────────────────────────────────────────

    fun encode(payload: BlePayload): List<ByteArray> {
        val bytes = serialize(payload)
        return if (bytes.size <= CHUNK_SIZE) listOf(bytes) else fragment(bytes)
    }

    private fun serialize(payload: BlePayload): ByteArray {
        val out = ByteArrayOutputStream()
        when (payload) {
            is BlePayload.Handshake -> {
                val nameBytes   = payload.displayName.toByteArray(Charsets.UTF_8)
                val idBytes     = payload.deviceId.take(8).toByteArray(Charsets.UTF_8)
                out.write(TYPE_HANDSHAKE.toInt())
                out.write(nameBytes.size)
                out.write(nameBytes)
                out.write(idBytes.size)
                out.write(idBytes)
            }
            is BlePayload.Message -> {
                val idBytes     = payload.id.take(12).toByteArray(Charsets.UTF_8)
                val senderBytes = payload.senderDeviceId.take(8).toByteArray(Charsets.UTF_8)
                val textBytes   = payload.text.toByteArray(Charsets.UTF_8)
                out.write(TYPE_MESSAGE.toInt())
                out.write(idBytes.size)
                out.write(idBytes)
                out.write(senderBytes.size)
                out.write(senderBytes)
                out.write(textBytes)
            }
            is BlePayload.Ack -> {
                val idBytes = payload.messageId.take(12).toByteArray(Charsets.UTF_8)
                out.write(TYPE_ACK.toInt())
                out.write(idBytes.size)
                out.write(idBytes)
            }
        }
        return out.toByteArray()
    }

    private fun fragment(bytes: ByteArray): List<ByteArray> {
        // Slice raw bytes into data windows
        val windows = mutableListOf<ByteArray>()
        windows.add(bytes.copyOfRange(0, minOf(FIRST_DATA_SIZE, bytes.size)))
        var pos = windows[0].size
        while (pos < bytes.size) {
            val end = minOf(pos + CONT_DATA_SIZE, bytes.size)
            windows.add(bytes.copyOfRange(pos, end))
            pos = end
        }

        val seqTotal = windows.size
        val totalLen = bytes.size
        return windows.mapIndexed { i, data ->
            val out = ByteArrayOutputStream()
            when {
                i == 0 -> {
                    out.write(TYPE_FRAG_START.toInt())
                    out.write((totalLen shr 8) and 0xFF)
                    out.write(totalLen and 0xFF)
                    out.write(seqTotal)
                }
                i == seqTotal - 1 -> {
                    out.write(TYPE_FRAG_END.toInt())
                    out.write(i)
                }
                else -> {
                    out.write(TYPE_FRAG_CONT.toInt())
                    out.write(i)
                }
            }
            out.write(data)
            out.toByteArray()
        }
    }

    // ── Decoding ─────────────────────────────────────────────────────────────

    /**
     * Feed one received chunk. Returns a complete BlePayload when all fragments
     * are assembled, null while still accumulating.
     *
     * @param data raw bytes (not base64)
     * @param sourceKey per-device-per-characteristic key for reassembly isolation
     */
    fun decode(data: ByteArray, sourceKey: String = "default"): BlePayload? {
        if (data.isEmpty()) return null

        return when (val type = data[0]) {
            TYPE_FRAG_START -> {
                val totalLen = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
                val seqTotal = data[3].toInt() and 0xFF
                val chunk    = data.copyOfRange(4, data.size)
                reassemblyBuffers[sourceKey] = ReassemblyBuffer(totalLen, seqTotal).also {
                    it.chunks[0] = chunk
                }
                null
            }
            TYPE_FRAG_CONT -> {
                val seqNum = data[1].toInt() and 0xFF
                reassemblyBuffers[sourceKey]?.chunks?.set(seqNum, data.copyOfRange(2, data.size))
                null
            }
            TYPE_FRAG_END -> {
                val seqNum = data[1].toInt() and 0xFF
                val buf = reassemblyBuffers.remove(sourceKey) ?: run {
                    Log.w(TAG, "Fragment end without start for $sourceKey")
                    return null
                }
                buf.chunks[seqNum] = data.copyOfRange(2, data.size)

                val assembled = ByteArrayOutputStream()
                for (i in 0 until buf.seqTotal) {
                    val chunk = buf.chunks[i] ?: run {
                        Log.w(TAG, "Missing fragment $i for $sourceKey")
                        return null
                    }
                    assembled.write(chunk)
                }
                deserialize(assembled.toByteArray().copyOfRange(0, buf.totalLen))
            }
            else -> deserialize(data)
        }
    }

    private fun deserialize(bytes: ByteArray): BlePayload {
        var offset = 1
        return when (val type = bytes[0]) {
            TYPE_HANDSHAKE -> {
                val nameLen     = bytes[offset++].toInt() and 0xFF
                val displayName = String(bytes, offset, nameLen, Charsets.UTF_8)
                offset         += nameLen
                val idLen       = bytes[offset++].toInt() and 0xFF
                val deviceId    = String(bytes, offset, idLen, Charsets.UTF_8)
                BlePayload.Handshake(deviceId = deviceId, displayName = displayName)
            }
            TYPE_MESSAGE -> {
                val idLen           = bytes[offset++].toInt() and 0xFF
                val id              = String(bytes, offset, idLen, Charsets.UTF_8)
                offset             += idLen
                val senderLen       = bytes[offset++].toInt() and 0xFF
                val senderDeviceId  = String(bytes, offset, senderLen, Charsets.UTF_8)
                offset             += senderLen
                val text            = String(bytes, offset, bytes.size - offset, Charsets.UTF_8)
                BlePayload.Message(id = id, senderDeviceId = senderDeviceId, text = text, timestamp = System.currentTimeMillis())
            }
            TYPE_ACK -> {
                val idLen     = bytes[offset++].toInt() and 0xFF
                val messageId = String(bytes, offset, idLen, Charsets.UTF_8)
                BlePayload.Ack(messageId = messageId)
            }
            else -> throw IllegalArgumentException("Unknown packet type: 0x${type.toString(16)}")
        }
    }
}
