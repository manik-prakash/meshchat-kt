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

    private val runtimeRepository by lazy {
        (application as MeshChatApplication).container.meshRuntimeRepository
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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

        val summary = "Neighbors ${status.neighborCount} | Queue ${status.queuedPacketCount}"
        val details = "$summary | Route ${status.lastRoutingStatus}"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Mesh active")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Mesh relay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Foreground mesh relay status"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "mesh_relay_runtime"
        private const val NOTIFICATION_ID = 1001
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
