package com.meshchat.app.runtime

import android.content.Context
import android.util.Log
import com.meshchat.app.ble.BeaconScheduler
import com.meshchat.app.ble.BleMeshManager
import com.meshchat.app.ble.BleState
import com.meshchat.app.ble.BleSyncCoordinator
import com.meshchat.app.data.db.entity.RouteEventEntity
import com.meshchat.app.data.repository.ConversationRepository
import com.meshchat.app.data.repository.MeshRepository
import com.meshchat.app.domain.Conversation
import com.meshchat.app.domain.Peer
import com.meshchat.app.routing.RelayQueueWorker
import com.meshchat.app.service.MeshRelayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MeshRuntimeStatus(
    val meshActive: Boolean = false,
    val bleState: BleState = BleState.IDLE,
    val neighborCount: Int = 0,
    val queuedPacketCount: Int = 0,
    val lastRoutingStatus: String = "idle",
    val persistentMeshEnabled: Boolean = false,
    val relayActive: Boolean = false,
    val relaySummary: String? = null,
    val relayPacketId: String? = null
)

class MeshRuntimeRepository(
    context: Context,
    private val meshPreferencesRepository: MeshPreferencesRepository,
    private val conversationRepository: ConversationRepository,
    private val meshRepository: MeshRepository,
    private val bleMeshManager: BleMeshManager,
    private val bleSyncCoordinator: BleSyncCoordinator,
    private val beaconScheduler: BeaconScheduler,
    private val relayQueueWorker: RelayQueueWorker,
    private val relayActivityTracker: RelayActivityTracker
) {
    private val tagInstance = Integer.toHexString(System.identityHashCode(this))
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtimeMutex = Mutex()

    private val _runtimeActive = MutableStateFlow(false)
    private val _discoveredPeers = MutableStateFlow<List<Peer>>(emptyList())
    val discoveredPeers: StateFlow<List<Peer>> = _discoveredPeers.asStateFlow()

    private val runtimeCore = combine(
        _runtimeActive,
        bleMeshManager.state,
        meshPreferencesRepository.persistentMeshEnabled
    )   { active, bleState, persistentEnabled ->
        RuntimeCore(
            active = active,
            bleState = bleState,
            persistentEnabled = persistentEnabled
        )
    }

    private val runtimeMetrics = combine(
        meshRepository.getNeighbors().map { it.size },
        meshRepository.getPendingRelayPackets().map { it.size },
        meshRepository.getLatestRouteEvent()
    ) { neighborCount, queuedPacketCount, lastRouteEvent ->
        RuntimeMetrics(
            neighborCount = neighborCount,
            queuedPacketCount = queuedPacketCount,
            lastRouteEvent = lastRouteEvent
        )
    }

    private val relayClock: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(RELAY_STATUS_TICK_MS)
        }
    }

    val runtimeStatus: StateFlow<MeshRuntimeStatus> = combine(
        runtimeCore,
        runtimeMetrics,
        relayActivityTracker.latestRelay,
        relayClock
    ) { core, metrics, latestRelay, now ->
        val relayActive = latestRelay != null && now - latestRelay.timestamp <= RELAY_ACTIVE_WINDOW_MS
        val relaySummary = if (relayActive && latestRelay != null) {
            "${latestRelay.sourceDisplayName} -> ${latestRelay.destinationDisplayName}"
        } else {
            null
        }
        MeshRuntimeStatus(
            meshActive = core.active,
            bleState = core.bleState,
            neighborCount = metrics.neighborCount,
            queuedPacketCount = metrics.queuedPacketCount,
            lastRoutingStatus = formatRouteStatus(metrics.lastRouteEvent),
            persistentMeshEnabled = core.persistentEnabled,
            relayActive = relayActive,
            relaySummary = relaySummary,
            relayPacketId = if (relayActive) latestRelay?.packetId else null
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = MeshRuntimeStatus(
            persistentMeshEnabled = meshPreferencesRepository.isPersistentMeshEnabled()
        )
    )

    private var eventJob: Job? = null
    private var scanLoopJob: Job? = null
    private var cleanupJob: Job? = null

    fun restorePersistentServiceIfNeeded() {
        if (meshPreferencesRepository.isPersistentMeshEnabled()) {
            MeshRelayService.start(appContext)
        }
    }

    fun setPersistentMeshEnabled(enabled: Boolean) {
        meshPreferencesRepository.setPersistentMeshEnabled(enabled)
        if (enabled) {
            MeshRelayService.start(appContext)
        } else {
            MeshRelayService.stop(appContext)
        }
    }

    fun requestDiscoveryNow() {
        ensureFreshDiscoveryCycle()
        MeshRelayService.requestDiscovery(appContext)
    }

    fun triggerDiscoveryFromService() {
        ensureFreshDiscoveryCycle()
        scope.launch {
            try {
                bleMeshManager.startPeripheral()
                if (bleMeshManager.state.value != BleState.CONNECTING) {
                    bleMeshManager.startScan()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Service discovery request failed: ${e.message}")
            }
        }
    }

    private fun ensureFreshDiscoveryCycle() {
        bleMeshManager.resetDiscoveredPeers()
        _discoveredPeers.value = emptyList()
    }

    suspend fun connectAndOpen(peer: Peer): Conversation? {
        ensureRuntimeActive()
        val bleId = peer.bleId
        return if (bleId != null) {
            bleSyncCoordinator.connectAndOpen(bleId)
        } else {
            conversationRepository.getOrCreateConversation(peer.deviceId, peer.displayName)
        }
    }

    suspend fun ensureRuntimeActive() {
        if (_runtimeActive.value) return
        MeshRelayService.start(appContext)
        withTimeoutOrNull(SERVICE_START_TIMEOUT_MS) {
            runtimeStatus.first { it.meshActive }
        }
    }

    fun startRuntime() {
        scope.launch {
            runtimeMutex.withLock {
                if (_runtimeActive.value) {
                    Log.d(TAG, "startRuntime skipped instance=$tagInstance already active")
                    return@withLock
                }
                Log.d(TAG, "startRuntime begin instance=$tagInstance")
                _runtimeActive.value = true
                bleSyncCoordinator.start()
                beaconScheduler.start()
                relayQueueWorker.start()
                ensureEventCollector()
                ensureScanLoop()
                ensureCleanupLoop()
                scope.launch {
                    try {
                        bleMeshManager.startPeripheral()
                        if (!bleMeshManager.isConnected) {
                            bleMeshManager.startScan()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Initial mesh runtime start failed: ${e.message}")
                    }
                }
            }
        }
    }

    fun stopRuntime() {
        scope.launch {
            runtimeMutex.withLock {
                if (!_runtimeActive.value) return@withLock
                Log.d(TAG, "stopRuntime instance=$tagInstance")
                _runtimeActive.value = false
                scanLoopJob?.cancel()
                scanLoopJob = null
                cleanupJob?.cancel()
                cleanupJob = null
                eventJob?.cancel()
                eventJob = null
                _discoveredPeers.value = emptyList()
                beaconScheduler.stop()
                relayQueueWorker.stop()
                bleSyncCoordinator.stop()
                bleMeshManager.stopRuntime()
            }
        }
    }

    private fun ensureEventCollector() {
        if (eventJob?.isActive == true) return
        eventJob = scope.launch {
            bleMeshManager.events.collect { event ->
                if (event is BleMeshManager.BleEvent.PeerDiscovered) {
                    val updated = _discoveredPeers.value
                        .filterNot { it.displayName == event.peer.displayName } + event.peer
                    _discoveredPeers.value = updated.sortedByDescending { it.lastSeen }
                }
            }
        }
    }

    private fun ensureScanLoop() {
        if (scanLoopJob?.isActive == true) return
        scanLoopJob = scope.launch {
            while (isActive) {
                try {
                    if (!bleMeshManager.isConnected && bleMeshManager.state.value != BleState.CONNECTING) {
                        bleMeshManager.startScan()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Mesh scan loop error: ${e.message}")
                }
                delay(SCAN_LOOP_INTERVAL_MS)
            }
        }
    }

    private fun ensureCleanupLoop() {
        if (cleanupJob?.isActive == true) return
        cleanupJob = scope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL_MS)
                try {
                    meshRepository.pruneStaleNeighbors(MeshRepository.NEIGHBOR_STALE_MS)
                    meshRepository.pruneExpiredSeen()
                    meshRepository.pruneOldRouteEvents()
                } catch (e: Exception) {
                    Log.w(TAG, "Mesh cleanup error: ${e.message}")
                }
            }
        }
    }

    private fun formatRouteStatus(lastRouteEvent: RouteEventEntity?): String {
        if (lastRouteEvent == null) return "idle"
        val action = lastRouteEvent.action.lowercase().replace('_', ' ')
        val reason = lastRouteEvent.reason?.takeIf { it.isNotBlank() } ?: return action
        return "$action ($reason)"
    }

    companion object {
        private const val TAG = "MeshRuntimeRepo"
        private const val SCAN_LOOP_INTERVAL_MS = 15_000L
        private const val CLEANUP_INTERVAL_MS = 30_000L
        private const val SERVICE_START_TIMEOUT_MS = 5_000L
        private const val RELAY_ACTIVE_WINDOW_MS = 8_000L
        private const val RELAY_STATUS_TICK_MS = 1_000L
    }

    private data class RuntimeCore(
        val active: Boolean,
        val bleState: BleState,
        val persistentEnabled: Boolean
    )

    private data class RuntimeMetrics(
        val neighborCount: Int,
        val queuedPacketCount: Int,
        val lastRouteEvent: RouteEventEntity?
    )
}
