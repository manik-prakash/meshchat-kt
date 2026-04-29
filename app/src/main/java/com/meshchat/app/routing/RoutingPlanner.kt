package com.meshchat.app.routing

import com.meshchat.app.data.db.entity.NeighborEntity
import com.meshchat.app.domain.BlePayload
import com.meshchat.app.domain.RoutingMode
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal object RoutingPlanner {
    sealed class Plan {
        data class DirectHop(val hop: NeighborEntity) : Plan()
        data class GreedyHop(
            val hop: NeighborEntity,
            val recoveredFromPerimeter: Boolean = false
        ) : Plan()
        data class PerimeterHop(val hop: NeighborEntity) : Plan()
        data class BroadcastHops(val hops: List<NeighborEntity>) : Plan()
        object VoidLimitExceeded : Plan()
        object NoReachableNeighbors : Plan()
    }

    fun plan(
        packet: BlePayload.RoutedMessage,
        neighbors: List<NeighborEntity>,
        destinationLat: Double?,
        destinationLon: Double?,
        selfLat: Double?,
        selfLon: Double?,
        incomingBleAddress: String?,
        improvementThreshold: Double,
        maxVoidHops: Int
    ): Plan {
        val reachableNeighbors = neighbors.filter { !it.bleAddress.isNullOrBlank() }

        val directNeighbor = reachableNeighbors.firstOrNull {
            it.neighborPublicKey == packet.destinationPublicKey
        }
        if (directNeighbor != null) return Plan.DirectHop(directNeighbor)

        val floodCandidates = reachableNeighbors
            .filter { it.relayCapable }
            .filterNot { it.bleAddress == incomingBleAddress }

        if (destinationLat == null || destinationLon == null) {
            return if (floodCandidates.isNotEmpty()) {
                Plan.BroadcastHops(floodCandidates)
            } else {
                Plan.NoReachableNeighbors
            }
        }

        val geoCandidates = floodCandidates.filter { it.lat != null && it.lon != null }
        if (geoCandidates.isEmpty()) {
            return if (floodCandidates.isNotEmpty()) {
                Plan.BroadcastHops(floodCandidates)
            } else {
                Plan.NoReachableNeighbors
            }
        }

        val selfDist = if (selfLat != null && selfLon != null) {
            haversineKm(selfLat, selfLon, destinationLat, destinationLon)
        } else {
            Double.MAX_VALUE
        }

        val best = geoCandidates.minByOrNull {
            haversineKm(it.lat!!, it.lon!!, destinationLat, destinationLon)
        }!!
        val bestDist = haversineKm(best.lat!!, best.lon!!, destinationLat, destinationLon)

        if (bestDist < selfDist * improvementThreshold) {
            return Plan.GreedyHop(
                hop = best,
                recoveredFromPerimeter = packet.routingMode == RoutingMode.PERIMETER
            )
        }

        if (packet.routingMode == RoutingMode.PERIMETER && packet.voidHopCount >= maxVoidHops) {
            return Plan.VoidLimitExceeded
        }

        return Plan.PerimeterHop(best)
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return EARTH_RADIUS_KM * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private const val EARTH_RADIUS_KM = 6371.0
}
