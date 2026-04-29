package com.meshchat.app.data.repository

import com.meshchat.app.data.db.entity.KnownContactEntity
import com.meshchat.app.testutil.FakeKnownContactDao
import com.meshchat.app.testutil.FakeNeighborDao
import com.meshchat.app.testutil.FakeRelayQueueDao
import com.meshchat.app.testutil.FakeRouteEventDao
import com.meshchat.app.testutil.FakeSeenPacketDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MeshRepositoryContactTest {
    @Test
    fun `contact location update creates a record when missing`() = runBlocking {
        val knownContacts = FakeKnownContactDao()
        val repository = MeshRepository(
            seenPacketDao = FakeSeenPacketDao(),
            relayQueueDao = FakeRelayQueueDao(),
            neighborDao = FakeNeighborDao(),
            knownContactDao = knownContacts,
            routeEventDao = FakeRouteEventDao()
        )

        repository.updateContactLocation(
            publicKey = "dest-key",
            displayName = "Dest",
            geohash = "u4pruyd",
            lat = 1.23,
            lon = 4.56
        )

        val stored = knownContacts.contacts["dest-key"]
        assertNotNull(stored)
        assertEquals("Dest", stored?.displayName)
        assertEquals("u4pruyd", stored?.lastResolvedGeoHash)
    }

    @Test
    fun `ensure contact preserves location while refreshing display name`() = runBlocking {
        val knownContacts = FakeKnownContactDao().apply {
            contacts["dest-key"] = KnownContactEntity(
                publicKey = "dest-key",
                displayName = "Old",
                lastResolvedGeoHash = "u4pruyd",
                lastResolvedLat = 1.23,
                lastResolvedLon = 4.56,
                lastLocationAt = 99L
            )
        }
        val repository = MeshRepository(
            seenPacketDao = FakeSeenPacketDao(),
            relayQueueDao = FakeRelayQueueDao(),
            neighborDao = FakeNeighborDao(),
            knownContactDao = knownContacts,
            routeEventDao = FakeRouteEventDao()
        )

        repository.ensureContact("dest-key", "New")

        val stored = knownContacts.contacts["dest-key"]
        assertEquals("New", stored?.displayName)
        assertEquals("u4pruyd", stored?.lastResolvedGeoHash)
        assertEquals(1.23, stored?.lastResolvedLat ?: 0.0, 0.0)
    }
}
