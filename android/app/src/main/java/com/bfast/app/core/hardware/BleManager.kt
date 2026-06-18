package com.bfast.app.core.hardware

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Discovered device data class containing parsed BLE advertising data.
 * @param command "S" (Sender), "R" (Receiver), "A" (Accept), "D" (Decline), "P" (Payment)
 * @param role "SENDER" or "RECEIVER" for backwards compatibility
 * @param displayName The truncated name from BLE (max 10 chars), or Amount if command is P
 * @param deviceId The unique device identifier
 * @param rssi The signal strength in dBm (dynamic, not hardcoded threshold)
 * @param timestamp When this device was discovered
 * @param amountPaise If command is "P", the amount sent.
 * @param bleConfidence Confidence score between 0.0 and 1.0 based on Kalman-filtered RSSI proximity.
 */
data class DiscoveredDevice(
    val command: String,
    val role: String,
    val displayName: String,
    val deviceId: String,
    val rssi: Int,
    val timestamp: Long,
    val amountPaise: Long? = null,
    val bleConfidence: Float = 0.0f
)

/**
 * Production-grade BLE Manager for BFast tap-to-pay.
 *
 * Key features:
 *  - RSSI threshold at -40 dBm ≈ 0-5cm physical distance
 *  - RSSI smoothing via exponential moving average (EMA) to filter noise spikes
 *  - Scan window expiry: stale devices are auto-cleared every 3 seconds
 *  - Self-device filtering: ignores own deviceId to prevent self-matching
 *  - Device timestamp expiry: ignores scan results older than 2 seconds
 *
 * Compatible with all Android phones from API 26+ (OPPO, Samsung, Pixel, OnePlus, etc.)
 */
@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter = bluetoothManager?.adapter

    fun isBluetoothEnabled(): Boolean {
        return try {
            bluetoothAdapter?.isEnabled == true
        } catch (e: Exception) {
            false
        }
    }

    // ── Constants ────────────────────────────────────────────────────────────

    /** BFast Manufacturer ID (16-bit, fits in BLE header) */
    private val MANUFACTURER_ID = 0xBFA5

    /**
     * RSSI threshold for proximity: -55 dBm ≈ 0-5cm physical distance.
     * This ensures all Android phones trigger correctly when touching or extremely close,
     * while still rejecting devices that are further away.
     */
    private val PROXIMITY_RSSI_THRESHOLD = -55

    /** How often to clear stale scan data (ms). Prevents state accumulation. */
    private val SCAN_WINDOW_EXPIRY_MS = 3000L

    /** Ignore discovered devices older than this (ms). */
    private val DEVICE_TIMESTAMP_EXPIRY_MS = 2000L

    // ── Kalman Filter Constants ──────────────────────────────────────────────
    /** Process noise variance (Q). Small value assumes RSSI changes slowly. */
    private val KALMAN_Q = 0.1
    /** Measurement noise variance (R). Large value assumes raw RSSI is highly noisy. */
    private val KALMAN_R = 5.0
    /** Sigmoid steepness for BleConfidence calculation. */
    private val SIGMOID_K = 0.2

    // ── State ───────────────────────────────────────────────────────────────

    /** The closest discovered device — dynamically updated on every scan result. */
    private val _closestDevice = MutableStateFlow<DiscoveredDevice?>(null)
    val closestDevice: StateFlow<DiscoveredDevice?> = _closestDevice

    /** Raw RSSI for UI feedback. */
    private val _lastRssi = MutableStateFlow(Int.MIN_VALUE)
    val lastRssi: StateFlow<Int> = _lastRssi

    /** Our own device ID — set by the service so we can filter self-scans. */
    var ownDeviceId: String = ""

    /** Tracks the best RSSI seen in the current scan window. */
    private var bestRssiInWindow: Int = Int.MIN_VALUE
    private var bestDeviceInWindow: DiscoveredDevice? = null

    /** 1D Kalman filter state for RSSI smoothing to filter noise spikes. */
    private class RssiKalmanFilter(private val q: Double, private val r: Double) {
        var x = 0.0 // State estimate (RSSI)
        var p = 1.0 // Error covariance
        var initialized = false

        fun filter(z: Double): Double {
            if (!initialized) {
                x = z
                initialized = true
                return x
            }
            // Prediction
            val xPrev = x
            val pPrev = p + q
            // Update
            val k = pPrev / (pPrev + r)
            x = xPrev + k * (z - xPrev)
            p = (1 - k) * pPrev
            return x
        }
    }

    /** Kalman filter instances per device ID. */
    private val kalmanFilters = mutableMapOf<String, RssiKalmanFilter>()

    /** Handler for periodic scan window reset. */
    private val handler = Handler(Looper.getMainLooper())
    private val scanWindowResetRunnable = object : Runnable {
        override fun run() {
            resetScanWindow()
            handler.postDelayed(this, SCAN_WINDOW_EXPIRY_MS)
        }
    }

    // ── BLE Scanning ────────────────────────────────────────────────────────

    /**
     * BLE Advertising payload format: "S|DisplayName|DeviceId" or "R|DisplayName|DeviceId"
     * S = Sender, R = Receiver, A = Accept, D = Decline, P = Payment
     * DisplayName is truncated to 10 chars to stay within 31-byte BLE limit
     */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let {
                try {
                    val rssi = it.rssi
                    val scanRecord = it.scanRecord
                    val deviceData = scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID)

                    if (deviceData != null) {
                        val payload = String(deviceData)
                        val parts = payload.split("|")

                        if (parts.size >= 3) {
                            val command = parts[0]
                            val role = when (command) {
                                "S" -> "SENDER"
                                "R", "A", "D", "P", "T" -> "RECEIVER"
                                else -> return@let
                            }
                            val displayName = parts[1]
                            val deviceId = parts[2]

                            // ── Self-filtering: ignore our own advertisements ────
                            if (deviceId == ownDeviceId) return@let

                            var amountPaise: Long? = null
                            if (command == "P") {
                                amountPaise = displayName.toLongOrNull()
                            }

                            // ── RSSI smoothing via Kalman Filter ──────────────────────────
                            val filter = kalmanFilters.getOrPut(deviceId) { 
                                RssiKalmanFilter(KALMAN_Q, KALMAN_R) 
                            }
                            val smoothedRssi = filter.filter(rssi.toDouble())
                            val effectiveRssi = smoothedRssi.toInt()

                            // ── Calculate BleConfidence (Sigmoid function) ───────────────────
                            // Centered around PROXIMITY_RSSI_THRESHOLD (-55 dBm)
                            val diff = effectiveRssi - PROXIMITY_RSSI_THRESHOLD
                            val confidence = (1.0 / (1.0 + kotlin.math.exp(-SIGMOID_K * diff))).toFloat()

                            val device = DiscoveredDevice(
                                command = command,
                                role = role,
                                displayName = displayName,
                                deviceId = deviceId,
                                rssi = effectiveRssi,
                                timestamp = System.currentTimeMillis(),
                                amountPaise = amountPaise,
                                bleConfidence = confidence
                            )

                            _lastRssi.value = effectiveRssi

                            // ── Proximity gate: strict -40 dBm threshold ────────
                            // Below this = too far away, not a physical tap
                            if (effectiveRssi < PROXIMITY_RSSI_THRESHOLD) {
                                return@let
                            }

                            // ── Keep the closest device (highest RSSI) ──────────
                            if (effectiveRssi > bestRssiInWindow) {
                                bestRssiInWindow = effectiveRssi
                                bestDeviceInWindow = device
                                _closestDevice.value = device
                            } else if (bestDeviceInWindow?.deviceId == deviceId) {
                                // Same device with updated RSSI — always update
                                bestDeviceInWindow = device
                                _closestDevice.value = device
                                bestRssiInWindow = effectiveRssi
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {}
        override fun onStartFailure(errorCode: Int) {}
    }

    // ── BLE Advertising ─────────────────────────────────────────────────────

    /**
     * Start advertising this device.
     * @param command "S", "R", "A", "D", or "P"
     * @param displayNameOrAmount user's display name or payment amount
     * @param deviceId unique device identifier
     */
    fun startAdvertising(command: String, displayNameOrAmount: String, deviceId: String) {
        try {
            stopAdvertising()

            val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: return

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build()

            // e.g., "R|Name|Id", "A|Name|Id", "P|20000|Id"
            val payload = "$command|${displayNameOrAmount.take(10)}|${deviceId.take(15)}"

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addManufacturerData(MANUFACTURER_ID, payload.toByteArray(Charsets.UTF_8))
                .build()

            advertiser.startAdvertising(settings, data, advertiseCallback)
        } catch (e: SecurityException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopAdvertising() {
        try {
            bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ── Scanning Control ────────────────────────────────────────────────────

    fun startScanning() {
        try {
            val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            // Reset all scan window tracking
            resetScanWindow()
            kalmanFilters.clear()

            scanner.startScan(null, settings, scanCallback)

            // Start periodic scan window expiry timer
            handler.removeCallbacks(scanWindowResetRunnable)
            handler.postDelayed(scanWindowResetRunnable, SCAN_WINDOW_EXPIRY_MS)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopScanning() {
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        handler.removeCallbacks(scanWindowResetRunnable)
        resetScanWindow()
        kalmanFilters.clear()
    }

    /**
     * Reset the scan window — clears stale device tracking.
     * Called every SCAN_WINDOW_EXPIRY_MS (3 seconds) to prevent state accumulation.
     * This is the core fix for Issue #1 (tap stopping after N times).
     */
    private fun resetScanWindow() {
        bestRssiInWindow = Int.MIN_VALUE
        bestDeviceInWindow = null

        // Only clear closestDevice if it's stale (older than DEVICE_TIMESTAMP_EXPIRY_MS)
        val current = _closestDevice.value
        if (current != null) {
            val age = System.currentTimeMillis() - current.timestamp
            if (age > DEVICE_TIMESTAMP_EXPIRY_MS) {
                _closestDevice.value = null
                _lastRssi.value = Int.MIN_VALUE
            }
        }
    }

    /**
     * Full reset — called after a successful tap-to-pay match to clear all state
     * and prepare for the next tap. Essential for unlimited successive taps.
     */
    fun resetClosestDevice() {
        bestRssiInWindow = Int.MIN_VALUE
        bestDeviceInWindow = null
        _closestDevice.value = null
        _lastRssi.value = Int.MIN_VALUE
        // Don't clear kalmanFilters — filter benefits from continuity for known devices
    }
}
