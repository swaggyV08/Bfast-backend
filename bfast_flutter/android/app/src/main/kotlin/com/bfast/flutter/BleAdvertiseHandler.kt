package com.bfast.flutter

import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Handles BLE peripheral advertising for BOTH sender and receiver roles.
 *
 * Packet budget (31-byte primary advertisement limit):
 *   Flags           3 bytes
 *   Manufacturer   21 bytes  (1 len + 1 type + 2 company + 1 role + 16 UUID)
 *   ─────────────  24 bytes  ← within limit
 *
 * Manufacturer data format: [role:1][uuid:16]
 *   role 0x52 = Receiver (on home screen, wants to receive payment)
 *   role 0x53 = Sender   (on Send Money screen, wants to send payment)
 *
 * The opposite phone's scan result will decode the role + device UUID
 * so it knows (a) who it found and (b) their device ID for backend calls.
 */
class BleAdvertiseHandler(private val context: Context) : MethodChannel.MethodCallHandler {

    companion object {
        private const val TAG          = "BleAdvertiseHandler"
        private const val BFAST_MFG_ID = 0x0BFA
        private const val ROLE_RECEIVER: Byte = 0x52 // 'R'
        private const val ROLE_SENDER:   Byte = 0x53 // 'S'
    }

    private var advertiser: BluetoothLeAdvertiser? = null
    private var callback:   AdvertiseCallback?     = null

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "startAdvertising" -> {
                val deviceId    = call.argument<String>("deviceId")    ?: ""
                val displayName = call.argument<String>("displayName") ?: ""
                val isSender    = call.argument<Boolean>("isSender")   ?: false
                startAdvertising(deviceId, displayName, isSender)
                result.success(null)
            }
            "stopAdvertising" -> {
                stopAdvertising()
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    fun startAdvertising(deviceId: String, displayName: String, isSender: Boolean = false) {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter   = btManager?.adapter ?: run {
            Log.w(TAG, "Bluetooth adapter unavailable"); return
        }
        if (!adapter.isEnabled) { Log.w(TAG, "Bluetooth disabled"); return }

        advertiser = adapter.bluetoothLeAdvertiser ?: run {
            Log.w(TAG, "Device does not support BLE advertising"); return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // Build manufacturer payload: [role byte][uuid 16 bytes]
        val roleFlag: Byte = if (isSender) ROLE_SENDER else ROLE_RECEIVER
        val uuidBytes = try {
            val uuid = UUID.fromString(deviceId)
            val bb   = ByteBuffer.allocate(16)
            bb.putLong(uuid.mostSignificantBits)
            bb.putLong(uuid.leastSignificantBits)
            bb.array()
        } catch (_: Exception) { ByteArray(0) }

        val mfgPayload = if (uuidBytes.isNotEmpty()) {
            byteArrayOf(roleFlag) + uuidBytes  // 17 bytes total
        } else {
            byteArrayOf(roleFlag, 0x42, 0x46)  // "BF" fallback
        }

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(BFAST_MFG_ID, mfgPayload)
            .build()

        // Scan response carries human-readable name
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        val roleTag  = if (isSender) "S" else "R"
        val safeName = displayName.replace("_", "").take(12).ifEmpty { "User" }
        try {
            adapter.setName("BFAST_$safeName")
        } catch (e: Exception) {
            Log.w(TAG, "setName failed: ${e.message}")
        }

        callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "BLE advertising started [$roleTag]: BFAST_$safeName")
            }
            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "BLE advertising failed [$roleTag]: errorCode=$errorCode")
            }
        }

        advertiser?.startAdvertising(settings, data, scanResponse, callback)
    }

    fun stopAdvertising() {
        callback?.let { advertiser?.stopAdvertising(it) }
        callback  = null
        advertiser = null
    }
}
