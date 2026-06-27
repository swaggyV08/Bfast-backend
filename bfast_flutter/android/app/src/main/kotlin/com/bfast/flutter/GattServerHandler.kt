package com.bfast.flutter

import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.UUID

/**
 * Hosts a BLE GATT server on the RECEIVER device.
 *
 * Service: 0000FFF0 (BFast)
 * HandshakeChar (FFF2) — WRITE:  sender writes all messages to receiver
 * ResponseChar  (FFF3) — READ:   receiver stores responses; sender polls instead of waiting for NOTIFY
 *
 * Replacing NOTIFY with READ polling eliminates CCCD/Samsung BLE stack issues entirely.
 *
 * Flutter ↔ Native contract:
 *  MethodChannel "com.bfast.app/gatt_server":
 *    startGattServer()                 → starts the GATT server
 *    stopGattServer()                  → stops the GATT server
 *    setSessionActive(active: Boolean) → lock/unlock receiver to new connections
 *    setResponse(bytes: List<Int>)     → store bytes in ResponseChar (FFF3) for sender to READ
 *
 *  EventChannel "com.bfast.app/gatt_server/events":
 *    { type: "connected",    deviceAddress: String }
 *    { type: "disconnected", deviceAddress: String }
 *    { type: "data", charType: "handshake", data: List<Int> }
 *    { type: "busy_rejected", deviceAddress: String }
 */
@Suppress("MissingPermission")
class GattServerHandler(private val context: Context) :
    MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler {

    companion object {
        private const val TAG = "GattServerHandler"

        val SERVICE_UUID   = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
        val HANDSHAKE_UUID = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB")
        val RESPONSE_UUID  = UUID.fromString("0000FFF3-0000-1000-8000-00805F9B34FB")
    }

    private var gattServer:      BluetoothGattServer?  = null
    private var connectedDevice: BluetoothDevice?      = null
    private var sessionActive:   Boolean               = false
    private var eventSink:       EventChannel.EventSink? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── GATT server callback ─────────────────────────────────────────────────

    private val serverCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (sessionActive || connectedDevice != null) {
                        Log.i(TAG, "Busy: rejecting ${device.address}")
                        gattServer?.cancelConnection(device)
                        emit(mapOf(
                            "type"          to "busy_rejected",
                            "deviceAddress" to device.address,
                        ))
                    } else {
                        Log.i(TAG, "Sender connected: ${device.address}")
                        connectedDevice = device
                        // Clear response char so sender sees a clean slate
                        gattServer?.getService(SERVICE_UUID)
                            ?.getCharacteristic(RESPONSE_UUID)
                            ?.value = ByteArray(0)
                        emit(mapOf("type" to "connected", "deviceAddress" to device.address))
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Sender disconnected: ${device.address}")
                    if (connectedDevice?.address == device.address) {
                        connectedDevice = null
                        sessionActive   = false
                        emit(mapOf("type" to "disconnected", "deviceAddress" to device.address))
                    }
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            val bytes = value ?: return
            val charType = when (characteristic.uuid) {
                HANDSHAKE_UUID -> "handshake"
                else           -> "unknown"
            }
            Log.i(TAG, "Write from ${device.address}: charType=$charType len=${bytes.size} " +
                    "msgType=0x${if (bytes.isNotEmpty()) bytes[0].toInt().and(0xFF).toString(16) else "?"}")
            emit(mapOf(
                "type"     to "data",
                "charType" to charType,
                "data"     to bytes.map { it.toInt() and 0xFF },
            ))
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: ByteArray(0)
            val resp  = if (offset < value.size)
                value.copyOfRange(offset, value.size)
            else
                ByteArray(0)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, resp)
            if (value.isNotEmpty()) {
                Log.i(TAG, "READ ResponseChar: ${value.size}B seq=${value[0].toInt() and 0xFF}")
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.i(TAG, "MTU changed to $mtu for ${device.address}")
        }
    }

    // ── MethodChannel ────────────────────────────────────────────────────────

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "startGattServer"  -> { startGattServer(); result.success(null) }
            "stopGattServer"   -> { stopGattServer();  result.success(null) }
            "setSessionActive" -> {
                sessionActive = call.argument<Boolean>("active") ?: false
                Log.i(TAG, "sessionActive = $sessionActive")
                result.success(null)
            }
            "setResponse" -> {
                val ints = call.argument<List<Int>>("bytes") ?: emptyList()
                val char = gattServer?.getService(SERVICE_UUID)?.getCharacteristic(RESPONSE_UUID)
                if (char != null) {
                    char.value = ByteArray(ints.size) { i -> ints[i].toByte() }
                    val seq = if (ints.isNotEmpty()) ints[0] else -1
                    val type = if (ints.size >= 2) ints[1] else -1
                    Log.i(TAG, "setResponse: ${ints.size}B seq=$seq msgType=0x${type.toString(16)}")
                } else {
                    Log.e(TAG, "setResponse: RESPONSE_UUID char not found — server may not be started")
                }
                result.success(null)
            }
            // Legacy no-ops kept so old Flutter code doesn't crash if still called
            "notifyMotion", "notifyHandshake" -> result.success(null)
            else -> result.notImplemented()
        }
    }

    // ── EventChannel ─────────────────────────────────────────────────────────

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
        Log.i(TAG, "EventChannel: onListen — eventSink active")
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
        Log.i(TAG, "EventChannel: onCancel — eventSink cleared")
    }

    private fun emit(data: Map<String, Any>) {
        mainHandler.post { eventSink?.success(data) }
    }

    // ── GATT server lifecycle ─────────────────────────────────────────────────

    fun startGattServer() {
        if (gattServer != null) {
            Log.i(TAG, "startGattServer: already running")
            return
        }
        val btMgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: run { Log.e(TAG, "startGattServer: BluetoothManager unavailable"); return }
        val server = btMgr.openGattServer(context, serverCallback)
            ?: run { Log.e(TAG, "startGattServer: openGattServer returned null"); return }
        gattServer = server

        val svc = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // HandshakeChar (FFF2): sender writes all messages here (WRITE + WRITE_NO_RESPONSE)
        val hsChar = BluetoothGattCharacteristic(
            HANDSHAKE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        svc.addCharacteristic(hsChar)

        // ResponseChar (FFF3): receiver stores responses here; sender READs instead of waiting for NOTIFY.
        // This avoids all CCCD/Samsung BLE notification reliability issues.
        val respChar = BluetoothGattCharacteristic(
            RESPONSE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        respChar.value = ByteArray(0)
        svc.addCharacteristic(respChar)

        server.addService(svc)
        Log.i(TAG, "GATT server started: FFF2=write(sender→rcvr), FFF3=read(rcvr→sender)")
    }

    fun stopGattServer() {
        gattServer?.close()
        gattServer      = null
        connectedDevice = null
        sessionActive   = false
        Log.i(TAG, "GATT server stopped")
    }

    fun cleanup() = stopGattServer()
}
