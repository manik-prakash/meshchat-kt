package com.meshchat.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.app.ble.BleMeshManager
import com.meshchat.app.data.repository.ConversationRepository
import com.meshchat.app.data.repository.IdentityRepository
import com.meshchat.app.domain.BlePayload
import com.meshchat.app.domain.Message
import com.meshchat.app.domain.MessageStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(
    private val conversationId: String,
    private val peerDeviceId: String,
    private val identityRepo: IdentityRepository,
    private val conversationRepo: ConversationRepository,
    private val bleMeshManager: BleMeshManager
) : ViewModel() {

    val messages: StateFlow<List<Message>> = conversationRepo
        .getMessages(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _myDeviceId = MutableStateFlow("")
    val myDeviceId: StateFlow<String> = _myDeviceId.asStateFlow()

    init {
        viewModelScope.launch { _myDeviceId.value = identityRepo.ensureIdentity().deviceId }
        observeBleEvents()
    }

    private fun observeBleEvents() {
        viewModelScope.launch {
            bleMeshManager.events.collect { event ->
                if (event is BleMeshManager.BleEvent.PayloadReceived) {
                    handleIncomingPayload(event.payload)
                }
            }
        }
    }

    private suspend fun handleIncomingPayload(payload: BlePayload) {
        when (payload) {
            is BlePayload.Message -> {
                if (conversationRepo.messageExists(payload.id)) return
                val identity = identityRepo.ensureIdentity()
                conversationRepo.insertMessage(
                    conversationId = conversationId,
                    senderDeviceId = payload.senderDeviceId,
                    text = payload.text,
                    status = MessageStatus.SENT,
                    messageId = payload.id
                )
                bleMeshManager.sendAck(payload.id)
            }
            is BlePayload.Ack -> {
                conversationRepo.updateMessageStatus(payload.messageId, MessageStatus.SENT)
            }
            is BlePayload.Handshake -> { /* handled in NearbyViewModel */ }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val identity = identityRepo.ensureIdentity()
            // Optimistic insert
            val msg = conversationRepo.insertMessage(
                conversationId = conversationId,
                senderDeviceId = identity.deviceId,
                text = text.take(500),
                status = MessageStatus.SENDING
            )
            try {
                bleMeshManager.sendMessage(
                    BlePayload.Message(
                        id = msg.id,
                        senderDeviceId = identity.deviceId,
                        text = msg.text,
                        timestamp = msg.createdAt
                    )
                )
                conversationRepo.updateMessageStatus(msg.id, MessageStatus.SENT)
            } catch (e: Exception) {
                conversationRepo.updateMessageStatus(msg.id, MessageStatus.FAILED)
            }
        }
    }

    fun retryMessage(messageId: String) {
        viewModelScope.launch {
            val msg = messages.value.find { it.id == messageId } ?: return@launch
            if (msg.status != MessageStatus.FAILED) return@launch
            conversationRepo.updateMessageStatus(messageId, MessageStatus.SENDING)
            val identity = identityRepo.ensureIdentity()
            try {
                bleMeshManager.sendMessage(
                    BlePayload.Message(
                        id = msg.id,
                        senderDeviceId = identity.deviceId,
                        text = msg.text,
                        timestamp = msg.createdAt
                    )
                )
                conversationRepo.updateMessageStatus(msg.id, MessageStatus.SENT)
            } catch (e: Exception) {
                conversationRepo.updateMessageStatus(msg.id, MessageStatus.FAILED)
            }
        }
    }
}
