package com.meshchat.app.routing

/**
 * Thread-safe LRU cache for seen packet IDs.
 *
 * Prevents duplicate delivery and forwarding: a node that has already processed
 * a packet with a given ID will silently drop any retransmission of it.
 *
 * Entries expire after [ttlMs] milliseconds. The cache is also capped at
 * [maxSize] entries; the eldest entry is evicted when the cap is reached.
 */
class PacketIdCache(
    private val maxSize: Int = 1000,
    private val ttlMs: Long = 5 * 60_000L
) {
    private data class Entry(val seenAt: Long)

    // accessOrder = true makes get() promote to MRU, enabling LRU eviction
    private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Entry>): Boolean = size > maxSize
    }

    @Synchronized
    fun isSeen(packetId: String): Boolean {
        evictExpired()
        return map.containsKey(packetId)
    }

    @Synchronized
    fun markSeen(packetId: String) {
        evictExpired()
        map[packetId] = Entry(System.currentTimeMillis())
    }

    private fun evictExpired() {
        val cutoff = System.currentTimeMillis() - ttlMs
        map.entries.removeIf { it.value.seenAt < cutoff }
    }
}
