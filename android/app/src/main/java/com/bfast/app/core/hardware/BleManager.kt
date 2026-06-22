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
import android.os.Build
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
    /** Ephemeral session ID (Layer 10). Changes each advertising start. */
    val sessionId: String = "",
    val rssi: Int,
    val timestamp: Long,
    val amountPaise: Long? = null,
    val bleConfidence: Float = 0.0f,
    /** Multi-signal proximity score from BleProximityEngine (0–100). */
    val proximityScore: Float = 0.0f
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
     * RSSI threshold for proximity sigmoid confidence calculation.
     * Widened to -65 dBm to allow devices at scan range (15–30cm) to register
     * a non-zero bleConfidence. Payment security is NOT affected — that's gated
     * by ConfidenceEngine (score ≥ 50 + physical tap + dual correlation).
     */
    private val PROXIMITY_RSSI_THRESHOLD = -65

    /** How often to clear stale scan data (ms). Prevents state accumulation. */
    private val SCAN_WINDOW_EXPIRY_MS = 3000L

    /** Ignore discovered devices older than this (ms).
     *  Was 2000ms — increased to 6000ms so closestDevice survives normal BLE scan gaps
     *  (which can be 1–3s even at LOW_LATENCY) without triggering a false IDLE reset. */
    private val DEVICE_TIMESTAMP_EXPIRY_MS = 6000L

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

    /** All receivers currently in detection range (proxScore ≥ 25), sorted by RSSI descending.
     *  Used to show a picker when multiple receivers are nearby. */
    private val nearbyReceiversMap = mutableMapOf<String, DiscoveredDevice>()
    private val _nearbyReceivers = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val nearbyReceivers: StateFlow<List<DiscoveredDevice>> = _nearbyReceivers

    /** Raw RSSI for UI feedback. */
    private val _lastRssi = MutableStateFlow(Int.MIN_VALUE)
    val lastRssi: StateFlow<Int> = _lastRssi

    /** Remote tap events received via BLE "B" command (for DualDeviceCorrelator). */
    private val _remoteTapEvent = MutableStateFlow<DualDeviceCorrelator.RemoteTapData?>(null)
    val remoteTapEvent: StateFlow<DualDeviceCorrelator.RemoteTapData?> = _remoteTapEvent

    /** Our own device ID — set by the service so we can filter self-scans. */
    var ownDeviceId: String = ""

    /** Multi-signal proximity provider (CS or RSSI) — produces score 0–100. */
    val proximityEngine: ProximityProvider by lazy {
        if (Build.VERSION.SDK_INT >= 36) { // Android 16+
            BleCsRangingProvider(context)
        } else {
            BleProximityEngine()
        }
    }

    /** Dual device correlator reference (for decoding "B" payloads). */
    private val correlatorDecoder = DualDeviceCorrelator()

    /** Tracks the best RSSI seen in the current scan window. */
    private var bestRssiInWindow: Int = Int.MIN_VALUE
    private var bestDeviceInWindow: DiscoveredDevice? = null

    /** Ephemeral session ID for Layer 10 session correlation. */
    private var currentSessionId: String = generateSessionId()

    private fun generateSessionId(): String {
        return java.util.UUID.randomUUID().toString().take(4).uppercase()
    }

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

                            // ── Handle "B" (Bump/Tap broadcast) command for correlation ───
                            if (command == "B" && parts.size >= 5) {
                                val remoteTap = correlatorDecoder.decodeTapFromBle(payload)
                                if (remoteTap != null && remoteTap.deviceId != ownDeviceId) {
                                    _remoteTapEvent.value = remoteTap
                                }
                                return@let  // "B" is not a device presence command
                            }

                            val role = when (command) {
                                "S" -> "SENDER"
                                "R", "A", "D", "P" -> "RECEIVER"
                                "T" -> "RECEIVER" // Tap-confirmed broadcast from receiver
                                else -> return@let
                            }
                            val displayName = parts[1]
                            val deviceId = parts[2]
                            val sessionId = if (parts.size >= 4) parts[3] else ""

                            // ── Self-filtering: ignore our own advertisements ────
                            // Primary check: ownDeviceId (set from DataStore)
                            // Fallback: SensorForegroundService.myDeviceId (available immediately)
                            if (deviceId == ownDeviceId) return@let
                            if (deviceId == SensorForegroundService.myDeviceId) return@let

                            // Safety net: reject if displayName matches our own name
                            // This catches stale cached BLE ads where deviceId wasn't set yet
                            val myName = SensorForegroundService.myDisplayName
                            if (displayName == myName && deviceId.isNotEmpty()) {
                                // Could be a legitimate user with same name — verify via deviceId
                                // But if we're in sender mode and seeing a "SENDER" ad with our name,
                                // it's definitely our own stale advertisement
                                if (role == "SENDER") return@let
                            }

                            // When in sender mode, we only care about RECEIVER advertisements.
                            // Ignore other senders' advertisements entirely.
                            val isSender = SensorForegroundService.isSenderMode.value
                            if (isSender && role == "SENDER") return@let

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

                            // ── Feed raw RSSI to BleProximityEngine for multi-signal scoring ──
                            val now = System.currentTimeMillis()
                            proximityEngine.feedReading(deviceId, effectiveRssi, now)
                            val proxScore = proximityEngine.proximityScore

                            // ── Calculate BleConfidence (Sigmoid function) ───────────────────
                            val diff = effectiveRssi - PROXIMITY_RSSI_THRESHOLD
                            val confidence = (1.0 / (1.0 + kotlin.math.exp(-SIGMOID_K * diff))).toFloat()

                            val device = DiscoveredDevice(
                                command = command,
                                role = role,
                                displayName = displayName,
                                deviceId = deviceId,
                                sessionId = sessionId,
                                rssi = effectiveRssi,
                                timestamp = now,
                                amountPaise = amountPaise,
                                bleConfidence = confidence,
                                proximityScore = proxScore
                            )

                            _lastRssi.value = effectiveRssi

                            // ── Multi-signal proximity gate ───────────────────────
                            // "P" and "T" always pass through (payment + tap-confirmed
                            // events must never be silently dropped by proximity filter).
                            if (proxScore < 20f && command != "P" && command != "T") {
                                nearbyReceiversMap.remove(deviceId)
                                _nearbyReceivers.value = nearbyReceiversMap.values
                                    .sortedByDescending { it.rssi }
                                return@let
                            }

                            // ── Track all receivers in detection range (≥ 25) ────
                            if (role == "RECEIVER" && proxScore >= 25f) {
                                nearbyReceiversMap[deviceId] = device
                                _nearbyReceivers.value = nearbyReceiversMap.values
                                    .sortedByDescending { it.rssi }
                            } else if (role == "RECEIVER") {
                                nearbyReceiversMap.remove(deviceId)
                                _nearbyReceivers.value = nearbyReceiversMap.values
                                    .sortedByDescending { it.rssi }
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

            // Payload format: "R|Name(8)|Id(12)|Sess(4)" — fits within 31-byte BLE limit
            val payload = "$command|${displayNameOrAmount.take(8)}|${deviceId.take(12)}|$currentSessionId"

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

        val now = System.currentTimeMillis()
        // Evict stale entries from nearbyReceiversMap
        nearbyReceiversMap.entries.removeAll { now - it.value.timestamp > DEVICE_TIMESTAMP_EXPIRY_MS }
        _nearbyReceivers.value = nearbyReceiversMap.values.sortedByDescending { it.rssi }

        // Only clear closestDevice if it's stale (older than DEVICE_TIMESTAMP_EXPIRY_MS)
        val current = _closestDevice.value
        if (current != null) {
            val age = now - current.timestamp
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
        _remoteTapEvent.value = null
        nearbyReceiversMap.clear()
        _nearbyReceivers.value = emptyList()
        proximityEngine.reset()
        // Don't clear kalmanFilters — filter benefits from continuity for known devices
    }

    /** Clear the remote tap event after it’s been consumed. */
    fun clearRemoteTapEvent() {
        _remoteTapEvent.value = null
    }
}
