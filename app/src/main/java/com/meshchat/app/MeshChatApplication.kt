package com.meshchat.app

import android.app.Application
import com.meshchat.app.ble.BleMeshManager
import com.meshchat.app.data.db.AppDatabase
import com.meshchat.app.data.repository.ConversationRepository
import com.meshchat.app.data.repository.IdentityRepository

class AppContainer(app: Application) {
    val db = AppDatabase.getInstance(app)
    val identityRepository = IdentityRepository(db.identityDao())
    val conversationRepository = ConversationRepository(db.conversationDao(), db.messageDao(), db.peerDao())
    val bleMeshManager = BleMeshManager(app, identityRepository, conversationRepository)
}

class MeshChatApplication : Application() {
    val container by lazy { AppContainer(this) }
}
