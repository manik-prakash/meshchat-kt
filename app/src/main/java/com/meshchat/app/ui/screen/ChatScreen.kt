package com.meshchat.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshchat.app.domain.Message
import com.meshchat.app.domain.MessageStatus
import com.meshchat.app.ui.theme.*
import com.meshchat.app.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(vm: ChatViewModel, peerName: String, onBack: () -> Unit) {
    val messages    by vm.messages.collectAsState()
    val myDeviceId  by vm.myPublicKey.collectAsState()
    val listState   = rememberLazyListState()
    val scope       = rememberCoroutineScope()
    var input       by remember { mutableStateOf("") }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        // Header — background extends behind status bar, content padded below it
        Row(
            Modifier
                .fillMaxWidth()
                .background(Surface)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("[<]", fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Accent,
                modifier = Modifier.clickable { onBack() }.padding(end = 12.dp))
            Text(peerName, fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = Primary, modifier = Modifier.weight(1f))
        }
        HorizontalDivider(color = Divider)

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg = msg, isMine = msg.senderDeviceId == myDeviceId, onRetry = { vm.retryMessage(msg.id) })
            }
        }

        HorizontalDivider(color = Divider)

        // Input row
        Row(
            Modifier
                .fillMaxWidth()
                .background(Surface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { if (it.length <= 500) input = it },
                placeholder = { Text("> type message...", fontFamily = FontFamily.Monospace, color = TextMuted, fontSize = 13.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    vm.sendMessage(input)
                    input = ""
                }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = Primary,
                    unfocusedBorderColor = TextMuted,
                    focusedTextColor     = TextPrimary,
                    unfocusedTextColor   = TextPrimary,
                    cursorColor          = Primary,
                ),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            val charCount = input.length
            if (charCount > 400) {
                Text("${500 - charCount}", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = if (charCount > 480) ErrorColor else TextMuted)
                Spacer(Modifier.width(4.dp))
            }
            TextButton(
                onClick = { vm.sendMessage(input); input = "" },
                colors = ButtonDefaults.textButtonColors(contentColor = Primary)
            ) {
                Text("[SND]", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: Message, isMine: Boolean, onRetry: () -> Unit) {
    val isFailure = msg.status in setOf(
        MessageStatus.FAILED, MessageStatus.FAILED_UNREACHABLE, MessageStatus.FAILED_EXPIRED
    )
    val canRetry = isFailure || msg.status == MessageStatus.QUEUED

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = canRetry) { onRetry() },
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
    ) {
        Text(
            msg.text,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = if (isFailure) ErrorColor else TextPrimary,
            modifier = Modifier
                .background(if (isMine) Surface else Background)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.createdAt)),
                fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = TextMuted
            )
            Spacer(Modifier.width(4.dp))
            val (statusText, statusColor) = when (msg.status) {
                MessageStatus.SENDING            -> "..."           to TextMuted
                MessageStatus.QUEUED             -> "[queued]"      to WarningColor
                MessageStatus.FORWARDED          -> "~"             to Accent
                MessageStatus.SENT               -> "v"             to TextMuted
                MessageStatus.DELIVERED          -> "vv"            to Primary
                MessageStatus.FAILED             -> "[failed]"      to ErrorColor
                MessageStatus.FAILED_UNREACHABLE -> "[unreachable]" to ErrorColor
                MessageStatus.FAILED_EXPIRED     -> "[expired]"     to ErrorColor
            }
            Text(statusText, fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = statusColor)
            if (canRetry) {
                Spacer(Modifier.width(4.dp))
                Text("tap to retry", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = TextMuted)
            }
        }
    }
}
