package com.meshchat.app.routing

import android.util.Log
import com.meshchat.app.data.repository.ConversationRepository
import com.meshchat.app.data.repository.MeshRepository
import com.meshchat.app.domain.FailureReason
import com.meshchat.app.domain.MessageStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Periodic background worker responsible for:
 *  - Crash recovery: reset IN_FLIGHT packets to PENDING on startup.
 *  - Expiry enforcement: hard-fail PENDING packets older than [PACKET_EXPIRY_MS].
 *  - Stale-location failure: fail packets that have waited >2 min with no known destination
 *    location, so the sender receives a definitive failure instead of spinning forever.
 *  - Stale IN_FLIGHT reset: packets that were marked IN_FLIGHT but never got a DeliveryAck
 *    within [IN_FLIGHT_TIMEOUT_MS] are reverted to PENDING so the next beacon can re-try them.
 *  - Periodic retry: after each maintenance pass, poke the router to attempt forwarding any
 *    remaining PENDING entries, so packets queued before a good neighbor appeared get drained.
 */
class RelayQueueWorker(
    private val meshRepository: MeshRepository,
    private val conversationRepository: ConversationRepository,
    private val meshRouter: MeshRouter,
    private val scope: CoroutineScope
) {
    private val TAG = "RelayWorker"
    private var workerJob: Job? = null

    fun start() {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch {
            // Crash / cold-start recovery: any IN_FLIGHT packet from the previous session
            // will never receive an ack, so reset them to PENDING immediately.
            meshRepository.resetInFlightPackets()
            Log.d(TAG, "Startup: reset stale IN_FLIGHT packets")

            // Kick off an initial drain in case there are already-queued packets.
            meshRouter.onNeighborAvailable(WORKER_TRIGGER)

            workerLoop()
        }
    }

    fun stop() {
        workerJob?.cancel()
        workerJob = null
    }

    private suspend fun workerLoop() {
        while (true) {
            delay(WORKER_INTERVAL_MS)
            try { tick() } catch (e: Exception) {
                Log.w(TAG, "Worker tick error: ${e.message}")
            }
        }
    }

    /** Visible for testing. */
    internal suspend fun tick() {
        val now = System.currentTimeMillis()
        expirePackets(now)
        resetStaleInFlight(now)
        pruneSettled()
        // Re-trigger routing for any packets that survived expiry.
        meshRouter.onNeighborAvailable(WORKER_TRIGGER)
    }

    // ── Expiry ────────────────────────────────────────────────────────────────

    private suspend fun expirePackets(now: Long) {
        val pending = meshRepository.getAllPendingRelayPackets()
        if (pending.isEmpty()) return

        for (entry in pending) {
            val age = now - entry.createdAt
            when {
                age >= PACKET_EXPIRY_MS -> {
                    meshRepository.markRelayFailed(entry.packetId, FailureReason.EXPIRED)
                    conversationRepository.updateMessageStatus(entry.packetId, MessageStatus.FAILED_EXPIRED)
                    Log.d(TAG, "Hard-expired ${entry.packetId.take(16)} after ${age / 1000}s")
                }

                age >= STALE_LOCATION_TIMEOUT_MS -> {
                    // Fail early only when the destination has no GPS location AND is not a
                    // directly-connected BLE neighbor. If the destination is a direct neighbor,
                    // the router delivers without coordinates — don't kill the packet here.
                    val contact = meshRepository.getContact(entry.destinationPublicKey)
                    val isDirectNeighbor =
                        meshRepository.getNeighborByPublicKey(entry.destinationPublicKey) != null
                    if (contact?.lastResolvedLat == null && !isDirectNeighbor) {
                        meshRepository.markRelayFailed(entry.packetId, FailureReason.STALE_DESTINATION_LOCATION)
                        conversationRepository.updateMessageStatus(entry.packetId, MessageStatus.FAILED_UNREACHABLE)
                        Log.d(TAG, "Stale-location fail ${entry.packetId.take(16)} after ${age / 1000}s")
                    }
                }
            }
        }
    }

    // ── IN_FLIGHT recovery ────────────────────────────────────────────────────

    private suspend fun resetStaleInFlight(now: Long) {
        meshRepository.resetStaleInFlight(now - IN_FLIGHT_TIMEOUT_MS)
    }

    // ── Settled packet cleanup ────────────────────────────────────────────────

    private suspend fun pruneSettled() {
        meshRepository.pruneSettledRelayPackets()
    }

    companion object {
        /** 5 minutes — hard expiry for any undelivered packet. */
        const val PACKET_EXPIRY_MS = MeshRepository.PACKET_EXPIRY_MS
        /** 2 minutes — extra failure path when destination location is unknown. */
        const val STALE_LOCATION_TIMEOUT_MS = MeshRepository.STALE_LOCATION_TIMEOUT_MS
        /** 1 minute — IN_FLIGHT packets not acked within this window revert to PENDING. */
        const val IN_FLIGHT_TIMEOUT_MS = MeshRepository.IN_FLIGHT_TIMEOUT_MS
        /** How often the worker ticks. */
        const val WORKER_INTERVAL_MS = 15_000L
        /** Sentinel passed to onNeighborAvailable when the worker triggers a drain. */
        const val WORKER_TRIGGER = "_worker_tick_"
    }
}
