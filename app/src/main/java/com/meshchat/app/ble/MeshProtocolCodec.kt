package com.meshchat.app.ble

import android.util.Log
import com.meshchat.app.ble.BleConstants.CHUNK_SIZE
import com.meshchat.app.domain.BlePayload
import com.meshchat.app.domain.FailureReason
import com.meshchat.app.domain.RoutingMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Binary protocol with fragmentation support.
 *
 * Packet format:
 *   [type: 1 byte] [payload bytes...]
 *
 * Legacy types (BLE direct):
 *   0x01 handshake:      [nameLen:1] [name:utf8] [idLen:1] [id:utf8]
 *   0x02 message:        [idLen:1] [id:utf8] [senderLen:1] [sender:utf8] [text:utf8 rest]
 *   0x03 ack:            [idLen:1] [id:utf8]
 *
 * Mesh routing types:
 *   0x04 beacon:         [nodeId:1+n] [displayName:1+n] [timestamp:8BE] [seqNum:4BE]
 *   0x05 routedMessage:  [packetId:1+n] [srcKey:1+n] [dstKey:1+n] [dstName:1+n]
 *                        [geoHint:1+n] [ttl:1] [hopCount:1] [routingMode:1]
 *                        [timestamp:8BE] [sig:1+n] [body:2+n]
 *   0x06 routeAck:       [packetId:1+n] [hopNodeId:1+n] [timestamp:8BE]
 *   0x07 deliveryAck:    [packetId:1+n] [dstNodeId:1+n] [timestamp:8BE]
 *   0x08 routeFailure:   [packetId:1+n] [failNodeId:1+n] [reason:1] [timestamp:8BE]
 *
 * Fragmentation for payloads > CHUNK_SIZE:
 *   0xF0 fragStart:    [totalLen:2 BE] [seqTotal:1] [data...]
 *   0xF1 fragContinue: [seqNum:1]      [data...]
 *   0xF2 fragEnd:      [seqNum:1]      [data...]
 *
 * Length prefixes: 1-byte (str1) for all string fields except RoutedMessage.body which
 * uses a 2-byte (str2) prefix to support messages up to 65535 UTF-8 bytes.
 */
object MeshProtocolCodec {
    private const val TAG = "MeshCodec"

    private const val TYPE_HANDSHAKE: Byte     = 0x01
    private const val TYPE_MESSAGE: Byte       = 0x02
    private const val TYPE_ACK: Byte           = 0x03
    private const val TYPE_BEACON: Byte        = 0x04
    private const val TYPE_ROUTED_MSG: Byte    = 0x05
    private const val TYPE_ROUTE_ACK: Byte     = 0x06
    private const val TYPE_DELIVERY_ACK: Byte  = 0x07
    private const val TYPE_ROUTE_FAILURE: Byte = 0x08
    private const val TYPE_FRAG_START: Byte    = 0xF0.toByte()
    private const val TYPE_FRAG_CONT: Byte     = 0xF1.toByte()
    private const val TYPE_FRAG_END: Byte      = 0xF2.toByte()

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
                val nameBytes = payload.displayName.toByteArray(Charsets.UTF_8)
                val idBytes   = payload.deviceId.take(8).toByteArray(Charsets.UTF_8)
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
            is BlePayload.Beacon -> {
                out.write(TYPE_BEACON.toInt())
                out.writeStr1(payload.nodeId)
                out.writeStr1(payload.displayName)
                out.writeLongBE(payload.timestamp)
                out.writeIntBE(payload.seqNum)
            }
            is BlePayload.RoutedMessage -> {
                out.write(TYPE_ROUTED_MSG.toInt())
                out.writeStr1(payload.packetId)
                out.writeStr1(payload.sourcePublicKey)
                out.writeStr1(payload.destinationPublicKey)
                out.writeStr1(payload.destinationDisplayNameSnapshot)
                out.writeStr1(payload.destinationGeoHint)
                out.write(payload.ttl and 0xFF)
                out.write(payload.hopCount and 0xFF)
                out.write(payload.routingMode.id.toInt() and 0xFF)
                out.writeLongBE(payload.timestamp)
                out.writeStr1(payload.signature)
                out.writeStr2(payload.body)
            }
            is BlePayload.RouteAck -> {
                out.write(TYPE_ROUTE_ACK.toInt())
                out.writeStr1(payload.packetId)
                out.writeStr1(payload.hopNodeId)
                out.writeLongBE(payload.timestamp)
            }
            is BlePayload.DeliveryAck -> {
                out.write(TYPE_DELIVERY_ACK.toInt())
                out.writeStr1(payload.packetId)
                out.writeStr1(payload.destinationNodeId)
                out.writeLongBE(payload.timestamp)
            }
            is BlePayload.RouteFailure -> {
                out.write(TYPE_ROUTE_FAILURE.toInt())
                out.writeStr1(payload.packetId)
                out.writeStr1(payload.failingNodeId)
                out.write(payload.reason.id.toInt() and 0xFF)
                out.writeLongBE(payload.timestamp)
            }
        }
        return out.toByteArray()
    }

    private fun fragment(bytes: ByteArray): List<ByteArray> {
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
        var off = 1
        return when (val type = bytes[0]) {
            TYPE_HANDSHAKE -> {
                val nameLen     = bytes[off++].toInt() and 0xFF
                val displayName = String(bytes, off, nameLen, Charsets.UTF_8)
                off            += nameLen
                val idLen       = bytes[off++].toInt() and 0xFF
                val deviceId    = String(bytes, off, idLen, Charsets.UTF_8)
                BlePayload.Handshake(deviceId = deviceId, displayName = displayName)
            }
            TYPE_MESSAGE -> {
                val idLen          = bytes[off++].toInt() and 0xFF
                val id             = String(bytes, off, idLen, Charsets.UTF_8)
                off               += idLen
                val senderLen      = bytes[off++].toInt() and 0xFF
                val senderDeviceId = String(bytes, off, senderLen, Charsets.UTF_8)
                off               += senderLen
                val text           = String(bytes, off, bytes.size - off, Charsets.UTF_8)
                BlePayload.Message(id = id, senderDeviceId = senderDeviceId, text = text, timestamp = System.currentTimeMillis())
            }
            TYPE_ACK -> {
                val idLen     = bytes[off++].toInt() and 0xFF
                val messageId = String(bytes, off, idLen, Charsets.UTF_8)
                BlePayload.Ack(messageId = messageId)
            }
            TYPE_BEACON -> {
                val (nodeId, o1)      = bytes.readStr1(off)
                val (displayName, o2) = bytes.readStr1(o1)
                val timestamp         = bytes.readLongBE(o2)
                val seqNum            = bytes.readIntBE(o2 + 8)
                BlePayload.Beacon(nodeId = nodeId, displayName = displayName, timestamp = timestamp, seqNum = seqNum)
            }
            TYPE_ROUTED_MSG -> {
                val (packetId, o1)  = bytes.readStr1(off)
                val (srcKey, o2)    = bytes.readStr1(o1)
                val (dstKey, o3)    = bytes.readStr1(o2)
                val (dstName, o4)   = bytes.readStr1(o3)
                val (geoHint, o5)   = bytes.readStr1(o4)
                val ttl             = bytes[o5].toInt() and 0xFF
                val hopCount        = bytes[o5 + 1].toInt() and 0xFF
                val routingMode     = RoutingMode.fromId(bytes[o5 + 2])
                val timestamp       = bytes.readLongBE(o5 + 3)
                val (sig, o6)       = bytes.readStr1(o5 + 11)
                val (body, _)       = bytes.readStr2(o6)
                BlePayload.RoutedMessage(
                    packetId = packetId,
                    sourcePublicKey = srcKey,
                    destinationPublicKey = dstKey,
                    destinationDisplayNameSnapshot = dstName,
                    destinationGeoHint = geoHint,
                    ttl = ttl,
                    hopCount = hopCount,
                    routingMode = routingMode,
                    timestamp = timestamp,
                    signature = sig,
                    body = body
                )
            }
            TYPE_ROUTE_ACK -> {
                val (packetId, o1)  = bytes.readStr1(off)
                val (hopNodeId, o2) = bytes.readStr1(o1)
                val timestamp       = bytes.readLongBE(o2)
                BlePayload.RouteAck(packetId = packetId, hopNodeId = hopNodeId, timestamp = timestamp)
            }
            TYPE_DELIVERY_ACK -> {
                val (packetId, o1)  = bytes.readStr1(off)
                val (dstNodeId, o2) = bytes.readStr1(o1)
                val timestamp       = bytes.readLongBE(o2)
                BlePayload.DeliveryAck(packetId = packetId, destinationNodeId = dstNodeId, timestamp = timestamp)
            }
            TYPE_ROUTE_FAILURE -> {
                val (packetId, o1)   = bytes.readStr1(off)
                val (failNodeId, o2) = bytes.readStr1(o1)
                val reason           = FailureReason.fromId(bytes[o2])
                val timestamp        = bytes.readLongBE(o2 + 1)
                BlePayload.RouteFailure(packetId = packetId, failingNodeId = failNodeId, reason = reason, timestamp = timestamp)
            }
            else -> throw IllegalArgumentException("Unknown packet type: 0x${type.toString(16)}")
        }
    }

    // ── Write helpers ─────────────────────────────────────────────────────────

    private fun ByteArrayOutputStream.writeStr1(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        write(b.size)
        write(b)
    }

    private fun ByteArrayOutputStream.writeStr2(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        write((b.size shr 8) and 0xFF)
        write(b.size and 0xFF)
        write(b)
    }

    private fun ByteArrayOutputStream.writeLongBE(v: Long) {
        for (shift in 56 downTo 0 step 8) write(((v shr shift) and 0xFF).toInt())
    }

    private fun ByteArrayOutputStream.writeIntBE(v: Int) {
        write((v shr 24) and 0xFF)
        write((v shr 16) and 0xFF)
        write((v shr 8) and 0xFF)
        write(v and 0xFF)
    }

    // ── Read helpers ──────────────────────────────────────────────────────────

    private fun ByteArray.readStr1(off: Int): Pair<String, Int> {
        val len = this[off].toInt() and 0xFF
        return String(this, off + 1, len, Charsets.UTF_8) to (off + 1 + len)
    }

    private fun ByteArray.readStr2(off: Int): Pair<String, Int> {
        val len = ((this[off].toInt() and 0xFF) shl 8) or (this[off + 1].toInt() and 0xFF)
        return String(this, off + 2, len, Charsets.UTF_8) to (off + 2 + len)
    }

    private fun ByteArray.readLongBE(off: Int): Long {
        var v = 0L
        for (i in 0..7) v = (v shl 8) or (this[off + i].toLong() and 0xFF)
        return v
    }

    private fun ByteArray.readIntBE(off: Int): Int =
        ((this[off].toInt() and 0xFF) shl 24) or
        ((this[off + 1].toInt() and 0xFF) shl 16) or
        ((this[off + 2].toInt() and 0xFF) shl 8) or
        (this[off + 3].toInt() and 0xFF)
}
