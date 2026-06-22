package com.bfast.app.core.hardware

import android.os.Build
import android.util.Log
import kotlin.math.sqrt

/**
 * Per-device adaptive calibration engine.
 *
 * Automatically learns device-specific baselines during normal usage:
 *   - Accelerometer noise floor (mean + variance at rest)
 *   - Gyroscope noise floor
 *   - BLE signal characteristics
 *
 * No ML models. Uses simple exponential moving averages during idle periods.
 * Calibration data is stored in-memory and can be persisted via DataStore.
 */
class AdaptiveCalibrator {

    companion object {
        private const val TAG = "AdaptiveCalibrator"

        /** Minimum samples needed before calibration is considered valid. */
        const val MIN_CALIBRATION_SAMPLES = 100

        /** EMA alpha for noise floor estimation (slow adaptation). */
        const val NOISE_ALPHA = 0.01

        /** If impulse exceeds this, we're not in "idle" state — skip calibration. */
        const val IDLE_IMPULSE_THRESHOLD = 1.0  // m/s²

        /** If gyro exceeds this, skip calibration (phone is moving). */
        const val IDLE_GYRO_THRESHOLD = 0.5     // rad/s

        /** Re-calibrate if the last calibration is older than this (ms). */
        const val RECALIBRATION_INTERVAL_MS = 300_000L  // 5 minutes
    }

    /**
     * Calibration profile for a specific device.
     */
    data class CalibrationProfile(
        val deviceModel: String = Build.MODEL,
        val accelNoiseFloor: Double = 0.3,    // m/s² typical idle noise
        val accelNoiseVariance: Double = 0.01,
        val gyroNoiseFloor: Double = 0.05,    // rad/s typical idle noise
        val gyroNoiseVariance: Double = 0.001,
        val bleBaselineRssi: Double = -70.0,  // RSSI at arm's length (~60cm)
        val sampleCount: Int = 0,
        val lastCalibrationMs: Long = 0L,
        val accelSensitivityFactor: Double = 1.0  // 1.0 = normal
    )

    // ── Current profile ─────────────────────────────────────────────────
    @Volatile
    var profile: CalibrationProfile = CalibrationProfile()
        private set

    // ── Internal state for running calibration ──────────────────────────
    private var accelNoiseMean = 0.3
    private var accelNoiseVar = 0.01
    private var gyroNoiseMean = 0.05
    private var gyroNoiseVar = 0.001
    private var sampleCount = 0
    private var isCalibrated = false
    private var lastCalibrationTime = 0L

    // ── BLE baseline tracking ───────────────────────────────────────────
    private var bleRssiSum = 0.0
    private var bleRssiCount = 0

    // ════════════════════════════════════════════════════════════════════
    //  Core API
    // ════════════════════════════════════════════════════════════════════

    /**
     * Feed an accelerometer impulse reading during an idle period.
     * Only updates calibration if the phone appears to be at rest.
     *
     * @param impulse The current impulse magnitude (|accelMag - gravity|)
     * @param gyroMag The current gyroscope magnitude
     */
    fun feedIdleSample(impulse: Double, gyroMag: Double) {
        // Only calibrate when the phone is genuinely idle
        if (impulse > IDLE_IMPULSE_THRESHOLD || gyroMag > IDLE_GYRO_THRESHOLD) {
            return
        }

        sampleCount++

        // EMA for accelerometer noise floor
        if (sampleCount <= 1) {
            accelNoiseMean = impulse
            accelNoiseVar = 0.01
            gyroNoiseMean = gyroMag
            gyroNoiseVar = 0.001
        } else {
            accelNoiseMean = (1.0 - NOISE_ALPHA) * accelNoiseMean + NOISE_ALPHA * impulse
            val accelDiff = impulse - accelNoiseMean
            accelNoiseVar = (1.0 - NOISE_ALPHA) * accelNoiseVar + NOISE_ALPHA * (accelDiff * accelDiff)

            gyroNoiseMean = (1.0 - NOISE_ALPHA) * gyroNoiseMean + NOISE_ALPHA * gyroMag
            val gyroDiff = gyroMag - gyroNoiseMean
            gyroNoiseVar = (1.0 - NOISE_ALPHA) * gyroNoiseVar + NOISE_ALPHA * (gyroDiff * gyroDiff)
        }

        // Update profile when we have enough samples
        if (sampleCount >= MIN_CALIBRATION_SAMPLES) {
            isCalibrated = true
            lastCalibrationTime = System.currentTimeMillis()

            // Compute sensitivity factor: compare this device's noise floor to the "standard" 0.3 m/s²
            val sensitivityFactor = if (accelNoiseMean > 0.01) {
                0.3 / accelNoiseMean  // >1.0 means this device is quieter (more sensitive)
            } else {
                1.0
            }

            profile = CalibrationProfile(
                deviceModel = Build.MODEL,
                accelNoiseFloor = accelNoiseMean,
                accelNoiseVariance = accelNoiseVar,
                gyroNoiseFloor = gyroNoiseMean,
                gyroNoiseVariance = gyroNoiseVar,
                bleBaselineRssi = if (bleRssiCount > 0) bleRssiSum / bleRssiCount else -70.0,
                sampleCount = sampleCount,
                lastCalibrationMs = lastCalibrationTime,
                accelSensitivityFactor = sensitivityFactor.coerceIn(0.5, 2.0)
            )

            if (sampleCount % 500 == 0) {
                Log.d(TAG, "Calibration updated: accelNoise=${accelNoiseMean.format(4)}, " +
                        "gyroNoise=${gyroNoiseMean.format(4)}, " +
                        "sensitivity=${sensitivityFactor.format(2)}, " +
                        "samples=$sampleCount")
            }
        }
    }

    /**
     * Feed a BLE RSSI sample for baseline estimation.
     * Call this with RSSI values from devices that are NOT in proximity
     * (i.e., RSSI below -50 dBm) to establish the "far away" baseline.
     */
    fun feedBleBaseline(rssi: Int) {
        if (rssi < -50) {  // Only use "far away" samples for baseline
            bleRssiSum += rssi
            bleRssiCount++
        }
    }

    /**
     * Check if calibration is current (within RECALIBRATION_INTERVAL_MS).
     */
    fun isCalibrationCurrent(): Boolean {
        if (!isCalibrated) return false
        return (System.currentTimeMillis() - lastCalibrationTime) < RECALIBRATION_INTERVAL_MS
    }

    /**
     * Get the recommended tap detection threshold adjustment based on this device's
     * accelerometer sensitivity. Returns a multiplier to apply to the base threshold.
     *
     * - Returns 1.0 for "average" devices
     * - Returns < 1.0 for noisy devices (lower threshold to compensate)
     * - Returns > 1.0 for quiet devices (can afford higher threshold)
     */
    fun getThresholdAdjustment(): Double {
        return if (isCalibrated) {
            profile.accelSensitivityFactor
        } else {
            1.0 // Default: no adjustment until calibrated
        }
    }

    /**
     * Load a previously saved calibration profile.
     */
    fun loadProfile(saved: CalibrationProfile) {
        profile = saved
        accelNoiseMean = saved.accelNoiseFloor
        accelNoiseVar = saved.accelNoiseVariance
        gyroNoiseMean = saved.gyroNoiseFloor
        gyroNoiseVar = saved.gyroNoiseVariance
        sampleCount = saved.sampleCount
        isCalibrated = saved.sampleCount >= MIN_CALIBRATION_SAMPLES
        lastCalibrationTime = saved.lastCalibrationMs
        Log.i(TAG, "Loaded calibration profile: ${saved.deviceModel}, " +
                "accelNoise=${saved.accelNoiseFloor.format(4)}, " +
                "samples=${saved.sampleCount}")
    }

    /**
     * Reset calibration to defaults.
     */
    fun reset() {
        profile = CalibrationProfile()
        accelNoiseMean = 0.3
        accelNoiseVar = 0.01
        gyroNoiseMean = 0.05
        gyroNoiseVar = 0.001
        sampleCount = 0
        isCalibrated = false
        lastCalibrationTime = 0L
        bleRssiSum = 0.0
        bleRssiCount = 0
    }

    private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)
}
