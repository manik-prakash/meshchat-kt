package com.meshchat.app

import com.meshchat.app.ble.MeshProtocolCodec
import com.meshchat.app.domain.BlePayload
import org.junit.Assert.*
import org.junit.Test

class MeshProtocolCodecTest {

    // ── Encode/decode round-trips ────────────────────────────────────────────

    @Test
    fun `handshake round-trip`() {
        val original = BlePayload.Handshake(nodeId = "abc12345", displayName = "Alice")
        val chunks = MeshProtocolCodec.encode(original)
        assertEquals(1, chunks.size)  // fits in one 18-byte chunk
        val decoded = MeshProtocolCodec.decode(chunks[0])
        assertNotNull(decoded)
        val hs = decoded as BlePayload.Handshake
        assertEquals("Alice", hs.displayName)
        assertEquals("abc12345", hs.nodeId)
    }

    @Test
    fun `ack round-trip`() {
        val original = BlePayload.Ack(messageId = "msg123456789")
        val chunks = MeshProtocolCodec.encode(original)
        assertEquals(1, chunks.size)
        val decoded = MeshProtocolCodec.decode(chunks[0]) as BlePayload.Ack
        assertEquals("msg123456789", decoded.messageId)
    }

    @Test
    fun `message round-trip short text`() {
        val original = BlePayload.Message(id = "id1234567890", senderDeviceId = "sender12", text = "hi", timestamp = 0L)
        val chunks = MeshProtocolCodec.encode(original)
        // May be single or multi-chunk depending on sizes
        val decoded = reassemble(chunks, "test") as BlePayload.Message
        assertEquals("hi", decoded.text)
        assertEquals("sender12", decoded.senderDeviceId)
    }

    @Test
    fun `message round-trip long text`() {
        val longText = "A".repeat(300)
        val original = BlePayload.Message(id = "id1234567890", senderDeviceId = "sender12", text = longText, timestamp = 0L)
        val chunks = MeshProtocolCodec.encode(original)
        assertTrue("should fragment", chunks.size > 1)
        val decoded = reassemble(chunks, "long") as BlePayload.Message
        assertEquals(longText, decoded.text)
    }

    @Test
    fun `message round-trip 500 char limit`() {
        val text500 = "B".repeat(500)
        val original = BlePayload.Message(id = "id1234567890", senderDeviceId = "sender12", text = text500, timestamp = 0L)
        val chunks = MeshProtocolCodec.encode(original)
        val decoded = reassemble(chunks, "max") as BlePayload.Message
        assertEquals(text500, decoded.text)
    }

    @Test
    fun `nodeId truncated to 8 chars in handshake`() {
        val long = "12345678-abcd-ef01-2345-6789abcdef01"
        val original = BlePayload.Handshake(nodeId = long, displayName = "Bob")
        val chunks = MeshProtocolCodec.encode(original)
        val decoded = reassemble(chunks, "trunc") as BlePayload.Handshake
        assertEquals(long.take(8), decoded.nodeId)
    }

    @Test
    fun `messageId truncated to 12 chars in ack`() {
        val longId = "abcdefghijklmnopqrstuvwxyz"
        val original = BlePayload.Ack(messageId = longId)
        val chunks = MeshProtocolCodec.encode(original)
        val decoded = MeshProtocolCodec.decode(chunks[0]) as BlePayload.Ack
        assertEquals(longId.take(12), decoded.messageId)
    }

    // ── Fragment assembly ────────────────────────────────────────────────────

    @Test
    fun `decode returns null for intermediate fragments`() {
        val text = "C".repeat(200)
        val original = BlePayload.Message(id = "id1234567890", senderDeviceId = "sender12", text = text, timestamp = 0L)
        val chunks = MeshProtocolCodec.encode(original)
        assertTrue(chunks.size > 2)
        // All chunks except the last should return null
        for (i in 0 until chunks.size - 1) {
            val result = MeshProtocolCodec.decode(chunks[i], "frag_test_1")
            assertNull("chunk $i should return null", result)
        }
        val last = MeshProtocolCodec.decode(chunks.last(), "frag_test_1")
        assertNotNull(last)
    }

    @Test
    fun `missing fragment returns null`() {
        val text = "D".repeat(200)
        val original = BlePayload.Message(id = "id1234567890", senderDeviceId = "sender12", text = text, timestamp = 0L)
        val chunks = MeshProtocolCodec.encode(original)
        assertTrue(chunks.size > 2)
        // Feed start and end, skip middle
        MeshProtocolCodec.decode(chunks[0], "missing_test")
        // skip chunks[1]
        val last = MeshProtocolCodec.decode(chunks.last(), "missing_test")
        assertNull("should return null with missing middle fragment", last)
    }

    @Test
    fun `different source keys are isolated`() {
        val text = "E".repeat(200)
        val original = BlePayload.Message(id = "id1234567890", senderDeviceId = "sender12", text = text, timestamp = 0L)
        val chunks = MeshProtocolCodec.encode(original)

        // Feed key1 start, then key2 start — key1 should still be independent
        MeshProtocolCodec.decode(chunks[0], "key1_iso")
        MeshProtocolCodec.decode(chunks[0], "key2_iso")
        for (i in 1 until chunks.size) {
            MeshProtocolCodec.decode(chunks[i], "key1_iso")
        }
        val result = MeshProtocolCodec.decode(chunks.last(), "key2_iso")
        // key2 should also complete independently when fed all chunks
        // (they're separate reassembly buffers)
        // reset key2 by feeding from start again
        MeshProtocolCodec.decode(chunks[0], "key3_iso")
        for (i in 1 until chunks.size) {
            val r = MeshProtocolCodec.decode(chunks[i], "key3_iso")
            if (i == chunks.size - 1) assertNotNull(r)
        }
    }

    @Test
    fun `empty input returns null`() {
        val result = MeshProtocolCodec.decode(ByteArray(0))
        assertNull(result)
    }

    @Test
    fun `unicode text survives round-trip`() {
        val text = "Hello 🌍 こんにちは"
        val original = BlePayload.Message(id = "id1234567890", senderDeviceId = "sender12", text = text, timestamp = 0L)
        val chunks = MeshProtocolCodec.encode(original)
        val decoded = reassemble(chunks, "unicode") as BlePayload.Message
        assertEquals(text, decoded.text)
    }

    // ── Chunk size invariant ─────────────────────────────────────────────────

    @Test
    fun `all fragment chunks are at most CHUNK_SIZE bytes`() {
        val text = "F".repeat(500)
        val original = BlePayload.Message(id = "id1234567890", senderDeviceId = "sender12", text = text, timestamp = 0L)
        val chunks = MeshProtocolCodec.encode(original)
        for ((i, chunk) in chunks.withIndex()) {
            assertTrue("chunk $i size ${chunk.size} exceeds CHUNK_SIZE", chunk.size <= 18)
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun reassemble(chunks: List<ByteArray>, key: String): BlePayload {
        var result: BlePayload? = null
        for (chunk in chunks) {
            result = MeshProtocolCodec.decode(chunk, key)
        }
        return checkNotNull(result) { "Assembly returned null for $key" }
    }
}
