package com.meshchat.app.ble

import android.util.Log
import com.meshchat.app.crypto.CryptoManager
import com.meshchat.app.data.db.entity.NeighborEntity
import com.meshchat.app.data.repository.ConversationRepository
import com.meshchat.app.data.repository.IdentityRepository
import com.meshchat.app.data.repository.MeshRepository
import com.meshchat.app.domain.BlePayload
import com.meshchat.app.domain.Conversation
import com.meshchat.app.domain.Message
import com.meshchat.app.domain.MessageStatus
import com.meshchat.app.domain.Peer
import com.meshchat.app.routing.MeshRouter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

class BleSyncCoordinator(
    private val bleMeshManager: BleMeshManager,
    private val conversationRepo: ConversationRepository,
    private val identityRepo: IdentityRepository,
    private val crypto: CryptoManager,
    private val meshRepo: MeshRepository,
    private val meshRouter: MeshRouter
) {
    private val TAG = "BleSyncCoord"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val BEACON_MAX_AGE_MS = 60_000L
        private const val BEACON_FUTURE_TOLERANCE_MS = 10_000L
    }

    // BLE address -> deferred handshake, awaited by connectAndOpen()
    private val pendingHandshakes = ConcurrentHashMap<String, CompletableDeferred<BlePayload.Handshake>>()
    // BLE address -> conversationId
    private val resolvedSessions = ConcurrentHashMap<String, String>()
    // BLE address -> peer public key, for onNeighborAvailable notifications
    private val resolvedPeerKeys = ConcurrentHashMap<String, String>()
    // BLE address -> messages received before handshake resolved
    private val messageBuffer = ConcurrentHashMap<String, MutableList<BlePayload.Message>>()
    // messageId -> ACK timeout job (legacy single-hop path)
    private val pendingAcks = ConcurrentHashMap<String, Job>()
    private var eventJob: Job? = null

    fun start() {
        if (eventJob?.isActive == true) return
        eventJob = scope.launch { observeEvents() }
    }

    fun stop() {
        eventJob?.cancel()
        eventJob = null
        pendingHandshakes.clear()
        resolvedSessions.clear()
        resolvedPeerKeys.clear()
        messageBuffer.clear()
        pendingAcks.values.forEach { it.cancel() }
        pendingAcks.clear()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun connectAndOpen(bleId: String): Conversation? {
        val deferred = CompletableDeferred<BlePayload.Handshake>()
        pendingHandshakes[bleId] = deferred

        val connected = bleMeshManager.connectToPeer(bleId)
        if (!connected) {
            pendingHandshakes.remove(bleId)
            return null
        }

        val handshake = try {
            withTimeout(5_000) { deferred.await() }
        } catch (e: Exception) {
            Log.w(TAG, "Handshake timeout for $bleId")
            pendingHandshakes.remove(bleId)
            return null
        }

        val conversation = resolveConversation(handshake.nodeId, handshake.displayName, bleId)
        resolvedSessions[bleId] = conversation.id
        resolvedPeerKeys[bleId] = handshake.nodeId
        messageBuffer.remove(bleId)?.forEach { processIncomingMessage(it, conversation.id) }
        upsertNeighborFromHandshake(handshake.nodeId, handshake.displayName, bleId)
        meshRouter.onNeighborAvailable(handshake.nodeId)
        return conversation
    }

    /** Legacy single-hop direct message send (used when BLE session is active). */
    suspend fun sendMessage(msg: Message, payload: BlePayload.Message) {
        val timeoutJob = scope.launch {
            delay(10_000)
            if (pendingAcks.remove(msg.id) != null) {
                Log.w(TAG, "ACK timeout for ${msg.id}")
                conversationRepo.updateMessageStatus(msg.id, MessageStatus.FAILED)
            }
        }
        pendingAcks[msg.id] = timeoutJob

        try {
            bleMeshManager.sendMessage(payload)
        } catch (e: Exception) {
            Log.w(TAG, "sendMessage failed for ${msg.id}: ${e.message}")
            pendingAcks.remove(msg.id)?.cancel()
            conversationRepo.updateMessageStatus(msg.id, MessageStatus.FAILED)
        }
    }

    // ── Event loop ────────────────────────────────────────────────────────────

    private suspend fun observeEvents() {
        bleMeshManager.events.collect { event ->
            if (event is BleMeshManager.BleEvent.PayloadReceived) {
                scope.launch { handlePayload(event.payload, event.fromAddress) }
            }
        }
    }

    private suspend fun handlePayload(payload: BlePayload, fromAddress: String) {
        when (payload) {
            is BlePayload.Handshake      -> handleHandshake(payload, fromAddress)
            is BlePayload.Message        -> handleMessage(payload, fromAddress)
            is BlePayload.Ack            -> handleAck(payload)
            is BlePayload.Beacon         -> handleBeacon(payload, fromAddress)
            is BlePayload.RoutedMessage  -> meshRouter.onMessageReceived(payload, fromAddress)
            is BlePayload.RouteAck       -> { /* hop-level ack; unused in Phase 5 */ }
            is BlePayload.DeliveryAck    -> handleDeliveryAck(payload)
            is BlePayload.RouteFailure   -> handleRouteFailure(payload)
        }
    }

    private suspend fun handleHandshake(handshake: BlePayload.Handshake, fromAddress: String) {
        val pending = pendingHandshakes.remove(fromAddress)
        if (pending != null) {
            pending.complete(handshake)
            return
        }
        // Inbound connection (we are peripheral)
        val conversation = resolveConversation(handshake.nodeId, handshake.displayName, fromAddress)
        resolvedSessions[fromAddress] = conversation.id
        resolvedPeerKeys[fromAddress] = handshake.nodeId
        messageBuffer.remove(fromAddress)?.forEach { processIncomingMessage(it, conversation.id) }
        val identity = identityRepo.ensureIdentity()
        for (fragment in MeshProtocolCodec.encode(BlePayload.Handshake(identity.publicKey, identity.displayName))) {
            bleMeshManager.gattServer.sendNotification(BleConstants.HANDSHAKE_CHAR_UUID, fragment)
        }
        upsertNeighborFromHandshake(handshake.nodeId, handshake.displayName, fromAddress)
        meshRouter.onNeighborAvailable(handshake.nodeId)
    }

    private suspend fun handleMessage(msg: BlePayload.Message, fromAddress: String) {
        val conversationId = resolvedSessions[fromAddress]
        if (conversationId != null) {
            processIncomingMessage(msg, conversationId)
        } else {
            Log.d(TAG, "Buffering msg ${msg.id} from $fromAddress (no session yet)")
            messageBuffer.getOrPut(fromAddress) { mutableListOf() }.add(msg)
        }
    }

    private suspend fun processIncomingMessage(msg: BlePayload.Message, conversationId: String) {
        if (conversationRepo.messageExists(msg.id)) return
        conversationRepo.insertMessage(
            conversationId = conversationId,
            senderDeviceId = msg.senderDeviceId,
            text           = msg.text,
            status         = MessageStatus.SENT,
            messageId      = msg.id
        )
        try { bleMeshManager.sendAck(msg.id) } catch (e: Exception) {
            Log.w(TAG, "sendAck failed for ${msg.id}: ${e.message}")
        }
    }

    private fun handleAck(ack: BlePayload.Ack) {
        val job = pendingAcks.remove(ack.messageId) ?: return
        job.cancel()
        scope.launch { conversationRepo.updateMessageStatus(ack.messageId, MessageStatus.SENT) }
    }

    // ── Mesh delivery ack / failure ───────────────────────────────────────────

    private suspend fun handleDeliveryAck(ack: BlePayload.DeliveryAck) {
        if (ack.signature.isNotEmpty() && !crypto.verifyDeliveryAck(
                ack.destinationNodeId, ack.packetId, ack.destinationNodeId,
                ack.timestamp, ack.signature
            )
        ) {
            Log.w(TAG, "DeliveryAck sig invalid for ${ack.packetId}")
            return
        }
        conversationRepo.updateMessageStatus(ack.packetId, MessageStatus.DELIVERED)
        meshRepo.markRelayDelivered(ack.packetId)
        Log.d(TAG, "DeliveryAck: ${ack.packetId} delivered")
    }

    private fun handleRouteFailure(failure: BlePayload.RouteFailure) {
        scope.launch {
            conversationRepo.updateMessageStatus(failure.packetId, MessageStatus.FAILED_EXPIRED)
            meshRepo.markRelayFailed(failure.packetId, failure.reason)
            Log.d(TAG, "RouteFailure for ${failure.packetId}: ${failure.reason}")
        }
    }

    // ── Beacon handling ───────────────────────────────────────────────────────

    private suspend fun handleBeacon(beacon: BlePayload.Beacon, fromAddress: String) {
        val now = System.currentTimeMillis()
        if (beacon.timestamp < now - BEACON_MAX_AGE_MS || beacon.timestamp > now + BEACON_FUTURE_TOLERANCE_MS) {
            Log.d(TAG, "Rejecting stale/future beacon from ${beacon.nodeId.take(16)}")
            return
        }
        if (beacon.signature.isNotEmpty() &&
            !crypto.verifyBeacon(
                peerPublicKeyBase64 = beacon.nodeId,
                nodeId = beacon.nodeId,
                displayName = beacon.displayName,
                timestamp = beacon.timestamp,
                seqNum = beacon.seqNum,
                relayCapable = beacon.relayCapable,
                geohash = beacon.geohash,
                lat = beacon.lat,
                lon = beacon.lon,
                signatureBase64 = beacon.signature
            )
        ) {
            Log.w(TAG, "Beacon signature invalid from ${beacon.nodeId.take(16)}")
            return
        }

        val existing = meshRepo.getNeighborByPublicKey(beacon.nodeId)
        meshRepo.upsertNeighbor(
            NeighborEntity(
                neighborPublicKey = beacon.nodeId,
                displayName       = beacon.displayName,
                bleAddress        = fromAddress,
                rssi              = existing?.rssi,
                lastSeen          = now,
                relayCapable      = beacon.relayCapable,
                geohash           = beacon.geohash.ifEmpty { null },
                lat               = if (beacon.lat != 0.0) beacon.lat else null,
                lon               = if (beacon.lon != 0.0) beacon.lon else null,
                trustScore        = existing?.trustScore ?: 0.0
            )
        )

        if (beacon.geohash.isNotEmpty()) {
            val contact = meshRepo.getContact(beacon.nodeId)
            if (contact != null) {
                meshRepo.updateContactLocation(beacon.nodeId, beacon.geohash, beacon.lat, beacon.lon)
            }
        }

        // Every beacon from a relay-capable neighbor can unlock a previously un-routable packet,
        // so trigger a queue drain attempt every time. The drain is a no-op when the queue is
        // empty, so the overhead is just one DB read per beacon.
        if (beacon.relayCapable) {
            meshRouter.onNeighborAvailable(beacon.nodeId)
        }

        Log.d(TAG, "Beacon from ${beacon.displayName} geo=${beacon.geohash.ifEmpty { "none" }}")
    }

    // ── Neighbor registration ─────────────────────────────────────────────────

    private suspend fun upsertNeighborFromHandshake(publicKey: String, displayName: String, bleAddress: String) {
        val existing = meshRepo.getNeighborByPublicKey(publicKey)
        meshRepo.upsertNeighbor(
            NeighborEntity(
                neighborPublicKey = publicKey,
                displayName       = displayName,
                bleAddress        = bleAddress,
                rssi              = existing?.rssi,
                lastSeen          = System.currentTimeMillis(),
                relayCapable      = existing?.relayCapable ?: true,
                geohash           = existing?.geohash,
                lat               = existing?.lat,
                lon               = existing?.lon,
                trustScore        = existing?.trustScore ?: 0.0
            )
        )
    }

    // ── Session resolution ────────────────────────────────────────────────────

    private suspend fun resolveConversation(
        peerPublicKey: String,
        displayName: String,
        bleId: String
    ): Conversation {
        var conv = conversationRepo.getConversationByPeerDeviceId(peerPublicKey)

        if (conv == null) {
            val byName = conversationRepo.getConversationByPeerDisplayName(displayName)
            if (byName != null) {
                Log.i(TAG, "Migrating peer identity: ${byName.peerDeviceId.take(16)} → ${peerPublicKey.take(16)}")
                conversationRepo.repairPeerIdentity(byName.id, peerPublicKey, displayName)
                conv = byName.copy(peerDeviceId = peerPublicKey, peerDisplayName = displayName)
            }
        }

        if (conv == null) conv = conversationRepo.getOrCreateConversation(peerPublicKey, displayName)

        conversationRepo.upsertPeer(
            Peer(deviceId = peerPublicKey, displayName = displayName,
                 lastSeen = System.currentTimeMillis(), rssi = null, bleId = bleId)
        )
        return conv
    }
}
