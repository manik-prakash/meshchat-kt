package com.meshchat.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.meshchat.app.MainActivity
import com.meshchat.app.MeshChatApplication
import com.meshchat.app.R
import com.meshchat.app.runtime.MeshRuntimeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MeshRelayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null
    private var lastRelayAlertPacketId: String? = null

    private val runtimeRepository by lazy {
        (application as MeshChatApplication).container.meshRuntimeRepository
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIFICATION_ID, buildNotification(runtimeRepository.runtimeStatus.value))
        observeNotificationState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                runtimeRepository.stopRuntime()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_DISCOVERY -> {
                runtimeRepository.startRuntime()
                runtimeRepository.triggerDiscoveryFromService()
            }
            ACTION_START -> runtimeRepository.startRuntime()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        notificationJob = null
        getSystemService(NotificationManager::class.java)?.cancel(RELAY_ALERT_NOTIFICATION_ID)
        runtimeRepository.stopRuntime()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeNotificationState() {
        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            runtimeRepository.runtimeStatus.collectLatest { status ->
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, buildNotification(status))
                maybeNotifyRelayAlert(manager, status)
            }
        }
    }

    private fun buildNotification(status: MeshRuntimeStatus): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (status.relayActive) "Relaying now" else "Mesh active"
        val summary = if (status.relayActive) {
            "Forwarding ${status.relaySummary ?: "mesh traffic"}"
        } else {
            "Neighbors ${status.neighborCount} | Queue ${status.queuedPacketCount}"
        }
        val details = if (status.relayActive) {
            "$summary | Neighbors ${status.neighborCount} | Queue ${status.queuedPacketCount}"
        } else {
            "$summary | Route ${status.lastRoutingStatus}"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent)
            .build()
    }

    private fun buildRelayAlert(status: MeshRuntimeStatus): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, RELAY_ALERT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Relay node in use")
            .setContentText("Forwarding ${status.relaySummary ?: "mesh traffic"}")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "This phone is currently forwarding ${status.relaySummary ?: "mesh traffic"}."
                )
            )
            .setAutoCancel(true)
            .setTimeoutAfter(RELAY_ALERT_TIMEOUT_MS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openAppIntent)
            .build()
    }

    private fun maybeNotifyRelayAlert(manager: NotificationManager, status: MeshRuntimeStatus) {
        val relayPacketId = status.relayPacketId
        if (status.relayActive && relayPacketId != null && relayPacketId != lastRelayAlertPacketId) {
            manager.notify(RELAY_ALERT_NOTIFICATION_ID, buildRelayAlert(status))
            lastRelayAlertPacketId = relayPacketId
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val runtimeChannel = NotificationChannel(
            CHANNEL_ID,
            "Mesh relay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Foreground mesh relay status"
        }
        val relayAlertChannel = NotificationChannel(
            RELAY_ALERT_CHANNEL_ID,
            "Relay activity",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Alerts when this phone is forwarding messages for other people"
        }
        getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(runtimeChannel)
            createNotificationChannel(relayAlertChannel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "mesh_relay_runtime"
        private const val NOTIFICATION_ID = 1001
        private const val RELAY_ALERT_CHANNEL_ID = "mesh_relay_activity"
        private const val RELAY_ALERT_NOTIFICATION_ID = 1002
        private const val RELAY_ALERT_TIMEOUT_MS = 8_000L
        private const val ACTION_START = "com.meshchat.app.action.MESH_START"
        private const val ACTION_STOP = "com.meshchat.app.action.MESH_STOP"
        private const val ACTION_DISCOVERY = "com.meshchat.app.action.MESH_DISCOVERY"

        fun start(context: Context) {
            val intent = Intent(context, MeshRelayService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MeshRelayService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun requestDiscovery(context: Context) {
            val intent = Intent(context, MeshRelayService::class.java).setAction(ACTION_DISCOVERY)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
