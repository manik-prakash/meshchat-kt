package com.meshchat.app.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionHelper {

    /**
     * Runtime permissions needed to scan for and connect to BLE peers.
     *
     * API 29-30: ACCESS_FINE_LOCATION (BLUETOOTH/BLUETOOTH_ADMIN are normal perms, auto-granted).
     * API 31+  : BLUETOOTH_SCAN + BLUETOOTH_CONNECT.
     */
    fun coreBlePermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /**
     * Permission to advertise via BLE (API 31+).
     * Returns null on API 29-30 where advertising needs no runtime permission.
     */
    fun advertisingPermission(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_ADVERTISE else null

    /** Fine location for geo-routing beacons (optional but improves multi-hop routing). */
    const val LOCATION = Manifest.permission.ACCESS_FINE_LOCATION

    /** Background location required for the persistent relay foreground service. */
    const val BACKGROUND_LOCATION = Manifest.permission.ACCESS_BACKGROUND_LOCATION

    /**
     * POST_NOTIFICATIONS permission (API 33+ only).
     * Returns null on API < 33 where notifications are granted implicitly.
     */
    fun notificationPermission(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null

    // ── Capability queries ────────────────────────────────────────────────────

    fun hasBluetoothHardware(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    fun hasCoreBle(context: Context): Boolean =
        coreBlePermissions().all { isGranted(context, it) }

    fun hasAdvertising(context: Context): Boolean =
        advertisingPermission()?.let { isGranted(context, it) } ?: true

    fun hasLocation(context: Context): Boolean =
        isGranted(context, LOCATION)

    fun hasBackgroundLocation(context: Context): Boolean =
        isGranted(context, BACKGROUND_LOCATION)

    fun hasNotifications(context: Context): Boolean =
        notificationPermission()?.let { isGranted(context, it) } ?: true

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
