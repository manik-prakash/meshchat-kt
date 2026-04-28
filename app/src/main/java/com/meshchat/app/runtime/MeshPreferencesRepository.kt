package com.meshchat.app.runtime

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MeshPreferencesRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _persistentMeshEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_PERSISTENT_MESH_ENABLED, false))
    val persistentMeshEnabled: StateFlow<Boolean> = _persistentMeshEnabled.asStateFlow()

    fun isPersistentMeshEnabled(): Boolean = _persistentMeshEnabled.value

    fun setPersistentMeshEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PERSISTENT_MESH_ENABLED, enabled).apply()
        _persistentMeshEnabled.value = enabled
    }

    companion object {
        private const val PREFS_NAME = "mesh_runtime_prefs"
        private const val KEY_PERSISTENT_MESH_ENABLED = "persistent_mesh_enabled"
    }
}
