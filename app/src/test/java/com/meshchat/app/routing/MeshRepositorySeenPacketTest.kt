package com.meshchat.app.routing

import com.meshchat.app.data.repository.MeshRepository
import com.meshchat.app.testutil.FakeKnownContactDao
import com.meshchat.app.testutil.FakeNeighborDao
import com.meshchat.app.testutil.FakeRelayQueueDao
import com.meshchat.app.testutil.FakeRouteEventDao
import com.meshchat.app.testutil.FakeSeenPacketDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshRepositorySeenPacketTest {
    @Test
    fun `duplicate packet ids are recognized after first observation`() = runBlocking {
        val repository = MeshRepository(
            seenPacketDao = FakeSeenPacketDao(),
            relayQueueDao = FakeRelayQueueDao(),
            neighborDao = FakeNeighborDao(),
            knownContactDao = FakeKnownContactDao(),
            routeEventDao = FakeRouteEventDao()
        )

        assertFalse(repository.isSeen("packet-1"))
        repository.markSeen("packet-1")
        assertTrue(repository.isSeen("packet-1"))
    }
}
