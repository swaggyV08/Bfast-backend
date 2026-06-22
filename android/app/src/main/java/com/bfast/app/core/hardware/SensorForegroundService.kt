package com.bfast.app.core.hardware

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Data class for incoming/outgoing payment requests.
 * Contains the counterparty's full display name (from BLE, up to 8 chars)
 * and their device ID for routing.
 */
data class PaymentRequest(
    val deviceId: String,
    val displayName: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 13-Layer Discovery State Machine (Layer 3).
 *
 * Flow:  IDLE → DISCOVERED → ARMED → TAP_CONFIRMED → PAYMENT_READY → COMPLETE → IDLE
 *
 * IDLE:            No receiver nearby. Scanning.
 * DISCOVERED:      Receiver identified via BLE. Receiver card visible. No payment UI.
 * ARMED:           Receiver locked + strong proximity + tap detection active.
 * TAP_CONFIRMED:   Physical tap validated by ConfidenceEngine ≥ 70.
 * PAYMENT_READY:   Payment sheet open. User entering amount.
 * COMPLETE:        Transaction complete. Auto-resets to IDLE.
 */
enum class HandshakeState {
    /** No receiver nearby. Scanning. */
    IDLE,
    /** Receiver identified via BLE. Receiver card visible. No payment UI. */
    DISCOVERED,
    /** Receiver locked + strong proximity + tap detection active. Waiting for tap. */
    ARMED,
    /** Physical tap validated by ConfidenceEngine ≥ 70. */
    TAP_CONFIRMED,
    /** Payment sheet open. User entering amount. */
    PAYMENT_READY,
    /** Transaction complete. Will auto-reset to IDLE. */
    COMPLETE
}

/**
 * Detection mode for the tap-to-pay system.
 * UWB_BLE is retained for API compatibility but is disabled at runtime.
 */
enum class DetectionMode {
    /** Uses accelerometer + gyroscope for tap detection, BLE for proximity. */
    ACCEL_GYRO_BLE,
    /** DISABLED — retained for enum compatibility only. */
    UWB_BLE
}

@AndroidEntryPoint
class SensorForegroundService : Service(), SensorEventListener {

    @Inject
    lateinit var dataStoreManager: com.bfast.app.data.local.DataStoreManager

    @Inject
    lateinit var bumpRepository: com.bfast.app.data.repository.BumpRepository

    @Inject
    lateinit var paymentRepository: com.bfast.app.data.repository.PaymentRepository

    @Inject
    lateinit var homeRepository: com.bfast.app.data.repository.HomeRepository

    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    lateinit var tapDetector: TapDetector
    private lateinit var bleManager: BleManager
    private lateinit var uwbManager: UwbManager

    // ── Production-grade engines (13-Layer Architecture) ─────────────────
    private val confidenceEngine = ConfidenceEngine()
    private val dualDeviceCorrelator = DualDeviceCorrelator()
    private val receiverLock = ReceiverLock()
    private val approachVerifier = ApproachVerifier()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Job for the arm timeout timer. */
    private var armTimeoutJob: Job? = null

    /** Timestamp when DISCOVERED state was entered (for stability timing). */
    private var discoveredAtMs: Long = 0L

    companion object {
        private const val TAG = "SensorService"

        const val CHANNEL_ID = "SensorServiceChannel"
        const val PAYMENT_CHANNEL_ID = "PaymentNotificationChannel"
        const val NOTIFICATION_ID = 1
        private const val PAYMENT_NOTIFICATION_ID_BASE = 1000

        // Persistent device ID — set from DataStore before service starts
        var myDeviceId: String = "user-${java.util.UUID.randomUUID().toString().take(8)}"
        var myDisplayName: String = "User"

        // Role control
        val isSenderMode = MutableStateFlow(false)

        // ── Handshake State Machine (Layer 3) ───────────────────────────
        private val _handshakeState = MutableStateFlow(HandshakeState.IDLE)
        val handshakeState: StateFlow<HandshakeState> = _handshakeState

        // ── Local tap state ─────────────────────────────────────────────
        var lastPhysicalTapTime: Long = 0L
        var lastPhysicalTapConfidence: Float = 0f

        // ── Detection Mode (forced to ACCEL_GYRO_BLE — UWB disabled) ────
        private val _detectionMode = MutableStateFlow(DetectionMode.ACCEL_GYRO_BLE)
        val detectionMode: StateFlow<DetectionMode> = _detectionMode

        // Sender gets this when a RECEIVER is found via tap
        private val _outgoingPaymentMatch = MutableStateFlow<PaymentRequest?>(null)
        val outgoingPaymentMatch: StateFlow<PaymentRequest?> = _outgoingPaymentMatch

        // ── Nearby receiver detected via BLE proximity (NO tap required) ────
        // Exposed for TapDetectionScreen to show receiver details inline.
        private val _nearbyReceiver = MutableStateFlow<PaymentRequest?>(null)
        val nearbyReceiver: StateFlow<PaymentRequest?> = _nearbyReceiver

        // ── Tap confirmed for payment (tap verified by ConfidenceEngine) ────
        // Only set to true when a real physical tap passes all gates.
        // TapDetectionScreen observes this to navigate to PaymentEntryScreen.
        private val _tapConfirmedForPayment = MutableStateFlow(false)
        val tapConfirmedForPayment: StateFlow<Boolean> = _tapConfirmedForPayment

        // Receiver gets this when a SENDER taps their phone
        private val _incomingPaymentRequest = MutableStateFlow<PaymentRequest?>(null)
        val incomingPaymentRequest: StateFlow<PaymentRequest?> = _incomingPaymentRequest

        // Tap confidence for UI feedback
        private val _tapEvents = MutableStateFlow<Float?>(null)
        val tapEvents: StateFlow<Float?> = _tapEvents

        // Live sensor data for debug/test screen
        private val _sensorData = MutableStateFlow<SensorData?>(null)
        val sensorData: StateFlow<SensorData?> = _sensorData

        // Receiver accepted/rejected handshake — sender observes these
        private val _receiverAccepted = MutableStateFlow(false)
        val receiverAccepted: StateFlow<Boolean> = _receiverAccepted

        private val _receiverRejected = MutableStateFlow(false)
        val receiverRejected: StateFlow<Boolean> = _receiverRejected

        // Receiver gets this when the sender successfully sends payment over BLE offline
        private val _paymentReceivedEvent = MutableStateFlow<PaymentRequest?>(null)
        val paymentReceivedEvent: StateFlow<PaymentRequest?> = _paymentReceivedEvent

        // UWB availability — disabled, always false
        private val _uwbAvailable = MutableStateFlow(false)
        val uwbAvailable: StateFlow<Boolean> = _uwbAvailable

        // UWB error messages — disabled
        private val _uwbErrorMessage = MutableStateFlow<String?>(null)
        val uwbErrorMessage: StateFlow<String?> = _uwbErrorMessage

        // ── Deduplication guards ────────────────────────────────────────
        @Volatile
        private var lastProcessedPDeviceId: String? = null
        @Volatile
        private var lastProcessedPTime: Long = 0L
        private const val P_DEDUP_WINDOW_MS = 10000L

        // ── Proximity score for UI feedback ──────────────────────────────
        private val _proximityScore = MutableStateFlow(0f)
        val proximityScore: StateFlow<Float> = _proximityScore

        // ── Sensor test screen live values (10Hz sampled in updateSensorData) ──
        val liveImpulse = MutableStateFlow(0.0)
        val liveGyroMag = MutableStateFlow(0.0)

        @Volatile
        var instance: SensorForegroundService? = null

        fun enterSensorTestMode() { instance?.enterSensorTestModeInternal() }
        fun exitSensorTestMode() { instance?.exitSensorTestModeInternal() }

        fun clearIncomingRequest() {
            _incomingPaymentRequest.value = null
        }

        fun clearOutgoingMatch() {
            _outgoingPaymentMatch.value = null
        }

        fun acceptIncomingRequest() {
            _receiverAccepted.value = true
            instance?.bleManager?.startAdvertising("A", myDisplayName, myDeviceId)
        }

        fun rejectIncomingRequest() {
            _receiverRejected.value = true
            instance?.bleManager?.startAdvertising("D", myDisplayName, myDeviceId)
            CoroutineScope(Dispatchers.Main).launch {
                delay(2000)
                instance?.bleManager?.startAdvertising("R", myDisplayName, myDeviceId)
            }
            _incomingPaymentRequest.value = null
        }

        /**
         * Full reset of the handshake state machine. Transitions back to IDLE
         * and clears all match/payment state. Called after transaction completes
         * or when the user navigates away.
         */
        fun resetHandshakeState() {
            _handshakeState.value = HandshakeState.IDLE
            _receiverAccepted.value = false
            _receiverRejected.value = false
            _outgoingPaymentMatch.value = null
            _incomingPaymentRequest.value = null
            _paymentReceivedEvent.value = null
            _nearbyReceiver.value = null
            _tapConfirmedForPayment.value = false
            _proximityScore.value = 0f
            // Reset engines
            instance?.let {
                it.bleManager.resetClosestDevice()
                it.tapDetector.armed = false
                it.tapDetector.reset()
                it.dualDeviceCorrelator.reset()
                it.approachVerifier.clearAll()
                it.armTimeoutJob?.cancel()
                it.sensorStreamingJob?.cancel()
                it.lastPhysicalTapTime = 0L
                it.discoveredAtMs = 0L
                it.sensorReadingsBuffer.clear()
            }
            val role = if (isSenderMode.value) "S" else "R"
            instance?.bleManager?.startAdvertising(role, myDisplayName, myDeviceId)
        }

        /**
         * Reset only the tap confirmation flag — called when entering
         * TapDetectionScreen to clear stale tap state from previous interactions.
         */
        fun resetTapConfirmation() {
            _tapConfirmedForPayment.value = false
            _outgoingPaymentMatch.value = null
        }

        fun advertisePayment(amountPaise: Long) {
            instance?.bleManager?.startAdvertising("P", amountPaise.toString(), myDeviceId)
            _handshakeState.value = HandshakeState.PAYMENT_READY
            CoroutineScope(Dispatchers.Main).launch {
                delay(5000)
                val role = if (isSenderMode.value) "S" else "R"
                instance?.bleManager?.startAdvertising(role, myDisplayName, myDeviceId)
                _handshakeState.value = HandshakeState.COMPLETE
                delay(1000)
                resetHandshakeState()
            }
        }

        /**
         * Switch detection mode — UWB is DISABLED. Always returns error for UWB.
         */
        fun setDetectionMode(mode: DetectionMode): String? {
            if (mode == DetectionMode.UWB_BLE) {
                return "UWB mode is currently disabled. Please use Accelerometer + Gyroscope + BLE mode."
            }
            _detectionMode.value = DetectionMode.ACCEL_GYRO_BLE
            _uwbErrorMessage.value = null
            Log.i(TAG, "Detection mode: ACCEL_GYRO_BLE (UWB disabled)")
            return null
        }
    }

    data class SensorData(
        val accelX: Float, val accelY: Float, val accelZ: Float,
        val gyroX: Float, val gyroY: Float, val gyroZ: Float
    )

    private var currentAccel = FloatArray(3)
    private var currentGyro = FloatArray(3)
    private var lastPhysicalTapTime = 0L

    // ════════════════════════════════════════════════════════════════════════
    //  Service Lifecycle
    // ════════════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()

        // Force detection mode to ACCEL_GYRO_BLE — UWB is disabled
        _detectionMode.value = DetectionMode.ACCEL_GYRO_BLE

        // Read myDeviceId and myDisplayName from DataStore
        serviceScope.launch {
            dataStoreManager.deviceId.collect { id ->
                if (id != null) {
                    myDeviceId = id
                    bleManager.ownDeviceId = id
                }
            }
        }
        serviceScope.launch {
            dataStoreManager.displayName.collect { name ->
                if (name != null) myDisplayName = name
            }
        }
        serviceScope.launch {
            dataStoreManager.detectionMode.collect { _ ->
                _detectionMode.value = DetectionMode.ACCEL_GYRO_BLE
            }
        }

        bleManager = BleManager(this)
        // Set ownDeviceId IMMEDIATELY to prevent self-detection race condition.
        bleManager.ownDeviceId = myDeviceId
        uwbManager = UwbManager(this)

        // UWB is disabled
        _uwbAvailable.value = false

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // ════════════════════════════════════════════════════════════════════
        //  TAP HANDLER (Layers 5-6, 11-12)
        // ════════════════════════════════════════════════════════════════════
        tapDetector = TapDetector { signature ->
            // Always update debug/test screen
            _tapEvents.value = signature.confidence
            lastPhysicalTapConfidence = signature.confidence
            lastPhysicalTapTime = signature.timestampMs

            Log.d(TAG, "Tap event: conf=${signature.confidence} peak=${signature.peakAccelMs2}m/s² " +
                    "state=${_handshakeState.value} isSender=${isSenderMode.value}")

            // Only RECEIVER processes local taps (tap is detected on receiver's phone).
            // SENDER waits for BLE "T" command or backend poll — never its own accelerometer.
            if (!isSenderMode.value && _handshakeState.value == HandshakeState.ARMED) {
                handleReceiverTap(signature)
            }
        }

        // ════════════════════════════════════════════════════════════════════
        //  BLE PROXIMITY STATE LISTENER (Layers 1-4)
        //
        //  Drives the state machine:
        //    IDLE → DISCOVERED → ARMED → (tap handler takes over)
        //
        //  Also handles A/D/P commands from other devices.
        // ════════════════════════════════════════════════════════════════════
        serviceScope.launch {
            bleManager.closestDevice.collect { device ->
                if (device != null) {
                    val isSender = isSenderMode.value
                    val now = System.currentTimeMillis()
                    val proxScore = device.proximityScore

                    // Update UI-visible proximity score
                    _proximityScore.value = proxScore

                    val targetRole = if (isSender) "RECEIVER" else "SENDER"

                    // ── Feed RSSI to ApproachVerifier (Layer 7) ──────────────
                    if (device.role == targetRole) {
                        approachVerifier.feedRssi(device.deviceId, device.rssi, now)
                    }

                    // ── State Machine Transitions ───────────────────────────
                    val state = _handshakeState.value

                    when (state) {
                        HandshakeState.IDLE -> {
                            // IDLE → DISCOVERED: When a target is detected nearby
                            // proxScore ≥ 20 = device is within BLE detection range
                            // This is intentionally loose — the TAP is the security gate,
                            // not BLE proximity. Discovery just means "a target exists".
                            if (device.role == targetRole && proxScore >= 20f) {
                                _handshakeState.value = HandshakeState.DISCOVERED
                                applySensorMode("FAST_TAP")
                                discoveredAtMs = now
                                _nearbyReceiver.value = PaymentRequest(
                                    deviceId = device.deviceId,
                                    displayName = device.displayName
                                )
                                Log.d(TAG, "→ DISCOVERED: ${device.displayName} (${device.deviceId}) proxScore=$proxScore")
                            }
                        }

                        HandshakeState.DISCOVERED -> {
                            // Update nearby target and try to lock
                            if (device.role == targetRole && proxScore >= 20f) {
                                // Try to acquire receiver lock (Layer 2)
                                val locked = receiverLock.tryLock(
                                    deviceId = device.deviceId,
                                    displayName = device.displayName,
                                    sessionId = device.sessionId,
                                    rssi = device.rssi,
                                    timestampMs = now
                                )

                                // Update nearby device from locked device
                                if (receiverLock.isLocked()) {
                                    _nearbyReceiver.value = PaymentRequest(
                                        deviceId = receiverLock.lockedDeviceId!!,
                                        displayName = receiverLock.lockedDisplayName ?: device.displayName
                                    )
                                }

                                // DISCOVERED → ARMED proximity thresholds:
                                //   SENDER mode:   proxScore ≥ 35, 300ms after lock
                                //   RECEIVER mode: proxScore ≥ 30, rssi ≥ -75
                                //     Why 30 not 50: at -55 dBm (contact range) proxScore ≈ 42
                                //     due to the dwell component not yet saturated. 50 would
                                //     never arm for devices with slightly weaker BLE radios.
                                //     ReceiverLock already enforces MIN_STABLE_DURATION_MS=500ms
                                //     so stableMs=0 avoids a redundant second wait.
                                val armProxThreshold = if (isSender) 30f else 20f
                                val armRssiThreshold = Int.MIN_VALUE // ReceiverLock already enforces RSSI
                                val stableMs = if (isSender) 200L else 0L

                                if (locked &&
                                    proxScore >= armProxThreshold &&
                                    device.rssi >= armRssiThreshold &&
                                    receiverLock.lockDurationMs() >= stableMs) {
                                    armTapDetection()
                                }
                            }

                            // DISCOVERED → IDLE: Receiver lost
                            if (proxScore < 10f) {
                                _handshakeState.value = HandshakeState.IDLE
                                applySensorMode("WARM_BASELINE")
                                _nearbyReceiver.value = null
                                receiverLock.unlock()
                                approachVerifier.clearAll()
                                discoveredAtMs = 0L
                                Log.d(TAG, "→ IDLE (receiver lost, proxScore=$proxScore)")
                            }
                        }

                        HandshakeState.ARMED -> {
                            // Refresh lock for the peer device.
                            // targetRole = "RECEIVER" in sender mode, "SENDER" in receiver mode.
                            // Using targetRole here is critical: without it, the lock expires
                            // silently in receiver mode (locked device is SENDER, not RECEIVER).
                            if (device.role == targetRole && device.deviceId == receiverLock.lockedDeviceId) {
                                receiverLock.refresh(device.deviceId, device.rssi, now)
                            }

                            // ARMED → DISCOVERED: Lock expired or receiver moved away
                            if (!receiverLock.isLocked() || proxScore < 15f) {
                                tapDetector.armed = false
                                armTimeoutJob?.cancel()
                                _handshakeState.value = HandshakeState.DISCOVERED
                                discoveredAtMs = now
                                Log.d(TAG, "→ DISCOVERED (disarmed: lock=${receiverLock.isLocked()}, proxScore=$proxScore)")
                            }

                            // ARMED → IDLE: Receiver completely gone
                            if (proxScore < 10f) {
                                tapDetector.armed = false
                                armTimeoutJob?.cancel()
                                _handshakeState.value = HandshakeState.IDLE
                                applySensorMode("WARM_BASELINE")
                                _nearbyReceiver.value = null
                                receiverLock.unlock()
                                approachVerifier.clearAll()
                                discoveredAtMs = 0L
                                Log.d(TAG, "→ IDLE (receiver gone while ARMED)")
                            }
                        }

                        // TAP_CONFIRMED / PAYMENT_READY / COMPLETE: No BLE transitions
                        else -> { /* Payment flow handles these states */ }
                    }

                    // ── "T" command: Receiver confirmed physical tap ─────
                    // Receiver broadcasts "T" immediately when its accelerometer
                    // detects a tap. This is the fastest path (~50ms) for the
                    // sender to know the payment can proceed.
                    if (isSender && device.command == "T") {
                        val isOurReceiver = receiverLock.lockedDeviceId == null ||
                            receiverLock.lockedDeviceId == device.deviceId
                        val senderCanAccept = _handshakeState.value == HandshakeState.ARMED ||
                            _handshakeState.value == HandshakeState.DISCOVERED
                        if (isOurReceiver && senderCanAccept) {
                            Log.i(TAG, "BLE 'T' received from ${device.deviceId} — receiver confirmed tap!")
                            sensorStreamingJob?.cancel()
                            armTimeoutJob?.cancel()
                            onTapConfirmedForSender(device.deviceId, device.displayName)
                        }
                    }

                    // ── "A" command: Receiver accepted ───────────────────
                    if (isSender && device.command == "A") {
                        if (_outgoingPaymentMatch.value?.deviceId == device.deviceId) {
                            _receiverAccepted.value = true
                        }

                    // ── "D" command: Receiver declined ───────────────────
                    } else if (isSender && device.command == "D") {
                        if (_outgoingPaymentMatch.value?.deviceId == device.deviceId) {
                            _receiverRejected.value = true
                        }

                    // ── "P" command: Payment broadcast from Sender ───────
                    } else if (!isSender && device.command == "P") {
                        val isDuplicateP = device.deviceId == lastProcessedPDeviceId &&
                                (now - lastProcessedPTime) < P_DEDUP_WINDOW_MS

                        if (!isDuplicateP) {
                            lastProcessedPDeviceId = device.deviceId
                            lastProcessedPTime = now

                            val amountPaise = device.amountPaise ?: device.displayName.toLongOrNull() ?: 0L
                            _paymentReceivedEvent.value = PaymentRequest(
                                deviceId = device.deviceId,
                                displayName = device.amountPaise?.toString() ?: "0"
                            )

                            // Record received transaction in Room DB
                            serviceScope.launch {
                                try {
                                    paymentRepository.recordReceivedPayment(
                                        "Sender (${device.deviceId})",
                                        amountPaise
                                    )
                                    homeRepository.fetchBalance()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to record received payment", e)
                                }
                            }

                            firePaymentReceivedNotification(
                                senderName = "Sender (${device.deviceId.take(8)})",
                                amountPaise = amountPaise
                            )

                            // Haptic for payment received
                            fireHapticFeedback(isSuccess = true)

                            bleManager.startAdvertising("R", myDisplayName, myDeviceId)
                            _receiverAccepted.value = false
                        }
                    }
                } else {
                    // No device nearby.
                    // DISCOVERED → IDLE: device genuinely gone, reset immediately.
                    // ARMED: do NOT reset on a momentary BLE gap.
                    //   BLE scan results have inherent gaps of 0.5–3s even with devices
                    //   at contact range. Resetting here was causing ARMED→IDLE→re-arm
                    //   loops that took >60s to complete a tap. The arm timeout and
                    //   ReceiverLock expiry handle cleanup from ARMED state.
                    val state = _handshakeState.value
                    if (state == HandshakeState.DISCOVERED) {
                        tapDetector.armed = false
                        armTimeoutJob?.cancel()
                        sensorStreamingJob?.cancel()
                        _handshakeState.value = HandshakeState.IDLE
                        _proximityScore.value = 0f
                        _nearbyReceiver.value = null
                        receiverLock.unlock()
                        approachVerifier.clearAll()
                        discoveredAtMs = 0L
                        Log.d(TAG, "→ IDLE (device lost while DISCOVERED)")
                    }
                    // ARMED: stay armed; let arm timeout / ReceiverLock expiry handle cleanup
                }
            }
        }

        // ── Remote Tap Listener (DualDeviceCorrelator) ──────────────────
        serviceScope.launch {
            bleManager.remoteTapEvent.collect { remoteTap ->
                if (remoteTap != null) {
                    Log.d(TAG, "Received remote tap from ${remoteTap.deviceId}: " +
                            "peak=${remoteTap.peakAccelX100/100.0}, dur=${remoteTap.durationMs}ms")

                    val correlationResult = dualDeviceCorrelator.recordRemoteTap(remoteTap)
                    if (correlationResult?.matched == true) {
                        Log.i(TAG, "Dual-device correlation MATCHED! " +
                                "score=${correlationResult.correlationScore}, " +
                                "timeDelta=${correlationResult.timeDeltaMs}ms")
                    }

                    bleManager.clearRemoteTapEvent()
                }
            }
        }

        // Start sensors in warm-baseline mode
        applySensorMode("WARM_BASELINE")

        // Start scanning automatically
        if (bleManager.isBluetoothEnabled()) {
            bleManager.startScanning()
        }

        // React to role changes — advertise as SENDER or RECEIVER
        serviceScope.launch {
            isSenderMode.collect { isSender ->
                if (bleManager.isBluetoothEnabled()) {
                    val role = if (isSender) "S" else "R"
                    bleManager.startAdvertising(role, myDisplayName, myDeviceId)
                    // Reset state when switching roles
                    bleManager.resetClosestDevice()
                    tapDetector.armed = false
                    armTimeoutJob?.cancel()
                    lastPhysicalTapTime = 0L
                    receiverLock.unlock()
                    applySensorMode("WARM_BASELINE")
                    approachVerifier.clearAll()
                    discoveredAtMs = 0L
                    if (!isSender) {
                        startReceiverHeartbeat()
                    } else {
                        stopReceiverHeartbeat()
                    }
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Receiver Heartbeat (Server-Side Liveness)
    // ════════════════════════════════════════════════════════════════════════

    private var heartbeatJob: kotlinx.coroutines.Job? = null

    private fun startReceiverHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                // TODO: Call server to report "I am alive and listening"
                // paymentCommitClient.sendHeartbeat(myDeviceId, bleManager.currentSessionId)
                Log.d(TAG, "Heartbeat: Receiver is online.")
                kotlinx.coroutines.delay(10_000) // 10s heartbeat
            }
        }
    }

    private fun stopReceiverHeartbeat() {
        heartbeatJob?.cancel()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Sensor Lifecycle Management (Battery & Accuracy Optimization)
    // ════════════════════════════════════════════════════════════════════════

    private var currentSensorMode = "NONE"

    private fun applySensorMode(mode: String) {
        if (currentSensorMode == mode) return
        
        try {
            when (mode) {
                "WARM_BASELINE" -> {
                    // Accel ON (Normal + 500ms batching), Gyro OFF
                    gyroSensor?.let { sensorManager.unregisterListener(this, it) }
                    accelSensor?.let { 
                        sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 500_000) 
                    }
                    Log.d(TAG, "Sensors downshifted: Accel warm-baseline, Gyro OFF")
                }
                "FAST_TAP" -> {
                    // Both ON (Fastest + 0 batching)
                    accelSensor?.let { 
                        sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, 0) 
                    }
                    gyroSensor?.let { 
                        sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, 0) 
                    }
                    Log.d(TAG, "Sensors upshifted: Accel+Gyro FASTEST")
                }
                "OFF" -> {
                    sensorManager.unregisterListener(this)
                    Log.d(TAG, "Sensors OFF")
                }
            }
            currentSensorMode = mode
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply sensor mode: $mode", e)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Arming Logic (Layer 4)
    // ════════════════════════════════════════════════════════════════════════

    // ════════════════════════════════════════════════════════════════════════
    //  Arming Logic & Streaming (Layer 4 & Dumb Pipe)
    // ════════════════════════════════════════════════════════════════════════

    private val sensorReadingsBuffer = java.util.concurrent.ConcurrentLinkedQueue<com.bfast.app.data.remote.SensorReadingDto>()
    private var sensorStreamingJob: kotlinx.coroutines.Job? = null

    private fun armTapDetection() {
        applySensorMode("FAST_TAP")
        _handshakeState.value = HandshakeState.ARMED
        armTimeoutJob?.cancel()
        sensorStreamingJob?.cancel()

        val peerDeviceId = receiverLock.lockedDeviceId ?: bleManager.closestDevice.value?.deviceId
        val isSender = isSenderMode.value

        if (!isSender) {
            // ── RECEIVER MODE: arm local TapDetector ──────────────────────
            // Tap is physically on the receiver's phone. We detect it here,
            // show a toast, and signal the sender via BLE "T" + backend POST.
            tapDetector.armed = true
            Log.i(TAG, "→ ARMED [RECEIVER]: tap detector live, waiting for physical tap (sender=$peerDeviceId)")

            // Auto-disarm after 30s if no tap. 30s gives the user ample time —
            // the original 10s expired before a normal "approach → tap" interaction.
            armTimeoutJob = serviceScope.launch {
                delay(30_000)
                if (_handshakeState.value == HandshakeState.ARMED) {
                    tapDetector.armed = false
                    applySensorMode("WARM_BASELINE")
                    _handshakeState.value = HandshakeState.DISCOVERED
                    discoveredAtMs = System.currentTimeMillis()
                    Log.d(TAG, "→ DISCOVERED (receiver arm timeout — no tap in 30s)")
                }
            }
        } else {
            // ── SENDER MODE: do NOT arm local TapDetector ─────────────────
            // Sender waits for receiver's BLE "T" command (primary, ~50ms latency)
            // or backend poll confirmation (fallback, ~300ms latency).
            tapDetector.armed = false
            Log.i(TAG, "→ ARMED [SENDER]: waiting for receiver tap confirm (receiver=$peerDeviceId)")

            // Backend polling fallback (in case BLE "T" is missed)
            val receiverId = peerDeviceId
            if (receiverId != null) {
                sensorStreamingJob = serviceScope.launch {
                    while (isActive && _handshakeState.value == HandshakeState.ARMED) {
                        try {
                            val result = bumpRepository.pollTapStatus(
                                senderDeviceId = myDeviceId.take(12),
                                receiverDeviceId = receiverId
                            )
                            if (result.getOrNull() == true) {
                                Log.i(TAG, "Backend poll: receiver tap confirmed!")
                                onTapConfirmedForSender(
                                    receiverId,
                                    receiverLock.lockedDisplayName ?: ""
                                )
                                break
                            }
                        } catch (_: Exception) { /* network error — keep polling */ }
                        delay(150)
                    }
                }
            }

            // Arm timeout — re-arm if still close, else back to DISCOVERED
            armTimeoutJob = serviceScope.launch {
                delay(15_000)
                if (_handshakeState.value == HandshakeState.ARMED) {
                    if (receiverLock.isLocked() && bleManager.proximityEngine.proximityScore >= 25f) {
                        armTapDetection()
                    } else {
                        tapDetector.armed = false
                        applySensorMode("WARM_BASELINE")
                        sensorStreamingJob?.cancel()
                        _handshakeState.value = HandshakeState.DISCOVERED
                        discoveredAtMs = System.currentTimeMillis()
                    }
                }
            }
        }
    }

    /**
     * Called on the RECEIVER when its TapDetector fires while ARMED.
     * Validates proximity, shows toast, broadcasts BLE "T", POSTs to backend.
     */
    private fun handleReceiverTap(signature: TapSignature) {
        // No proximity re-check. The ARMED state already guarantees the sender was
        // within range when arming (ReceiverLock required RSSI ≥ -80, 2+ stable readings).
        // Re-checking closestDevice here caused 100% tap rejection during normal BLE
        // scan gaps — even with sender at contact range, closestDevice can be null for
        // up to 3 seconds between scan windows. ARMED state is the security boundary.
        Log.i(TAG, "RECEIVER TAP CONFIRMED: peak=${signature.peakAccelMs2}m/s² " +
                "conf=${signature.confidence} dur=${signature.durationMs}ms")

        // Disarm immediately to prevent double-trigger
        tapDetector.armed = false
        armTimeoutJob?.cancel()
        _handshakeState.value = HandshakeState.TAP_CONFIRMED

        // Haptic pulse
        fireHapticFeedback(isSuccess = false)

        // Toast on receiver's screen — must run on main thread
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, "Tap Detected!", Toast.LENGTH_SHORT).show()
        }

        // Resolve sender identity from ReceiverLock (persists through BLE gaps)
        // or from closestDevice if BLE scan just happened to be active at tap time.
        val lockedId = receiverLock.lockedDeviceId ?: bleManager.closestDevice.value?.deviceId ?: ""
        val lockedName = receiverLock.lockedDisplayName ?: bleManager.closestDevice.value?.displayName ?: "Sender"
        val lastRssi = bleManager.closestDevice.value?.rssi ?: -70

        // Mark receiver as having an incoming payment
        _incomingPaymentRequest.value = PaymentRequest(
            deviceId = lockedId,
            displayName = lockedName
        )

        // Broadcast "T" and POST to backend (runs async, non-blocking)
        serviceScope.launch {
            // 1. BLE "T" broadcast — sender picks this up instantly (~50ms)
            bleManager.startAdvertising("T", myDisplayName, myDeviceId)

            // 2. POST to backend — fallback for sender's polling path
            try {
                // Use take(12) to match the truncated deviceIds embedded in BLE payloads.
                // The backend key is "receiverTrunc12:senderTrunc12" on both sides.
                bumpRepository.reportTap(
                    receiverDeviceId = myDeviceId.take(12),
                    senderDeviceId = lockedId,
                    accelPeakMs2 = signature.peakAccelMs2,
                    rssi = lastRssi
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to post tap event to backend (BLE path still active)", e)
            }

            // 3. Restore receiver advertisement after 2s (keep "T" briefly so sender catches it)
            kotlinx.coroutines.delay(2000)
            if (_handshakeState.value != HandshakeState.PAYMENT_READY) {
                bleManager.startAdvertising("R", myDisplayName, myDeviceId)
            }
        }
    }

    /**
     * Called on the SENDER when the receiver confirms a tap (via BLE "T" or backend poll).
     * Triggers navigation to PaymentEntryScreen.
     */
    private fun onTapConfirmedForSender(receiverDeviceId: String, receiverDisplayName: String) {
        if (_handshakeState.value != HandshakeState.ARMED) return // Guard against double-fire

        Log.i(TAG, "Tap confirmed for sender! navigating to payment (receiver=$receiverDeviceId)")
        _handshakeState.value = HandshakeState.TAP_CONFIRMED
        fireHapticFeedback(isSuccess = false)

        val name = receiverDisplayName.ifBlank { receiverLock.lockedDisplayName ?: "Unknown" }

        // Update nearbyReceiver so TapDetectionScreen has the correct deviceId/name
        _nearbyReceiver.value = PaymentRequest(deviceId = receiverDeviceId, displayName = name)
        _outgoingPaymentMatch.value = PaymentRequest(deviceId = receiverDeviceId, displayName = name)

        // This triggers TapDetectionScreen to navigate to PaymentEntryScreen
        _tapConfirmedForPayment.value = true
        _handshakeState.value = HandshakeState.PAYMENT_READY
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Haptic Feedback (Layer 13)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Fire haptic feedback.
     * @param isSuccess true for payment success (strong pattern), false for tap confirmed (single pulse)
     */
    private fun fireHapticFeedback(isSuccess: Boolean) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (isSuccess) {
                // Strong success pattern
                vibrator.vibrate(VibrationEffect.createWaveform(
                    longArrayOf(0, 100, 80, 100),
                    intArrayOf(0, 200, 0, 255),
                    -1
                ))
            } else {
                // Single tap confirmation pulse
                vibrator.vibrate(VibrationEffect.createOneShot(
                    50, VibrationEffect.DEFAULT_AMPLITUDE
                ))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Haptic feedback failed", e)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Notification System
    // ════════════════════════════════════════════════════════════════════════

    private fun firePaymentReceivedNotification(senderName: String, amountPaise: Long) {
        try {
            val amountRs = amountPaise / 100.0
            val formattedAmount = String.format("%.2f", amountRs)

            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, PAYMENT_CHANNEL_ID)
                .setContentTitle("₹$formattedAmount Received! 🎉")
                .setContentText("You received ₹$formattedAmount from $senderName via BFast")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setVibrate(longArrayOf(0, 300, 200, 300))
                .build()

            val notificationManager = getSystemService(NotificationManager::class.java)
            val notifId = PAYMENT_NOTIFICATION_ID_BASE + (System.currentTimeMillis() % 10000).toInt()
            notificationManager.notify(notifId, notification)

            Log.i(TAG, "Payment notification fired: ₹$formattedAmount from $senderName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fire payment notification", e)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Sensor & Service Lifecycle
    // ════════════════════════════════════════════════════════════════════════

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BFast Active")
            .setContentText("Scanning for nearby devices...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                currentAccel = event.values.clone()
                tapDetector.processAccel(currentAccel[0], currentAccel[1], currentAccel[2])
                updateSensorData()
            }
            Sensor.TYPE_GYROSCOPE -> {
                currentGyro = event.values.clone()
                tapDetector.processGyro(currentGyro[0], currentGyro[1], currentGyro[2])
                updateSensorData()
            }
        }
    }

    private fun updateSensorData() {
        _sensorData.value = SensorData(
            accelX = currentAccel[0], accelY = currentAccel[1], accelZ = currentAccel[2],
            gyroX = currentGyro[0], gyroY = currentGyro[1], gyroZ = currentGyro[2]
        )
        liveImpulse.value = tapDetector.liveImpulse
        liveGyroMag.value = tapDetector.liveGyroMag
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Sensor Test Mode — forces FAST_TAP + arms TapDetector for the test UI.
    //  Safe because the tap callback only triggers handleReceiverTap() when
    //  the handshake state is ARMED, not IDLE. IDLE taps just update the log.
    // ─────────────────────────────────────────────────────────────────────────

    private var sensorTestMode = false

    internal fun enterSensorTestModeInternal() {
        sensorTestMode = true
        applySensorMode("FAST_TAP")
        // Arm the detector so it computes peaks for the test screen.
        // handleReceiverTap() won't fire — it guards on handshakeState == ARMED.
        if (_handshakeState.value == HandshakeState.IDLE) {
            tapDetector.armed = true
        }
        Log.d(TAG, "Sensor test mode ON (gyro + accel FASTEST, TapDetector armed for test)")
    }

    internal fun exitSensorTestModeInternal() {
        sensorTestMode = false
        // Only disarm and downshift if BLE hasn't moved us out of IDLE
        if (_handshakeState.value == HandshakeState.IDLE) {
            tapDetector.armed = false
            applySensorMode("WARM_BASELINE")
        }
        Log.d(TAG, "Sensor test mode OFF")
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        armTimeoutJob?.cancel()
        sensorManager.unregisterListener(this)
        bleManager.stopScanning()
        bleManager.stopAdvertising()
        uwbManager.destroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannels() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "BFast Tap Detection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background service for tap detection and device scanning"
        }

        val paymentChannel = NotificationChannel(
            PAYMENT_CHANNEL_ID,
            "Payment Notifications",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Instant notifications when you receive a payment"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 200, 300)
            enableLights(true)
            lightColor = android.graphics.Color.GREEN
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
            )
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
        manager.createNotificationChannel(paymentChannel)
    }
}
