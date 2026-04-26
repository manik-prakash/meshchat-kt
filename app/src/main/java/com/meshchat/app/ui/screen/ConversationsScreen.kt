package com.meshchat.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshchat.app.domain.Conversation
import com.meshchat.app.ui.theme.*
import com.meshchat.app.ui.viewmodel.ConversationsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConversationsScreen(vm: ConversationsViewModel, onOpenChat: (Conversation) -> Unit) {
    val conversations by vm.conversations.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text("CONVERSATIONS", fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = Primary, modifier = Modifier.padding(vertical = 12.dp))
        HorizontalDivider(color = Divider)
        Spacer(Modifier.height(8.dp))

        if (conversations.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("no conversations yet.", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMuted)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                items(conversations, key = { it.id }) { conv ->
                    ConversationRow(conv = conv, onClick = { onOpenChat(conv) })
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(conv: Conversation, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(conv.peerDisplayName, fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Primary)
            conv.lastMessage?.let { last ->
                Text(
                    last,
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        conv.lastMessageAt?.let { ts ->
            Text(formatTimestamp(ts), fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextMuted)
        }
    }
}

private fun formatTimestamp(ts: Long): String {
    val now = System.currentTimeMillis()
    val fmt = if (now - ts < 86_400_000) SimpleDateFormat("HH:mm", Locale.getDefault())
              else SimpleDateFormat("MM/dd", Locale.getDefault())
    return fmt.format(Date(ts))
}
