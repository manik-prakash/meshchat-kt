package com.meshchat.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.app.data.repository.ConversationRepository
import com.meshchat.app.data.repository.IdentityRepository
import com.meshchat.app.domain.Message
import com.meshchat.app.domain.MessageStatus
import com.meshchat.app.runtime.MeshRuntimeRepository
import com.meshchat.app.routing.MeshRouter
import com.meshchat.app.routing.RoutingDecision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(
    private val conversationId: String,
    private val peerDeviceId: String,
    private val peerDisplayName: String,
    private val identityRepo: IdentityRepository,
    private val conversationRepo: ConversationRepository,
    private val meshRuntimeRepository: MeshRuntimeRepository,
    private val meshRouter: MeshRouter
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
            meshRuntimeRepository.ensureRuntimeActive()
            val identity = identityRepo.ensureIdentity()
            val msg = conversationRepo.insertMessage(
                conversationId = conversationId,
                senderDeviceId = identity.publicKey,
                text           = text.take(500),
                status         = MessageStatus.QUEUED
            )
            val decision = meshRouter.originate(
                packetId             = msg.id,
                destinationId        = peerDeviceId,
                destinationDisplayName = peerDisplayName,
                text                 = msg.text,
                timestamp            = msg.createdAt
            )
            conversationRepo.updateMessageStatus(msg.id, decision.toMessageStatus())
        }
    }

    fun retryMessage(messageId: String) {
        viewModelScope.launch {
            meshRuntimeRepository.ensureRuntimeActive()
            val msg = messages.value.find { it.id == messageId } ?: return@launch
            if (msg.status !in RETRYABLE_STATUSES) return@launch
            conversationRepo.updateMessageStatus(messageId, MessageStatus.QUEUED)
            val decision = meshRouter.originate(
                packetId             = messageId,
                destinationId        = peerDeviceId,
                destinationDisplayName = peerDisplayName,
                text                 = msg.text,
                timestamp            = msg.createdAt
            )
            conversationRepo.updateMessageStatus(messageId, decision.toMessageStatus())
        }
    }

    companion object {
        private val RETRYABLE_STATUSES = setOf(
            MessageStatus.FAILED,
            MessageStatus.FAILED_UNREACHABLE,
            MessageStatus.FAILED_EXPIRED,
            MessageStatus.QUEUED
        )
    }
}

private fun RoutingDecision.toMessageStatus(): MessageStatus = when (this) {
    RoutingDecision.DIRECT,
    RoutingDecision.FORWARDED   -> MessageStatus.FORWARDED
    RoutingDecision.QUEUED      -> MessageStatus.QUEUED
    RoutingDecision.UNREACHABLE -> MessageStatus.FAILED_UNREACHABLE
}
