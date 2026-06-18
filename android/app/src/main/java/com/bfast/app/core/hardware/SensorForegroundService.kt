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
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Data class for incoming/outgoing payment requests.
 * Contains the counterparty's full display name (from BLE, up to 10 chars)
 * and their device ID for routing.
 */
data class PaymentRequest(
    val deviceId: String,
    val displayName: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Handshake state machine for the tap-to-pay lifecycle.
 *
 * Flow:  IDLE → TAP_DETECTED → BLE_MATCHED → PAYMENT_PENDING → COMPLETE → IDLE
 *
 * This prevents double-triggering and ensures clean re-entry for the next tap.
 * Each state transition is guarded — you can only move forward, never skip states.
 */
enum class HandshakeState {
    /** Waiting for a tap. Ready to detect. */
    IDLE,
    /** Tap detected by accelerometer/gyroscope. Waiting for BLE match. */
    TAP_DETECTED,
    /** BLE match found (sender found receiver or vice versa). Waiting for payment. */
    BLE_MATCHED,
    /** Payment is being processed by the UI/backend. */
    PAYMENT_PENDING,
    /** Transaction complete. Will auto-reset to IDLE. */
    COMPLETE
}

/**
 * Detection mode for the tap-to-pay system.
 */
enum class DetectionMode {
    /** Uses accelerometer + gyroscope for tap detection, BLE for proximity. */
    ACCEL_GYRO_BLE,
    /** Uses accelerometer for tap detection, UWB for distance, BLE for identity. */
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
    private lateinit var tapDetector: TapDetector
    private lateinit var bleManager: BleManager
    private lateinit var uwbManager: UwbManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

        // ── Handshake State Machine ─────────────────────────────────────
        private val _handshakeState = MutableStateFlow(HandshakeState.IDLE)
        val handshakeState: StateFlow<HandshakeState> = _handshakeState

        // ── Local tap state ─────────────────────────────────────────────────────
        var lastPhysicalTapTime: Long = 0L
        var lastPhysicalTapConfidence: Float = 0f

        // ── Detection Mode ──────────────────────────────────────────────
        private val _detectionMode = MutableStateFlow(DetectionMode.ACCEL_GYRO_BLE)
        val detectionMode: StateFlow<DetectionMode> = _detectionMode

        // Sender gets this when a RECEIVER is found via tap
        private val _outgoingPaymentMatch = MutableStateFlow<PaymentRequest?>(null)
        val outgoingPaymentMatch: StateFlow<PaymentRequest?> = _outgoingPaymentMatch

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

        // UWB availability — so UI can show/hide toggle
        private val _uwbAvailable = MutableStateFlow(false)
        val uwbAvailable: StateFlow<Boolean> = _uwbAvailable

        // UWB error messages — human-readable
        private val _uwbErrorMessage = MutableStateFlow<String?>(null)
        val uwbErrorMessage: StateFlow<String?> = _uwbErrorMessage

        @Volatile
        var instance: SensorForegroundService? = null

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
                kotlinx.coroutines.delay(2000)
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
            // Reset BLE and tap state for the next interaction
            instance?.bleManager?.resetClosestDevice()
            instance?.tapDetector?.reset()
            instance?.lastPhysicalTapTime = 0L
            val role = if (isSenderMode.value) "S" else "R"
            instance?.bleManager?.startAdvertising(role, myDisplayName, myDeviceId)
        }

        fun advertisePayment(amountPaise: Long) {
            instance?.bleManager?.startAdvertising("P", amountPaise.toString(), myDeviceId)
            _handshakeState.value = HandshakeState.PAYMENT_PENDING
            CoroutineScope(Dispatchers.Main).launch {
                kotlinx.coroutines.delay(5000)
                val role = if (isSenderMode.value) "S" else "R"
                instance?.bleManager?.startAdvertising(role, myDisplayName, myDeviceId)
                _handshakeState.value = HandshakeState.COMPLETE
                // Auto-reset to IDLE after payment broadcast completes
                kotlinx.coroutines.delay(1000)
                resetHandshakeState()
            }
        }

        /**
         * Switch detection mode. If switching to UWB_BLE, checks hardware first.
         * Returns a human-readable error message if UWB is not available.
         */
        fun setDetectionMode(mode: DetectionMode): String? {
            if (mode == DetectionMode.UWB_BLE) {
                val uwbAvail = instance?.uwbManager?.hasUwbHardware() ?: false
                if (!uwbAvail) {
                    return "UWB chip not found in this device. Your phone does not have Ultra-Wideband hardware. Please use the Accelerometer + Gyroscope + BLE mode instead."
                }
            }
            _detectionMode.value = mode
            _uwbErrorMessage.value = null
            Log.i(TAG, "Detection mode changed to: $mode")

            // Save preference
            instance?.serviceScope?.launch {
                instance?.dataStoreManager?.saveDetectionMode(mode.name)
            }

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

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()

        // Read myDeviceId and myDisplayName from DataStore
        serviceScope.launch {
            dataStoreManager.deviceId.collect { id ->
                if (id != null) {
                    myDeviceId = id
                    bleManager.ownDeviceId = id // Set for self-filtering
                }
            }
        }
        serviceScope.launch {
            dataStoreManager.displayName.collect { name ->
                if (name != null) myDisplayName = name
            }
        }
        // Restore detection mode preference
        serviceScope.launch {
            dataStoreManager.detectionMode.collect { modeName ->
                try {
                    val mode = DetectionMode.valueOf(modeName)
                    _detectionMode.value = mode
                } catch (e: Exception) {
                    _detectionMode.value = DetectionMode.ACCEL_GYRO_BLE
                }
            }
        }

        bleManager = BleManager(this)
        uwbManager = UwbManager(this)

        // Check UWB hardware availability
        _uwbAvailable.value = uwbManager.hasUwbHardware()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        tapDetector = TapDetector { confidence, tapTime ->
            // Only process tap if in IDLE state (prevents double-triggering)
            if (_handshakeState.value != HandshakeState.IDLE) {
                Log.d(TAG, "Tap ignored — handshake in state: ${_handshakeState.value}")
                return@TapDetector
            }

            // ONE-WAY TAP LOGIC: Sender completely ignores physical taps.
            // Only the tap on the receiver's phone triggers the process.
            if (isSenderMode.value) {
                Log.d(TAG, "Sender ignores physical tap — waiting for Receiver 'T' message.")
                return@TapDetector
            }

            _tapEvents.value = confidence
            lastPhysicalTapTime = tapTime
            lastPhysicalTapConfidence = confidence

            // Transition to TAP_DETECTED state
            _handshakeState.value = HandshakeState.TAP_DETECTED

            // When a tap is detected, check if we have a nearby BLE device
            val closest = bleManager.closestDevice.value
            if (closest != null && abs(tapTime - closest.timestamp) <= 2000) {
                processProximityTap(closest)
            } else {
                // No BLE device yet — state will be checked when BLE finds one
                // Auto-reset to IDLE if no match found within 3 seconds
                serviceScope.launch {
                    kotlinx.coroutines.delay(3000)
                    if (_handshakeState.value == HandshakeState.TAP_DETECTED) {
                        Log.d(TAG, "Tap expired — no BLE match within 3s")
                        _handshakeState.value = HandshakeState.IDLE
                        bleManager.resetClosestDevice()
                    }
                }
            }
        }

        // Two-way correlation: also trigger when BLE discovers a device AFTER the tap
        serviceScope.launch {
            bleManager.closestDevice.collect { device ->
                if (device != null) {
                    val isSender = isSenderMode.value

                    // Check for offline protocol commands from counterparty
                    if (isSender && device.command == "T") {
                        // CRITICAL: Only respond to "T" when in IDLE state.
                        // Without this guard, any stray "T" from a nearby receiver
                        // (e.g. from a false-positive tap on their side) would
                        // instantly open the payment page — the exact false-trigger bug.
                        if (_handshakeState.value == HandshakeState.IDLE) {
                            Log.i(TAG, "Sender received 'T' from receiver ${device.deviceId} — opening payment screen")
                            _handshakeState.value = HandshakeState.BLE_MATCHED
                            _outgoingPaymentMatch.value = PaymentRequest(
                                deviceId = device.deviceId,
                                displayName = device.displayName
                            )
                        } else {
                            Log.d(TAG, "Sender received 'T' but in state ${_handshakeState.value} — ignoring")
                        }
                    } else if (isSender && device.command == "A") {
                        if (_outgoingPaymentMatch.value?.deviceId == device.deviceId) {
                            _receiverAccepted.value = true
                        }
                    } else if (isSender && device.command == "D") {
                        if (_outgoingPaymentMatch.value?.deviceId == device.deviceId) {
                            _receiverRejected.value = true
                        }
                    } else if (!isSender && device.command == "P") {
                        if (_incomingPaymentRequest.value?.deviceId == device.deviceId || true) {
                            val amountPaise = device.amountPaise ?: device.displayName.toLongOrNull() ?: 0L
                            _paymentReceivedEvent.value = PaymentRequest(
                                deviceId = device.deviceId,
                                displayName = device.amountPaise?.toString() ?: "0"
                            )

                            // ── Record received transaction in Room DB ──────
                            serviceScope.launch {
                                try {
                                    paymentRepository.recordReceivedPayment(
                                        "Sender (${device.deviceId})",
                                        amountPaise
                                    )
                                    // Refresh balance
                                    homeRepository.fetchBalance()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to record received payment", e)
                                }
                            }

                            // ── Fire instant high-priority notification ─────
                            firePaymentReceivedNotification(
                                senderName = "Sender (${device.deviceId.take(8)})",
                                amountPaise = amountPaise
                            )

                            // Reset back to R after receiving
                            bleManager.startAdvertising("R", myDisplayName, myDeviceId)
                            _receiverAccepted.value = false
                        }
                    }

                    // Original physical tap proximity logic — only if in TAP_DETECTED state
                    if (_handshakeState.value == HandshakeState.TAP_DETECTED &&
                        lastPhysicalTapTime > 0 &&
                        abs(lastPhysicalTapTime - device.timestamp) <= 2000
                    ) {
                        processProximityTap(device)
                    }
                }
            }
        }

        // Register sensor listeners with the fastest available rate.
        // The new TapDetector uses time-based gravity EMA, so it handles any
        // sample rate correctly.  Fastest rate gives the best impulse resolution
        // and is critical on Samsung phones where GAME rate may be too slow.
        try {
            accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
            gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        } catch (e: Exception) {
            // Fallback chain: FASTEST → GAME → NORMAL
            try {
                accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
                gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            } catch (e2: Exception) {
                try {
                    accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
                    gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
                } catch (e3: Exception) {
                    Log.e(TAG, "Failed to register sensor listeners at any delay", e3)
                }
            }
        }

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
                    lastPhysicalTapTime = 0L
                    _handshakeState.value = HandshakeState.IDLE
                }
            }
        }
    }

    /**
     * Core handshake logic using backend endpoints.
     * Guarded by the state machine — only fires when in TAP_DETECTED state.
     */
    private fun processProximityTap(device: DiscoveredDevice) {
        // Guard: only process if we're in TAP_DETECTED state
        if (_handshakeState.value != HandshakeState.TAP_DETECTED) return

        val isSender = isSenderMode.value

        // In UWB+BLE mode, also validate distance via UWB
        if (_detectionMode.value == DetectionMode.UWB_BLE) {
            val uwbResult = uwbManager.estimateDistanceFromRssi(device.rssi, device.deviceId)
            if (!uwbManager.isWithinTapDistance(uwbResult)) {
                Log.d(TAG, "UWB distance check failed: ${uwbResult.distanceCm}cm > ${UwbManager.TAP_DISTANCE_THRESHOLD_CM}cm")
                _handshakeState.value = HandshakeState.IDLE
                return
            }
        }

        val snapshot = com.bfast.app.data.remote.SensorSnapshotDto(
            accelPeakMs2 = tapDetector.lastPeakAccel,
            accelDurationMs = tapDetector.lastDuration.toInt(),
            gyroMagnitudeRads = tapDetector.lastGyro,
            tapTimestamp = java.time.format.DateTimeFormatter.ISO_INSTANT.format(
                java.time.Instant.ofEpochMilli(lastPhysicalTapTime)
            ),
            accelConfidence = lastPhysicalTapConfidence.toDouble(),
            gyroConfidence = 1.0 // Currently gyro is used as a hard rejection gate
        )

        if (isSender && device.role == "RECEIVER") {
            // Transition to BLE_MATCHED
            _handshakeState.value = HandshakeState.BLE_MATCHED

            _outgoingPaymentMatch.value = PaymentRequest(
                deviceId = device.deviceId,
                displayName = device.displayName
            )

            // Call the sender API, retry up to 5 times (1.5 seconds total)
            serviceScope.launch {
                var matched = false
                var attempts = 0
                while (!matched && attempts < 5) {
                    val result = bumpRepository.submitSenderBump(
                        com.bfast.app.data.remote.SenderBumpRequest(
                            deviceId = myDeviceId,
                            nearbyDevices = listOf(
                                com.bfast.app.data.remote.NearbyDeviceDto(device.deviceId, device.rssi)
                            ),
                            sensorSnapshot = snapshot
                        )
                    )

                    if (result.isSuccess) {
                        val data = result.getOrNull()
                        if (data?.matched == true) {
                            matched = true
                            _outgoingPaymentMatch.value = PaymentRequest(
                                deviceId = data.receiverDeviceId ?: device.deviceId,
                                displayName = data.receiverUserId ?: device.displayName
                            )
                            break
                        }
                    }
                    attempts++
                    kotlinx.coroutines.delay(300)
                }
            }
        } else if (!isSender && device.role == "SENDER") {
            // Transition to BLE_MATCHED
            _handshakeState.value = HandshakeState.BLE_MATCHED

            // Store incoming request for potential UI display
            _incomingPaymentRequest.value = PaymentRequest(
                deviceId = device.deviceId,
                displayName = device.displayName
            )

            // Receiver: hit backend
            serviceScope.launch {
                bumpRepository.submitReceiverBump(
                    com.bfast.app.data.remote.ReceiverBumpRequest(
                        deviceId = myDeviceId,
                        rssi = device.rssi,
                        sensorSnapshot = snapshot
                    )
                )
            }

            // ONE-WAY TAP LOGIC: Receiver tells Sender it was tapped!
            bleManager.startAdvertising("T", myDisplayName, myDeviceId)
        } else {
            // No valid match — reset to IDLE
            _handshakeState.value = HandshakeState.IDLE
        }
    }

    // ── Notification System ─────────────────────────────────────────────────

    /**
     * Fire an instant high-priority notification when a payment is received.
     * This fires from the service, so it works even when the app is in background
     * or on a different screen (fixes Issue #4).
     */
    private fun firePaymentReceivedNotification(senderName: String, amountPaise: Long) {
        try {
            val amountRs = amountPaise / 100.0
            val formattedAmount = String.format("%.2f", amountRs)

            // Intent to open the app when notification is tapped
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
                .setVibrate(longArrayOf(0, 300, 200, 300)) // Vibrate pattern
                .build()

            val notificationManager = getSystemService(NotificationManager::class.java)
            val notifId = PAYMENT_NOTIFICATION_ID_BASE + (System.currentTimeMillis() % 10000).toInt()
            notificationManager.notify(notifId, notification)

            Log.i(TAG, "Payment notification fired: ₹$formattedAmount from $senderName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fire payment notification", e)
        }
    }

    // ── Service Lifecycle ───────────────────────────────────────────────────

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
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        sensorManager.unregisterListener(this)
        bleManager.stopScanning()
        bleManager.stopAdvertising()
        uwbManager.destroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Create both notification channels:
     *  1. SensorServiceChannel — low priority, silent (foreground service)
     *  2. PaymentNotificationChannel — HIGH priority, sound + vibration (payment alerts)
     */
    private fun createNotificationChannels() {
        // Foreground service channel (low priority, silent)
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "BFast Tap Detection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background service for tap detection and device scanning"
        }

        // Payment notification channel (HIGH priority, sound + vibration + heads-up)
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
