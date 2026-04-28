package com.meshchat.app.ble

import android.util.Log
import com.meshchat.app.crypto.CryptoManager
import com.meshchat.app.data.repository.IdentityRepository
import com.meshchat.app.domain.BlePayload
import com.meshchat.app.location.LocationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class BeaconScheduler(
    private val identityRepo: IdentityRepository,
    private val bleMeshManager: BleMeshManager,
    private val crypto: CryptoManager,
    private val locationProvider: LocationProvider
) {
    private val TAG = "BeaconScheduler"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seqNum = AtomicInteger(0)
    private var beaconJob: Job? = null

    fun start() {
        if (beaconJob?.isActive == true) return
        beaconJob = scope.launch { beaconLoop() }
    }

    fun stop() {
        beaconJob?.cancel()
        beaconJob = null
    }

    private suspend fun beaconLoop() {
        while (true) {
            delay(BEACON_INTERVAL_MS)
            broadcastBeacon()
        }
    }

    private suspend fun broadcastBeacon() {
        try {
            val identity = identityRepo.getIdentity() ?: return
            val location = locationProvider.getLastLocation()
            val geohash  = if (location != null) locationProvider.geohash(location.latitude, location.longitude) else ""
            val lat      = location?.latitude ?: 0.0
            val lon      = location?.longitude ?: 0.0
            val seq      = seqNum.getAndIncrement()
            val now      = System.currentTimeMillis()

            val sig = crypto.signBeacon(
                nodeId = identity.publicKey,
                displayName = identity.displayName,
                timestamp = now,
                seqNum = seq,
                relayCapable = true,
                geohash = geohash,
                lat = lat,
                lon = lon
            )

            val beacon = BlePayload.Beacon(
                nodeId = identity.publicKey,
                displayName = identity.displayName,
                timestamp = now,
                seqNum = seq,
                relayCapable = true,
                geohash = geohash,
                lat = lat,
                lon = lon,
                signature = sig
            )

            bleMeshManager.broadcastBeacon(beacon)
            Log.d(TAG, "Beacon broadcast seq=$seq geo=${geohash.ifEmpty { "none" }}")
        } catch (e: Exception) {
            Log.w(TAG, "Beacon broadcast failed: ${e.message}")
        }
    }

    companion object {
        const val BEACON_INTERVAL_MS = 30_000L
    }
}
