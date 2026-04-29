package com.meshchat.app.ble

internal data class BeaconFreshness(
    val seqNum: Int,
    val timestamp: Long
)

internal data class BeaconLocationSnapshot(
    val geohash: String?,
    val lat: Double?,
    val lon: Double?,
    val hasFreshLocation: Boolean
)

internal object BeaconObservationPolicy {
    fun shouldAccept(previous: BeaconFreshness?, seqNum: Int, timestamp: Long): Boolean {
        if (previous == null) return true
        if (seqNum > previous.seqNum) return true
        return seqNum == previous.seqNum && timestamp > previous.timestamp
    }

    fun mergeLocation(
        existingGeohash: String?,
        existingLat: Double?,
        existingLon: Double?,
        beaconGeohash: String,
        beaconLat: Double,
        beaconLon: Double
    ): BeaconLocationSnapshot {
        val hasFreshLocation = beaconGeohash.isNotBlank() && (beaconLat != 0.0 || beaconLon != 0.0)
        return if (hasFreshLocation) {
            BeaconLocationSnapshot(
                geohash = beaconGeohash,
                lat = beaconLat,
                lon = beaconLon,
                hasFreshLocation = true
            )
        } else {
            BeaconLocationSnapshot(
                geohash = existingGeohash,
                lat = existingLat,
                lon = existingLon,
                hasFreshLocation = false
            )
        }
    }
}
