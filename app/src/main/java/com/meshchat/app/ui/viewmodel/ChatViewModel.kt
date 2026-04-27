package com.meshchat.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.app.ble.BleSyncCoordinator
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
    private val coordinator: BleSyncCoordinator
) : ViewModel() {

    val messages: StateFlow<List<Message>> = conversationRepo
        .getMessages(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _myPublicKey = MutableStateFlow("")
    val myPublicKey: StateFlow<String> = _myPublicKey.asStateFlow()

    init {
        viewModelScope.launch { _myPublicKey.value = identityRepo.ensureIdentity().publicKey }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val identity = identityRepo.ensureIdentity()
            val msg = conversationRepo.insertMessage(
                conversationId = conversationId,
                senderDeviceId = identity.publicKey,
                text = text.take(500),
                status = MessageStatus.SENDING
            )
            coordinator.sendMessage(
                msg,
                BlePayload.Message(
                    id             = msg.id,
                    senderDeviceId = identity.publicKey,
                    text           = msg.text,
                    timestamp      = msg.createdAt
                )
            )
        }
    }

    fun retryMessage(messageId: String) {
        viewModelScope.launch {
            val msg = messages.value.find { it.id == messageId } ?: return@launch
            if (msg.status != MessageStatus.FAILED) return@launch
            conversationRepo.updateMessageStatus(messageId, MessageStatus.SENDING)
            val identity = identityRepo.ensureIdentity()
            coordinator.sendMessage(
                msg,
                BlePayload.Message(
                    id             = msg.id,
                    senderDeviceId = identity.publicKey,
                    text           = msg.text,
                    timestamp      = msg.createdAt
                )
            )
        }
    }
}
