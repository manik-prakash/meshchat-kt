package com.meshchat.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meshchat.app.runtime.MeshPreferencesRepository

class MeshBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = MeshPreferencesRepository(context)
        if (prefs.isPersistentMeshEnabled()) {
            MeshRelayService.start(context)
        }
    }
}
