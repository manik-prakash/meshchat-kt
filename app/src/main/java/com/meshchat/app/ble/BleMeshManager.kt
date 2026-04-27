package com.meshchat.app.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.meshchat.app.ble.BleConstants.ACK_CHAR_UUID
import com.meshchat.app.ble.BleConstants.CCCD_UUID
import com.meshchat.app.ble.BleConstants.HANDSHAKE_CHAR_UUID
import com.meshchat.app.ble.BleConstants.MESSAGE_CHAR_UUID
import com.meshchat.app.ble.BleConstants.PEER_NAME_PREFIX
import com.meshchat.app.ble.BleConstants.SCAN_DURATION_MS
import com.meshchat.app.ble.BleConstants.SERVICE_UUID
import com.meshchat.app.data.repository.ConversationRepository
import com.meshchat.app.data.repository.IdentityRepository
import com.meshchat.app.domain.BlePayload
import com.meshchat.app.domain.MessageStatus
import com.meshchat.app.domain.Peer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

enum class BleState { IDLE, SCANNING, CONNECTING, CONNECTED, ERROR }

/**
 * Coordinates BLE central and peripheral roles.
 *
 * Peripheral: GattServerManager keeps a GATT server running and advertises.
 * Central:    This class scans for MC_ devices and connects to selected peers.
 */
@SuppressLint("MissingPermission")
class BleMeshManager(
    private val context: Context,
    private val identityRepo: IdentityRepository,
    private val conversationRepo: ConversationRepository
) {
    private val TAG = "BleMesh"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val gattServer = GattServerManager(context)

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = bluetoothManager.adapter

    private val _state = MutableStateFlow(BleState.IDLE)
    val state: StateFlow<BleState> = _state.asStateFlow()

    sealed class BleEvent {
        data class PeerDiscovered(val peer: Peer) : BleEvent()
        data class PayloadReceived(val payload: BlePayload, val fromAddress: String) : BleEvent()
    }

    private val _events = MutableSharedFlow<BleEvent>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<BleEvent> = _events.asSharedFlow()

    // Central GATT connection state
    private var connectedGatt: BluetoothGatt? = null
    private var scanJob: Job? = null
    private var peripheralStarted = false

    // Per-device dedup: name -> last-seen timestamp
    private val discoveredNames = mutableMapOf<String, Long>()

    // Pending coroutine continuations for sequential GATT operations
    private var pendingConnectionCont: Continuation<BluetoothGatt>? = null
    private var pendingMtuCont: Continuation<Int>? = null
    private var pendingServicesCont: Continuation<Boolean>? = null
    private var pendingDescWriteCont: Continuation<Boolean>? = null
    private var pendingCharWriteCont: Continuation<Boolean>? = null
    private val gattWriteMutex = kotlinx.coroutines.sync.Mutex()

    // ── GATT Client Callback ────────────────────────────────────────────────

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Central connected to ${gatt.device.address}")
                    pendingConnectionCont?.also {
                        pendingConnectionCont = null
                        it.resume(gatt)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Central disconnected from ${gatt.device.address}")
                    pendingConnectionCont?.also {
                        pendingConnectionCont = null
                        it.resumeWithException(Exception("Disconnected during connect"))
                    }
                    scope.launch { handleClientDisconnect() }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU changed to $mtu")
            pendingMtuCont?.also { pendingMtuCont = null; it.resume(mtu) }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.i(TAG, "Services discovered: status=$status")
            pendingServicesCont?.also {
                pendingServicesCont = null
                it.resume(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            pendingDescWriteCont?.also {
                pendingDescWriteCont = null
                it.resume(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pendingCharWriteCont?.also {
                pendingCharWriteCont = null
                it.resume(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        // API 33+ — value provided directly
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val sourceKey = "central_${gatt.device.address}_${characteristic.uuid}"
            try {
                val payload = MeshProtocolCodec.decode(value, sourceKey) ?: return
                _events.tryEmit(BleEvent.PayloadReceived(payload, gatt.device.address))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode notification: ${e.message}")
            }
        }
    }

    // ── Init ────────────────────────────────────────────────────────────────

    init {
        scope.launch { observeServerEvents() }
    }

    private suspend fun observeServerEvents() {
        gattServer.events.collect { event ->
            when (event) {
                is GattServerManager.Event.DeviceConnected -> {
                    Log.i(TAG, "Peripheral: central connected ${event.device.address}")
                    updateState()
                }
                is GattServerManager.Event.DeviceDisconnected -> {
                    Log.i(TAG, "Peripheral: central disconnected ${event.device.address}")
                    updateState()
                }
                is GattServerManager.Event.WriteReceived -> {
                    val sourceKey = "periph_${event.deviceAddress}_${event.charUUID}"
                    try {
                        val payload = MeshProtocolCodec.decode(event.value, sourceKey) ?: return@collect
                        _events.tryEmit(BleEvent.PayloadReceived(payload, event.deviceAddress))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to decode server write: ${e.message}")
                    }
                }
            }
        }
    }

    // ── Peripheral ──────────────────────────────────────────────────────────

    suspend fun startPeripheral() {
        if (peripheralStarted) return
        val identity = identityRepo.ensureIdentity()
        val advName = "$PEER_NAME_PREFIX${identity.displayName.take(BleConstants.ADV_NAME_MAX_LEN)}"

        gattServer.startServer()
        val ok = gattServer.startAdvertising(advName)
        peripheralStarted = ok
        Log.i(TAG, if (ok) "Peripheral started as $advName" else "Peripheral advertising FAILED")
    }

    // ── Scan ────────────────────────────────────────────────────────────────

    private var activeScanCallback: ScanCallback? = null

    suspend fun startScan(durationMs: Long = SCAN_DURATION_MS) {
        if (_state.value == BleState.SCANNING) return

        startPeripheral()

        val scanner = adapter.bluetoothLeScanner ?: run {
            _state.value = BleState.ERROR
            return
        }

        discoveredNames.clear()
        _state.value = BleState.SCANNING

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                if (!name.startsWith(PEER_NAME_PREFIX)) return
                handleDiscoveredDevice(result.device, name, result.rssi)
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                _state.value = BleState.ERROR
            }
        }
        activeScanCallback = callback

        scanner.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            callback
        )

        scanJob = scope.launch {
            delay(durationMs)
            stopScan()
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        activeScanCallback?.let {
            try { adapter.bluetoothLeScanner?.stopScan(it) } catch (_: Exception) {}
            activeScanCallback = null
        }
        if (_state.value == BleState.SCANNING) _state.value = BleState.IDLE
    }

    private fun handleDiscoveredDevice(device: BluetoothDevice, name: String, rssi: Int) {
        val now = System.currentTimeMillis()
        val last = discoveredNames[name]
        if (last != null && now - last < 2_000) return
        discoveredNames[name] = now

        val peer = Peer(
            deviceId = device.address,
            displayName = name,
            lastSeen = now,
            rssi = rssi,
            bleId = device.address
        )
        scope.launch { conversationRepo.upsertPeer(peer) }
        _events.tryEmit(BleEvent.PeerDiscovered(peer))
    }

    // ── Connect ─────────────────────────────────────────────────────────────

    suspend fun connectToPeer(bleId: String): Boolean {
        stopScan()
        _state.value = BleState.CONNECTING

        val device = try { adapter.getRemoteDevice(bleId) } catch (e: Exception) {
            Log.e(TAG, "Invalid BLE address: $bleId")
            _state.value = BleState.ERROR
            return false
        }

        return try {
            val gatt = withTimeout(10_000) {
                suspendCoroutine { cont ->
                    pendingConnectionCont = cont
                    device.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
                }
            }

            withTimeout(5_000) {
                suspendCoroutine { cont ->
                    pendingMtuCont = cont
                    if (!gatt.requestMtu(512)) cont.resume(23)
                }
            }

            val discovered = withTimeout(10_000) {
                suspendCoroutine { cont ->
                    pendingServicesCont = cont
                    if (!gatt.discoverServices()) cont.resume(false)
                }
            }
            if (!discovered) throw Exception("Service discovery failed")

            connectedGatt = gatt
            updateState()

            // Subscribe to all notifications before writing our handshake so we
            // don't miss Device B's handshake response notification.
            enableNotifications(gatt, HANDSHAKE_CHAR_UUID)
            enableNotifications(gatt, MESSAGE_CHAR_UUID)
            enableNotifications(gatt, ACK_CHAR_UUID)
            val identity = identityRepo.ensureIdentity()
            writeFragmentsCentral(
                gatt, HANDSHAKE_CHAR_UUID,
                BlePayload.Handshake(identity.deviceId, identity.displayName)
            )

            true
        } catch (e: Exception) {
            Log.e(TAG, "Connect failed: ${e.message}")
            connectedGatt?.close()
            connectedGatt = null
            _state.value = BleState.ERROR
            false
        }
    }

    private suspend fun enableNotifications(gatt: BluetoothGatt, charUUID: String) {
        val service = gatt.getService(UUID.fromString(SERVICE_UUID)) ?: return
        val char    = service.getCharacteristic(UUID.fromString(charUUID)) ?: return
        gatt.setCharacteristicNotification(char, true)
        val descriptor = char.getDescriptor(UUID.fromString(CCCD_UUID)) ?: return

        withTimeout(5_000) {
            suspendCoroutine { cont ->
                pendingDescWriteCont = cont
                val status = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (status != BluetoothStatusCodes.SUCCESS) cont.resume(false)
            }
        }
    }

    suspend fun disconnect() {
        connectedGatt?.disconnect()
        connectedGatt?.close()
        connectedGatt = null
        updateState()
    }

    private fun handleClientDisconnect() {
        connectedGatt?.close()
        connectedGatt = null
        updateState()
    }

    // ── Send ────────────────────────────────────────────────────────────────

    suspend fun sendMessage(payload: BlePayload.Message) {
        val gatt = connectedGatt
        if (gatt != null) {
            writeFragmentsCentral(gatt, MESSAGE_CHAR_UUID, payload)
            return
        }
        if (gattServer.connectedDeviceCount > 0) {
            for (fragment in MeshProtocolCodec.encode(payload)) {
                gattServer.sendNotification(MESSAGE_CHAR_UUID, fragment)
            }
            return
        }
        throw IllegalStateException("Not connected to any peer")
    }

    suspend fun sendAck(messageId: String) {
        val payload = BlePayload.Ack(messageId)
        try {
            val gatt = connectedGatt
            if (gatt != null) {
                writeFragmentsCentral(gatt, ACK_CHAR_UUID, payload)
            } else if (gattServer.connectedDeviceCount > 0) {
                for (fragment in MeshProtocolCodec.encode(payload)) {
                    gattServer.sendNotification(ACK_CHAR_UUID, fragment)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendAck failed: ${e.message}")
        }
    }

    private suspend fun writeFragmentsCentral(gatt: BluetoothGatt, charUUID: String, payload: BlePayload) {
        val service = gatt.getService(UUID.fromString(SERVICE_UUID)) ?: throw Exception("Service not found")
        val char    = service.getCharacteristic(UUID.fromString(charUUID)) ?: throw Exception("Char $charUUID not found")
        val fragments = MeshProtocolCodec.encode(payload)

        gattWriteMutex.lock()
        try {
            for (fragment in fragments) {
                withTimeout(5_000) {
                    suspendCoroutine { cont ->
                        pendingCharWriteCont = cont
                        val status = gatt.writeCharacteristic(
                            char, fragment, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        )
                        if (status != BluetoothStatusCodes.SUCCESS) cont.resume(false)
                    }
                }
            }
        } finally {
            gattWriteMutex.unlock()
        }
    }

    // ── State helpers ────────────────────────────────────────────────────────

    private fun updateState() {
        _state.value = when {
            connectedGatt != null || gattServer.connectedDeviceCount > 0 -> BleState.CONNECTED
            _state.value == BleState.SCANNING -> BleState.SCANNING
            else -> BleState.IDLE
        }
    }

    val isConnected: Boolean
        get() = connectedGatt != null || gattServer.connectedDeviceCount > 0

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun destroy() {
        stopScan()
        connectedGatt?.disconnect()
        connectedGatt?.close()
        connectedGatt = null
        gattServer.stop()
        scope.cancel()
    }
}
