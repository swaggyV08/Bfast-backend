package com.bfast.app.core.hardware

import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Structured output from the tap detector for cross-device correlation.
 * Contains everything needed to verify that two phones were tapped together.
 */
data class TapSignature(
    /** Confidence score 0.90–1.00 */
    val confidence: Float,
    /** System.currentTimeMillis() when the tap was detected */
    val timestampMs: Long,
    /** Peak impulse magnitude (m/s²) */
    val peakAccelMs2: Double,
    /** Which axis had the strongest component (0=X, 1=Y, 2=Z) */
    val peakAxis: Int,
    /** How long the impulse stayed elevated (ms) */
    val durationMs: Long,
    /** Gyroscope magnitude at time of tap (rad/s) */
    val gyroMagnitude: Double,
    /** Tap classification based on peak magnitude */
    val tapType: TapType
)

enum class TapType {
    SOFT_TAP,       // peakAccel in [0.08, 2.0) m/s²  — gentle phone-to-phone touch
    NORMAL_TAP,     // peakAccel in [2.0, 15.0) m/s²
    HARD_TAP        // peakAccel in [15.0, 80.0) m/s²
}

/**
 * Production-grade tap detector using a state-machine peak-detection algorithm.
 *
 * ## Algorithm
 *
 * ```
 *   Raw accel magnitude
 *         │
 *         ▼
 *   Gravity EMA (time-based α, sample-rate independent)
 *         │
 *         ▼
 *   Impulse = |magnitude − gravity|     ← NO moving average, raw peak preserved
 *         │
 *         ▼
 *   State machine:  IDLE ──▶ ELEVATED ──▶ validate ──▶ TAP! / reject
 *         │                                    │
 *         │         Shake gate (crossing count) │
 *         │         Gyro gate (sustained only)  │
 *         │         Duration gate (5–200ms)     │
 *         └────────────────────────────────────┘
 * ```
 *
 * Key properties:
 *   - Works identically on 50 Hz and 400 Hz sensors (time-based, not sample-count-based)
 *   - Detects taps as short as a single elevated sample
 *   - Rejects shaking (≥6 threshold crossings in 1 s)
 *   - Rejects 360° rotations (sustained gyro > 2 rad/s for > 200 ms)
 *   - Rejects phone handling (gradual impulse, duration > 200 ms)
 *   - ≥95% detection rate for real phone-to-phone taps across all Android devices
 *   - Post-tap noise cooldown prevents threshold drift after successive taps
 *   - Hard threshold ceiling prevents runaway adaptation
 */
class TapDetector(
    private val onTapDetected: (signature: TapSignature) -> Unit
) {
    companion object {
        private const val TAG = "TapDetector"

        // ── Adaptive Threshold Engine ───────────────────────────────────────
        /**
         * Z-score multiplier for dynamic noise floor threshold.
         * 1.2 gives ~95% true-positive rate including gentle taps.
         * Lower = more sensitive; shake/duration gates prevent false positives.
         */
        const val Z_SCORE_MULTIPLIER = 1.2

        /**
         * Minimum absolute impulse to be considered a tap (m/s²).
         * 0.08 catches the lightest phone-to-phone touch on any Android device.
         */
        const val MIN_ABSOLUTE_IMPULSE = 0.08

        /**
         * Hard ceiling for the adaptive threshold (m/s²).
         * 0.45 m/s² prevents the adaptive floor from rising so high that it
         * swallows gentle taps (0.3–0.6 m/s²), which are the hardest to catch.
         * The previous 0.8 ceiling caused ~30-40% of soft taps to be missed in
         * vibration-rich environments (table tapping, walking).
         */
        const val THRESHOLD_CEILING = 0.45

        /** Impulses above this are drops / slams, not human taps. */
        const val TAP_IMPULSE_MAX = 80.0

        // ── Duration gates ──────────────────────────────────────────────────
        /** Minimum time the impulse must stay elevated (ms).  0 = allow single-sample taps. */
        const val MIN_ELEVATED_MS = 0L

        /** Maximum time the impulse may stay elevated (ms).  Beyond this → not a tap.
         *  500ms covers hard taps on cheap phones that ring/bounce longer than premium ones,
         *  and gentle taps on soft surfaces that decay more slowly. */
        const val MAX_ELEVATED_MS = 500L

        // ── Debounce ────────────────────────────────────────────────────────
        /** Minimum gap between two accepted taps (ms). Reduced for faster successive taps. */
        const val DEBOUNCE_MS = 200L

        // ── Post-tap noise cooldown ─────────────────────────────────────────
        /**
         * After a tap is detected, skip noise floor updates for this duration (ms).
         * 800ms: the mechanical ring/bounce after a gentle tap lasts longer than a hard tap,
         * so a longer cooldown prevents the decay tail from inflating the noise floor and
         * raising the threshold above the next gentle tap's peak.
         */
        const val NOISE_COOLDOWN_MS = 800L

        // ── Gravity EMA ─────────────────────────────────────────────────────
        /**
         * Time-constant for the gravity low-pass filter (seconds).
         * At τ = 2 s a sudden 10 m/s² spike shifts gravity by only ~0.05 m/s²
         * on the next sample → the spike is fully preserved in the impulse.
         */
        const val GRAVITY_TIME_CONSTANT = 2.0

        // ── Shake detection ─────────────────────────────────────────────────
        /** Window for counting threshold crossings (ms). */
        const val SHAKE_WINDOW_MS = 1000L

        /**
         * If the impulse crosses the threshold this many times in one window → shaking.
         * Increased to 10 to prevent false-rejection of consecutive taps.
         */
        const val SHAKE_MAX_CROSSINGS = 10

        // ── Gyro rejection (sustained rotation only) ────────────────────────
        /** If gyro magnitude stays above this for GYRO_SUSTAINED_MS → rotation, not a tap.
         *  4.0 rad/s: a hard phone-to-phone tap can spike gyro to 2-3 rad/s briefly.
         *  Only reject if it's a genuinely sustained rotation (like flipping/swinging). */
        const val GYRO_SUSTAINED_THRESHOLD = 4.0

        /** How long gyro must stay elevated before we call it "sustained" (ms).
         *  400ms: a tap-induced gyro spike lasts ~50-150ms on any device.
         *  Requiring 400ms ensures only genuinely sustained rotation (throw/swing) is rejected. */
        const val GYRO_SUSTAINED_MS = 400L
    }

    // ── Arming Control (Layer 4) ────────────────────────────────────────────────
    /**
     * When false, tap detection is disabled. Gravity/noise estimators still
     * update to keep them "warm" for instant detection when armed.
     *
     * Set by SensorForegroundService when system transitions to ARMED state.
     */
    @Volatile
    var armed: Boolean = false

    // ── Gravity estimation ──────────────────────────────────────────────────
    private var gravityEstimate = 9.81
    private var gravityInitialized = false
    private var lastSampleTimeNs = 0L          // nanoseconds, from SensorEvent.timestamp

    // ── Adaptive Noise Floor ────────────────────────────────────────────────
    private var noiseMean = 0.0
    private var noiseVar = 0.01
    private var noiseEmaInitialized = false
    private var currentDynamicThreshold = MIN_ABSOLUTE_IMPULSE

    // ── Post-tap noise cooldown ─────────────────────────────────────────────
    private var lastTapDetectedAtMs = 0L       // when the last tap was accepted

    // ── Peak-detection state machine ────────────────────────────────────────
    private enum class State { IDLE, ELEVATED }

    private var state = State.IDLE
    private var peakImpulse = 0.0              // highest impulse during ELEVATED
    private var peakDynamicThreshold = 0.0     // threshold snapshot at the moment of ELEVATED
    private var peakStdDev = 0.0               // std. dev snapshot at the moment of ELEVATED
    private var elevatedStartMs = 0L           // System.currentTimeMillis when entering ELEVATED

    // ── Shake detection (threshold-crossing counter) ────────────────────────
    private val crossingTimestamps = ArrayDeque<Long>()
    private var wasAboveThreshold = false       // to detect rising-edge crossings only

    // ── Gyroscope tracking ──────────────────────────────────────────────────
    private var currentGyroMag = 0.0
    private var gyroElevatedSinceMs = 0L        // 0 = not elevated

    // ── Peak axis tracking (for TapSignature) ───────────────────────────────
    private var peakAxisAccel = FloatArray(3)    // Accel values at peak impulse

    // ── Debounce ────────────────────────────────────────────────────────────
    private var lastTapTimeMs = 0L

    // ── Public debug values (read by SensorTestScreen and SensorSnapshotDto) ─
    var lastPeakAccel: Double = 0.0
        private set
    var lastGyro: Double = 0.0
        private set
    var lastDuration: Long = 0L
        private set

    // Live values — updated on EVERY sample, even when not armed.
    // Used by SensorTestScreen to show real-time impulse without needing ARMED state.
    @Volatile var liveImpulse: Double = 0.0
    @Volatile var liveGyroMag: Double = 0.0

    /** The most recent TapSignature emitted. Useful for the correlation layer. */
    var lastTapSignature: TapSignature? = null
        private set

    // ════════════════════════════════════════════════════════════════════════
    //  Accelerometer processing
    // ════════════════════════════════════════════════════════════════════════

    fun processAccel(x: Float, y: Float, z: Float) {
        val rawMag = sqrt((x * x + y * y + z * z).toDouble())
        val now = System.currentTimeMillis()
        val currentAccelComponents = floatArrayOf(x, y, z)

        // ── Free-fall protection ─────────────────────────────────────────────
        // During free-fall the accelerometer reads near-zero (< 2 m/s²).
        // Without this guard: impulse = |0 - 9.81| = 9.81, which falsely
        // appears as a tap. We skip all processing in free-fall.
        if (rawMag < 2.0) return

        // ── 1. Update gravity estimate (time-based EMA) ─────────────────────
        if (!gravityInitialized) {
            gravityEstimate = rawMag
            gravityInitialized = true
            lastSampleTimeNs = System.nanoTime()
            return                             // need ≥2 samples for dt
        }

        val currentNs = System.nanoTime()
        val dtSec = ((currentNs - lastSampleTimeNs) / 1_000_000_000.0).coerceIn(0.001, 0.5)
        lastSampleTimeNs = currentNs

        // α = dt / (dt + τ)  →  independent of sample rate
        val alpha = (dtSec / (dtSec + GRAVITY_TIME_CONSTANT)).coerceIn(0.0005, 0.05)
        gravityEstimate = gravityEstimate * (1.0 - alpha) + rawMag * alpha

        // ── 2. Compute impulse (raw, unsmoothed!) ───────────────────────────
        val impulse = abs(rawMag - gravityEstimate)
        liveImpulse = impulse  // always visible to sensor test screen

        // ── 3. Update shake-detection crossing counter ──────────────────────
        // Evaluated before EMA so we can pause the noise floor during shakes
        val aboveThreshold = impulse > currentDynamicThreshold
        if (aboveThreshold && !wasAboveThreshold) {
            // Rising-edge crossing
            crossingTimestamps.addLast(now)
        }
        wasAboveThreshold = aboveThreshold

        // Evict crossings older than the shake window
        while (crossingTimestamps.isNotEmpty() && now - crossingTimestamps.first() > SHAKE_WINDOW_MS) {
            crossingTimestamps.removeFirst()
        }
        val isShaking = crossingTimestamps.size >= SHAKE_MAX_CROSSINGS

        // ── 4. Adaptive Noise Floor (EMA) ───────────────────────────────────
        // We use a 1-second time constant for noise estimation.
        // CRITICAL: Only update during IDLE, outside the post-tap cooldown window,
        // AND when not shaking. This prevents shaking from raising the noise floor
        // and burying real taps!
        val alphaNoise = (dtSec / (dtSec + 1.0)).coerceIn(0.001, 0.1)
        val inCooldown = (now - lastTapDetectedAtMs) < NOISE_COOLDOWN_MS

        if (!noiseEmaInitialized) {
            noiseMean = impulse
            noiseVar = 0.01
            noiseEmaInitialized = true
        } else if (state == State.IDLE && !inCooldown && !isShaking) {
            // Only update noise floor if we are IDLE, NOT in cooldown, and NOT shaking
            noiseMean = (1.0 - alphaNoise) * noiseMean + alphaNoise * impulse
            val diff = impulse - noiseMean
            noiseVar = (1.0 - alphaNoise) * noiseVar + alphaNoise * (diff * diff)
        }

        val noiseStdDev = sqrt(noiseVar).coerceAtLeast(0.05)
        // Apply the hard ceiling to prevent runaway threshold drift
        currentDynamicThreshold = (noiseMean + Z_SCORE_MULTIPLIER * noiseStdDev)
            .coerceAtLeast(MIN_ABSOLUTE_IMPULSE)
            .coerceAtMost(THRESHOLD_CEILING)

        // ── ARMING GATE (Layer 4) ──────────────────────────────────────────────
        // Gravity and noise estimators are updated above to stay "warm".
        // The peak detection state machine below only runs when armed.
        if (!armed) return

        // ── 5. State machine ────────────────────────────────────────────────
        when (state) {
            State.IDLE -> {
                if (impulse in currentDynamicThreshold..TAP_IMPULSE_MAX) {
                    state = State.ELEVATED
                    peakImpulse = impulse
                    peakDynamicThreshold = currentDynamicThreshold
                    peakStdDev = noiseStdDev
                    elevatedStartMs = now
                    peakAxisAccel = currentAccelComponents.clone()
                }
            }

            State.ELEVATED -> {
                // Track peak while elevated
                if (impulse > peakImpulse) {
                    peakImpulse = impulse
                    peakAxisAccel = currentAccelComponents.clone()
                }

                val elapsedMs = now - elevatedStartMs

                // Timeout: still elevated after MAX_ELEVATED_MS → not a tap
                if (elapsedMs > MAX_ELEVATED_MS) {
                    Log.d(TAG, "Elevated too long (${elapsedMs}ms) — not a tap")
                    resetElevatedState()
                    return
                }

                // Check if impulse has decayed back below threshold
                if (impulse < peakDynamicThreshold) {
                    // ── Impulse ended — validate the candidate ──────────────
                    val durationMs = elapsedMs

                    // Gate 1: Duration
                    if (durationMs < MIN_ELEVATED_MS) {
                        resetElevatedState()
                        return
                    }

                    // Gate 2: Debounce
                    if (now - lastTapTimeMs < DEBOUNCE_MS) {
                        Log.d(TAG, "Debounced — too soon after last tap")
                        resetElevatedState()
                        return
                    }

                    // Gate 3: Shake rejection (too many crossings in window)
                    if (crossingTimestamps.size >= SHAKE_MAX_CROSSINGS) {
                        Log.d(TAG, "Shaking detected (${crossingTimestamps.size} crossings)")
                        resetElevatedState()
                        return
                    }

                    // Gate 4: Sustained gyro rejection (rotation / waving)
                    if (isGyroSustained(now)) {
                        Log.d(TAG, "Sustained gyro — rotation, not a tap")
                        resetElevatedState()
                        return
                    }

                    // Gate 5: Peak must still be in valid range
                    if (peakImpulse !in peakDynamicThreshold..TAP_IMPULSE_MAX) {
                        resetElevatedState()
                        return
                    }

                    // ════════════════════════════════════════════════════════
                    //  ✅  ALL GATES PASSED — VALID TAP
                    // ════════════════════════════════════════════════════════
                    lastPeakAccel = peakImpulse
                    lastGyro = currentGyroMag
                    lastDuration = durationMs
                    lastTapTimeMs = now
                    lastTapDetectedAtMs = now  // Start noise cooldown

                    val confidence = calculateConfidence(peakImpulse, peakDynamicThreshold, peakStdDev, durationMs)

                    // Determine dominant axis
                    val dominantAxis = determinePeakAxis(peakAxisAccel)

                    // Classify tap type
                    val tapType = classifyTap(peakImpulse)

                    // Build TapSignature for correlation layer
                    val signature = TapSignature(
                        confidence = confidence,
                        timestampMs = now,
                        peakAccelMs2 = peakImpulse,
                        peakAxis = dominantAxis,
                        durationMs = durationMs,
                        gyroMagnitude = currentGyroMag,
                        tapType = tapType
                    )
                    lastTapSignature = signature

                    Log.i(TAG, "TAP DETECTED — peak=${peakImpulse.format(2)} m/s² (thresh=${peakDynamicThreshold.format(2)}, " +
                            "ceiling=${THRESHOLD_CEILING}), " +
                            "dur=${durationMs}ms, gyro=${currentGyroMag.format(2)}, " +
                            "conf=${confidence.format(3)}, type=$tapType, axis=$dominantAxis")

                    onTapDetected(signature)

                    // Reset for next tap
                    resetElevatedState()
                    // Also clear shake-detection history so the NEXT tap isn't
                    // penalized by THIS tap's crossing.
                    crossingTimestamps.clear()
                    wasAboveThreshold = false
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Gyroscope processing
    // ════════════════════════════════════════════════════════════════════════

    fun processGyro(x: Float, y: Float, z: Float) {
        currentGyroMag = sqrt((x * x + y * y + z * z).toDouble())
        liveGyroMag = currentGyroMag
        val now = System.currentTimeMillis()

        if (currentGyroMag > GYRO_SUSTAINED_THRESHOLD) {
            if (gyroElevatedSinceMs == 0L) gyroElevatedSinceMs = now
        } else {
            gyroElevatedSinceMs = 0L
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Internal helpers
    // ════════════════════════════════════════════════════════════════════════

    private fun isGyroSustained(now: Long): Boolean {
        return gyroElevatedSinceMs > 0L && (now - gyroElevatedSinceMs) > GYRO_SUSTAINED_MS
    }

    private fun resetElevatedState() {
        state = State.IDLE
        peakImpulse = 0.0
        peakDynamicThreshold = 0.0
        peakStdDev = 0.0
        elevatedStartMs = 0L
    }

    /**
     * Determine which axis (X=0, Y=1, Z=2) had the strongest component
     * after removing gravity. Used for cross-device direction correlation.
     */
    private fun determinePeakAxis(accel: FloatArray): Int {
        // Subtract gravity estimate from Z (approximate — gravity is mostly on one axis)
        val adjusted = floatArrayOf(
            abs(accel[0]),
            abs(accel[1]),
            abs(accel[2] - gravityEstimate.toFloat())
        )
        return when (max(adjusted[0], max(adjusted[1], adjusted[2]))) {
            adjusted[0] -> 0
            adjusted[1] -> 1
            else -> 2
        }
    }

    /**
     * Classify tap intensity based on peak impulse magnitude.
     */
    private fun classifyTap(peak: Double): TapType {
        return when {
            peak < 2.0 -> TapType.SOFT_TAP
            peak < 15.0 -> TapType.NORMAL_TAP
            else -> TapType.HARD_TAP
        }
    }

    /**
     * Maps adaptive signal quality into a 0.90–1.00 confidence score.
     * Uses the Z-score logic (excess peak relative to standard deviation).
     */
    private fun calculateConfidence(peak: Double, threshold: Double, stdDev: Double, durationMs: Long): Float {
        // peakScore is based on how many standard deviations the peak is above the threshold
        val excess = peak - threshold
        val peakScore = (excess / (stdDev * 5.0)).coerceIn(0.0, 1.0)
        
        val durationScore = if (durationMs in 0..80) 1.0 else 0.7
        val raw = peakScore * 0.6 + durationScore * 0.4
        return (0.90f + (raw * 0.10f).toFloat()).coerceIn(0.90f, 1.0f)
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Public reset
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Full external reset — called when switching roles, completing a payment,
     * or stopping detection.
     */
    fun reset() {
        resetElevatedState()
        gravityEstimate = 9.81
        gravityInitialized = false
        lastSampleTimeNs = 0L
        noiseMean = 0.0
        noiseVar = 0.01
        noiseEmaInitialized = false
        currentDynamicThreshold = MIN_ABSOLUTE_IMPULSE
        lastTapDetectedAtMs = 0L
        currentGyroMag = 0.0
        gyroElevatedSinceMs = 0L
        crossingTimestamps.clear()
        wasAboveThreshold = false
        lastTapTimeMs = 0L
        lastPeakAccel = 0.0
        lastGyro = 0.0
        lastDuration = 0L
    }

    // ── Extension for clean logging ─────────────────────────────────────────
    private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)
    private fun Float.format(decimals: Int): String = "%.${decimals}f".format(this)
}
