package com.meshchat.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.app.data.repository.ConversationRepository
import com.meshchat.app.domain.Conversation
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class ConversationsViewModel(conversationRepo: ConversationRepository) : ViewModel() {

    val conversations: StateFlow<List<Conversation>> = conversationRepo
        .getConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
