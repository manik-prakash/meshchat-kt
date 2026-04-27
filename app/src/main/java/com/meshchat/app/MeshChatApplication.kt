package com.meshchat.app

import android.app.Application
import com.meshchat.app.ble.BleMeshManager
import com.meshchat.app.ble.BleSyncCoordinator
import com.meshchat.app.crypto.CryptoManager
import com.meshchat.app.data.db.AppDatabase
import com.meshchat.app.data.repository.ConversationRepository
import com.meshchat.app.data.repository.IdentityRepository
import com.meshchat.app.data.repository.MeshRepository

class AppContainer(app: Application) {
    val crypto = CryptoManager()
    val db = AppDatabase.getInstance(app)
    val identityRepository = IdentityRepository(db.identityDao(), crypto)
    val conversationRepository = ConversationRepository(db.conversationDao(), db.messageDao(), db.peerDao())
    val meshRepository = MeshRepository(
        db.seenPacketDao(), db.relayQueueDao(), db.neighborDao(),
        db.knownContactDao(), db.routeEventDao()
    )
    val bleMeshManager = BleMeshManager(app, identityRepository, conversationRepository)
    val bleSyncCoordinator = BleSyncCoordinator(bleMeshManager, conversationRepository, identityRepository)
}

class MeshChatApplication : Application() {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container.bleSyncCoordinator.start()
    }
}
