package com.meshchat.app.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.meshchat.app.ble.BleConstants.ACK_CHAR_UUID
import com.meshchat.app.ble.BleConstants.CCCD_UUID
import com.meshchat.app.ble.BleConstants.HANDSHAKE_CHAR_UUID
import com.meshchat.app.ble.BleConstants.MESSAGE_CHAR_UUID
import com.meshchat.app.ble.BleConstants.SERVICE_UUID
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Manages the BLE GATT server (peripheral role): advertising, connection tracking,
 * incoming write handling, and notification delivery.
 *
 * Behavioral reference: BlePeripheralModule.kt from the Expo native module.
 */
@SuppressLint("MissingPermission")
class GattServerManager(private val context: Context) {

    private val TAG = "GattServer"

    sealed class Event {
        data class DeviceConnected(val device: BluetoothDevice) : Event()
        data class DeviceDisconnected(val device: BluetoothDevice) : Event()
        data class WriteReceived(val deviceAddress: String, val charUUID: String, val value: ByteArray) : Event()
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = bluetoothManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertising = false

    private val connectedDevices = mutableSetOf<BluetoothDevice>()
    val connectedDeviceCount: Int get() = connectedDevices.size

    private val characteristics = mutableMapOf<String, BluetoothGattCharacteristic>()

    // Buffers for BLE "prepared writes" (long writes split by the client stack)
    private val preparedWriteBuffers = ConcurrentHashMap<String, ConcurrentHashMap<String, ByteArrayOutputStream>>()

    private val _events = MutableSharedFlow<Event>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<Event> = _events

    // ── GATT Server Callback ────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Central connected: ${device.address}")
                    connectedDevices.add(device)
                    _events.tryEmit(Event.DeviceConnected(device))
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Central disconnected: ${device.address}")
                    connectedDevices.remove(device)
                    preparedWriteBuffers.remove(device.address)
                    _events.tryEmit(Event.DeviceDisconnected(device))
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray
        ) {
            Log.d(TAG, "Write: char=${characteristic.uuid}, prepared=$preparedWrite, ${value.size}B from ${device.address}")
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
            if (preparedWrite) {
                val devBufs = preparedWriteBuffers.getOrPut(device.address) { ConcurrentHashMap() }
                val charKey = characteristic.uuid.toString()
                devBufs.getOrPut(charKey) { ByteArrayOutputStream() }.write(value)
            } else {
                _events.tryEmit(Event.WriteReceived(device.address, characteristic.uuid.toString(), value))
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            val devBufs = preparedWriteBuffers.remove(device.address) ?: return
            if (execute) {
                for ((charUUID, buf) in devBufs) {
                    _events.tryEmit(Event.WriteReceived(device.address, charUUID, buf.toByteArray()))
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int,
            offset: Int, characteristic: BluetoothGattCharacteristic
        ) {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, characteristic.value ?: ByteArray(0))
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int,
            offset: Int, descriptor: BluetoothGattDescriptor
        ) {
            gattServer?.sendResponse(
                device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                descriptor.value ?: BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            )
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray
        ) {
            descriptor.value = value
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.i(TAG, "MTU changed to $mtu for ${device.address}")
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            Log.d(TAG, "Notification sent to ${device.address}, status=$status")
        }
    }

    // ── Public API ──────────────────────────────────────────────────────────

    fun startServer() {
        if (gattServer != null) return

        val service = BluetoothGattService(UUID.fromString(SERVICE_UUID), BluetoothGattService.SERVICE_TYPE_PRIMARY)

        for (charUUID in listOf(HANDSHAKE_CHAR_UUID, MESSAGE_CHAR_UUID, ACK_CHAR_UUID)) {
            val char = BluetoothGattCharacteristic(
                UUID.fromString(charUUID),
                BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ or
                    BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            val cccd = BluetoothGattDescriptor(
                UUID.fromString(CCCD_UUID),
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            char.addDescriptor(cccd)
            service.addCharacteristic(char)
            characteristics[charUUID.lowercase()] = char
        }

        gattServer = bluetoothManager.openGattServer(context, gattCallback)
        gattServer?.addService(service)
        Log.i(TAG, "GATT server started")
    }

    suspend fun startAdvertising(deviceName: String): Boolean {
        if (advertising) return true
        adapter.name = deviceName
        advertiser = adapter.bluetoothLeAdvertiser ?: run {
            Log.e(TAG, "BLE advertising not supported")
            return false
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(UUID.fromString(SERVICE_UUID)))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        return suspendCancellableCoroutine { cont ->
            advertiser?.startAdvertising(settings, data, scanResponse, object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    advertising = true
                    Log.i(TAG, "Advertising started as $deviceName")
                    if (cont.isActive) cont.resume(true)
                }
                override fun onStartFailure(errorCode: Int) {
                    Log.e(TAG, "Advertising failed: error $errorCode")
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }

    /**
     * Notify all connected centrals on the given characteristic.
     * API 33+ overload passes value directly without mutating characteristic.value.
     */
    fun sendNotification(charUUID: String, value: ByteArray) {
        val char = characteristics[charUUID.lowercase()] ?: return
        for (device in connectedDevices.toList()) {
            try {
                gattServer?.notifyCharacteristicChanged(device, char, false, value)
            } catch (e: Exception) {
                Log.w(TAG, "Notify failed for ${device.address}: ${e.message}")
            }
        }
    }

    fun stop() {
        try { advertiser?.stopAdvertising(null) } catch (_: Exception) {}
        advertising = false
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        advertiser = null
        connectedDevices.clear()
        characteristics.clear()
        preparedWriteBuffers.clear()
        Log.i(TAG, "GATT server stopped")
    }
}
