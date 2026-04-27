package com.meshchat.app.ble

import android.util.Log
import com.meshchat.app.data.repository.ConversationRepository
import com.meshchat.app.data.repository.IdentityRepository
import com.meshchat.app.domain.BlePayload
import com.meshchat.app.domain.Conversation
import com.meshchat.app.domain.Message
import com.meshchat.app.domain.MessageStatus
import com.meshchat.app.domain.Peer
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
    private val identityRepo: IdentityRepository
) {
    private val TAG = "BleSyncCoord"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // BLE address -> deferred handshake, awaited by connectAndOpen()
    private val pendingHandshakes = ConcurrentHashMap<String, CompletableDeferred<BlePayload.Handshake>>()
    // BLE address -> conversationId
    private val resolvedSessions = ConcurrentHashMap<String, String>()
    // BLE address -> messages received before handshake resolved
    private val messageBuffer = ConcurrentHashMap<String, MutableList<BlePayload.Message>>()
    // messageId -> ACK timeout job
    private val pendingAcks = ConcurrentHashMap<String, Job>()

    fun start() {
        scope.launch { observeEvents() }
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
        messageBuffer.remove(bleId)?.forEach { processIncomingMessage(it, conversation.id) }
        return conversation
    }

    suspend fun sendMessage(msg: Message, payload: BlePayload.Message) {
        // Register before sending so a fast ACK isn't dropped by handleAck()
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
            // PeerDiscovered: NearbyViewModel handles UI; peer upsert already done in BleMeshManager
        }
    }

    private suspend fun handlePayload(payload: BlePayload, fromAddress: String) {
        when (payload) {
            is BlePayload.Handshake      -> handleHandshake(payload, fromAddress)
            is BlePayload.Message        -> handleMessage(payload, fromAddress)
            is BlePayload.Ack            -> handleAck(payload)
            // Mesh routing packets are handled by MeshRouter, not this coordinator
            is BlePayload.Beacon,
            is BlePayload.RoutedMessage,
            is BlePayload.RouteAck,
            is BlePayload.DeliveryAck,
            is BlePayload.RouteFailure   -> { /* forwarded to MeshRouter when wired up */ }
        }
    }

    private suspend fun handleHandshake(handshake: BlePayload.Handshake, fromAddress: String) {
        val pending = pendingHandshakes.remove(fromAddress)
        if (pending != null) {
            // connectAndOpen() is waiting; it will call resolveConversation after await() returns
            pending.complete(handshake)
            return
        }
        // Inbound connection (we are peripheral): resolve session ourselves
        val conversation = resolveConversation(handshake.nodeId, handshake.displayName, fromAddress)
        resolvedSessions[fromAddress] = conversation.id
        messageBuffer.remove(fromAddress)?.forEach { processIncomingMessage(it, conversation.id) }
        // Send our identity back so the central can also resolve the conversation
        val identity = identityRepo.ensureIdentity()
        for (fragment in MeshProtocolCodec.encode(BlePayload.Handshake(identity.publicKey, identity.displayName))) {
            bleMeshManager.gattServer.sendNotification(BleConstants.HANDSHAKE_CHAR_UUID, fragment)
        }
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

    // ── Session resolution ────────────────────────────────────────────────────

    private suspend fun resolveConversation(
        peerPublicKey: String,
        displayName: String,
        bleId: String
    ): Conversation {
        // 1. Exact match by stable public key (stored in peerDeviceId column)
        var conv = conversationRepo.getConversationByPeerDeviceId(peerPublicKey)

        if (conv == null) {
            // 2. Legacy repair: old installs stored a UUID there, fall back to display name
            val byName = conversationRepo.getConversationByPeerDisplayName(displayName)
            if (byName != null) {
                Log.i(TAG, "Migrating peer identity to public key: ${byName.peerDeviceId.take(16)} -> ${peerPublicKey.take(16)}")
                conversationRepo.repairPeerIdentity(byName.id, peerPublicKey, displayName)
                conv = byName.copy(peerDeviceId = peerPublicKey, peerDisplayName = displayName)
            }
        }

        if (conv == null) {
            // 3. Brand new peer
            conv = conversationRepo.getOrCreateConversation(peerPublicKey, displayName)
        }

        // Always update peer record with current BLE address
        conversationRepo.upsertPeer(
            Peer(deviceId = peerPublicKey, displayName = displayName,
                 lastSeen = System.currentTimeMillis(), rssi = null, bleId = bleId)
        )

        return conv
    }
}
