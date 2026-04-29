package com.meshchat.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconObservationPolicyTest {
    @Test
    fun `older beacon state is rejected`() {
        val previous = BeaconFreshness(seqNum = 10, timestamp = 2_000L)

        assertFalse(BeaconObservationPolicy.shouldAccept(previous, seqNum = 9, timestamp = 3_000L))
        assertFalse(BeaconObservationPolicy.shouldAccept(previous, seqNum = 10, timestamp = 2_000L))
        assertTrue(BeaconObservationPolicy.shouldAccept(previous, seqNum = 10, timestamp = 2_001L))
        assertTrue(BeaconObservationPolicy.shouldAccept(previous, seqNum = 11, timestamp = 1_000L))
    }

    @Test
    fun `missing beacon location preserves existing contact coordinates`() {
        val merged = BeaconObservationPolicy.mergeLocation(
            existingGeohash = "u4pruyd",
            existingLat = 12.34,
            existingLon = 56.78,
            beaconGeohash = "",
            beaconLat = 0.0,
            beaconLon = 0.0
        )

        assertFalse(merged.hasFreshLocation)
        assertEquals("u4pruyd", merged.geohash)
        assertEquals(12.34, merged.lat ?: 0.0, 0.0)
        assertEquals(56.78, merged.lon ?: 0.0, 0.0)
    }
}
