package com.meshchat.app

import android.app.Application
import com.meshchat.app.ble.BeaconScheduler
import com.meshchat.app.ble.BleMeshManager
import com.meshchat.app.ble.BleSyncCoordinator
import com.meshchat.app.crypto.CryptoManager
import com.meshchat.app.data.db.AppDatabase
import com.meshchat.app.data.repository.ConversationRepository
import com.meshchat.app.data.repository.IdentityRepository
import com.meshchat.app.data.repository.MeshRepository
import com.meshchat.app.domain.BlePayload
import com.meshchat.app.domain.MessageStatus
import com.meshchat.app.location.LocationProvider
import com.meshchat.app.runtime.MeshPreferencesRepository
import com.meshchat.app.runtime.MeshRuntimeRepository
import com.meshchat.app.routing.MeshRouter
import com.meshchat.app.routing.MeshRouterImpl
import com.meshchat.app.routing.RelayQueueWorker
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(app: Application) {
    private val tag = "AppContainer"
    val crypto             = CryptoManager()
    val db                 = AppDatabase.getInstance(app)
    val identityRepository = IdentityRepository(db.identityDao(), crypto)
    val conversationRepository = ConversationRepository(db.conversationDao(), db.messageDao(), db.peerDao())
    val meshRepository     = MeshRepository(
        db.seenPacketDao(), db.relayQueueDao(), db.neighborDao(),
        db.knownContactDao(), db.routeEventDao()
    )
    val meshPreferencesRepository = MeshPreferencesRepository(app)
    val bleMeshManager     = BleMeshManager(app, identityRepository, conversationRepository)
    val locationProvider   = LocationProvider(app)

    private val appScope   = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Delivers a routed message addressed to this node: persists to DB + sends DeliveryAck. */
    private val deliverLocally: suspend (BlePayload.RoutedMessage) -> Unit = { packet ->
        Log.d(
            tag,
            "Deliver locally packet=${packet.packetId.takeLast(8)} src=${packet.sourcePublicKey.take(12)} " +
                "srcName=${packet.sourceDisplayNameSnapshot}"
        )
        val conv = conversationRepository.getOrCreateConversation(
            packet.sourcePublicKey, packet.sourceDisplayNameSnapshot
        )
        if (!conversationRepository.messageExists(packet.packetId)) {
            conversationRepository.insertMessage(
                conversationId = conv.id,
                senderDeviceId = packet.sourcePublicKey,
                text           = packet.body,
                status         = MessageStatus.SENT,
                messageId      = packet.packetId
            )
        }
        val identity = identityRepository.ensureIdentity()
        val now      = System.currentTimeMillis()
        val sig      = crypto.signDeliveryAck(packet.packetId, identity.publicKey, now)
        val ack      = BlePayload.DeliveryAck(packet.packetId, identity.publicKey, now, packet.hopCount, sig)
        Log.d(tag, "Emit DeliveryAck packet=${packet.packetId.takeLast(8)} from=${identity.publicKey.take(12)}")
        // Use the bidirectional broadcast so the ACK reaches the sender regardless of
        // which device initiated the BLE connection (central vs peripheral role).
        bleMeshManager.broadcastDeliveryAck(ack)
    }

    val meshRouter: MeshRouter = MeshRouterImpl(
        identityRepo       = identityRepository,
        scope              = appScope,
        meshRepository     = meshRepository,
        crypto             = crypto,
        locationProvider   = locationProvider,
        forwardPacket      = { packet, bleAddress -> bleMeshManager.sendRoutedMessageTo(packet, bleAddress) },
        deliverLocally     = deliverLocally,
        reportFailure      = { failure -> bleMeshManager.broadcastRouteFailure(failure) },
        canReachBleAddress = bleMeshManager::canReachBleAddress,
        onQueuedForwarded  = { packetId ->
            conversationRepository.updateMessageStatus(packetId, MessageStatus.FORWARDED)
        }
    )

    val relayQueueWorker = RelayQueueWorker(
        meshRepository        = meshRepository,
        conversationRepository = conversationRepository,
        meshRouter            = meshRouter,
        scope                 = appScope
    )

    val bleSyncCoordinator = BleSyncCoordinator(
        bleMeshManager, conversationRepository, identityRepository,
        crypto, meshRepository, meshRouter
    )

    val beaconScheduler = BeaconScheduler(
        identityRepository, bleMeshManager, crypto, locationProvider
    )

    val meshRuntimeRepository = MeshRuntimeRepository(
        context = app,
        meshPreferencesRepository = meshPreferencesRepository,
        meshRepository = meshRepository,
        bleMeshManager = bleMeshManager,
        bleSyncCoordinator = bleSyncCoordinator,
        beaconScheduler = beaconScheduler,
        relayQueueWorker = relayQueueWorker
    )
}

class MeshChatApplication : Application() {
    val container by lazy { AppContainer(this) }

    override fun onCreate() { super.onCreate() }
}
