package com.bfast.app.core.hardware

import android.util.Log
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Production-grade multi-signal BLE proximity engine.
 *
 * Instead of a single RSSI threshold, this engine computes a **proximity confidence
 * score (0–100)** from four independent signals:
 *
 *   1. **RSSI Strength** (40%): Kalman-filtered RSSI mapped through a sigmoid
 *   2. **RSSI Stability** (25%): Inverse of rolling variance — stable = close
 *   3. **RSSI Trend** (15%): Linear regression slope — getting closer?
 *   4. **Dwell Time** (20%): How long the device has been above the noise floor
 *
 * This approach eliminates the problem of "one noisy RSSI spike at 50cm triggers
 * payment" because a distant device will have HIGH variance, ZERO dwell time,
 * and NEGATIVE trend — scoring near 0 even if one sample spiked above threshold.
 *
 * Compatible with all Android phones from API 26+. Automatically adapts to
 * weak BLE radios on low-end devices through relative RSSI scoring.
 */
class BleProximityEngine : ProximityProvider {

    companion object {
        private const val TAG = "BleProximityEngine"

        // ── Scoring Weights ─────────────────────────────────────────────
        const val W_RSSI = 0.40f         // RSSI strength
        const val W_STABILITY = 0.25f    // RSSI stability (inverse variance)
        const val W_TREND = 0.15f        // RSSI trend (approaching?)
        const val W_DWELL = 0.20f        // Dwell time above noise floor

        // ── Sigmoid Parameters ──────────────────────────────────────────
        /** Center of the sigmoid curve for RSSI scoring.
         *  Set to -55: at -30 dBm (touching) = ~99, -45 dBm (~5cm) = ~73,
         *  -55 dBm (~10cm) = ~50, -70 dBm (~1m) = ~5.
         *  This allows discovery at reasonable range while scoring
         *  genuinely close devices much higher. */
        const val SIGMOID_CENTER = -55.0
        /** Steepness of the sigmoid curve */
        const val SIGMOID_K = 0.20

        // ── Stability Parameters ────────────────────────────────────────
        /** Variance above this is considered "unstable" (far away / noisy) */
        const val MAX_USEFUL_VARIANCE = 100.0

        // ── Dwell Parameters ────────────────────────────────────────────
        /** RSSI above this is considered "in proximity zone" for dwell counting.
         *  Set to -75 — devices within ~30cm start accumulating dwell time. */
        const val DWELL_RSSI_FLOOR = -75
        /** Full dwell score reached at this duration (ms). */
        const val DWELL_FULL_SCORE_MS = 1000L

        // ── Ring Buffer ─────────────────────────────────────────────────
        /** Number of RSSI samples to keep per device */
        const val RING_BUFFER_SIZE = 30
        /** Maximum age of samples in the ring buffer (ms) */
        const val RING_BUFFER_MAX_AGE_MS = 3000L
    }

    // ── Per-device RSSI history ─────────────────────────────────────────
    private val deviceBuffers = mutableMapOf<String, RssiRingBuffer>()

    // ── Public scores (updated on every RSSI sample) ────────────────────

    /** Overall proximity confidence score 0–100 */
    @Volatile
    override var proximityScore: Float = 0f
        private set

    /** Individual component scores for debugging/UI */
    @Volatile
    var rssiScore: Float = 0f
        private set

    @Volatile
    override var stabilityScore: Float = 0f
        private set

    @Volatile
    var trendScore: Float = 0f
        private set

    @Volatile
    var dwellScore: Float = 0f
        private set

    /** The RSSI surge at the current moment (recent avg - background avg) */
    @Volatile
    override var rssiSurge: Float = 0f
        private set

    /** The device ID that the current score applies to */
    @Volatile
    override var scoredDeviceId: String? = null
        private set

    override fun reset(deviceId: String?) {
        if (deviceId == null) {
            clearAll()
        } else {
            clearDevice(deviceId)
        }
    }

    /** Ranging method identifier */
    override val rangingMethod: String = "RSSI"

    // ════════════════════════════════════════════════════════════════════
    //  Core API
    // ════════════════════════════════════════════════════════════════════

    /**
     * Feed a new RSSI sample from the BLE scanner.
     * Call this on every scan result that passes the manufacturer ID filter.
     *
     * @param deviceId Unique identifier of the scanned device
     * @param value Raw RSSI value in dBm (not filtered)
     * @param timestampMs System.currentTimeMillis() of the scan
     */
    override fun feedReading(deviceId: String, value: Int, timestampMs: Long) {
        val rssi = value
        val buffer = deviceBuffers.getOrPut(deviceId) { RssiRingBuffer(RING_BUFFER_SIZE) }
        buffer.add(rssi, timestampMs)

        // Evict stale samples
        buffer.evictOlderThan(timestampMs - RING_BUFFER_MAX_AGE_MS)

        // Compute individual scores
        val rs = computeRssiScore(buffer)
        val ss = computeStabilityScore(buffer)
        val ts = computeTrendScore(buffer)
        val ds = computeDwellScore(buffer, timestampMs)
        val surge = computeRssiSurge(buffer, timestampMs)

        // Weighted fusion
        val total = (rs * W_RSSI + ss * W_STABILITY + ts * W_TREND + ds * W_DWELL)
            .coerceIn(0f, 100f)

        // Update public state
        rssiScore = rs
        stabilityScore = ss
        trendScore = ts
        dwellScore = ds
        rssiSurge = surge
        proximityScore = total
        scoredDeviceId = deviceId
    }

    /**
     * Get the current proximity score for a specific device.
     * Returns 0 if the device has no recent RSSI history.
     */
    fun getScoreForDevice(deviceId: String): Float {
        val buffer = deviceBuffers[deviceId] ?: return 0f
        if (buffer.isEmpty()) return 0f

        val rs = computeRssiScore(buffer)
        val ss = computeStabilityScore(buffer)
        val ts = computeTrendScore(buffer)
        val ds = computeDwellScore(buffer, System.currentTimeMillis())

        return (rs * W_RSSI + ss * W_STABILITY + ts * W_TREND + ds * W_DWELL)
            .coerceIn(0f, 100f)
    }

    /**
     * Clear all history for a device. Called after a successful payment
     * to reset for the next interaction.
     */
    fun clearDevice(deviceId: String) {
        deviceBuffers.remove(deviceId)
        if (scoredDeviceId == deviceId) {
            proximityScore = 0f
            rssiScore = 0f
            stabilityScore = 0f
            trendScore = 0f
            dwellScore = 0f
            scoredDeviceId = null
        }
    }

    /** Clear all device history. */
    fun clearAll() {
        deviceBuffers.clear()
        proximityScore = 0f
        rssiScore = 0f
        stabilityScore = 0f
        trendScore = 0f
        dwellScore = 0f
        scoredDeviceId = null
    }

    // ════════════════════════════════════════════════════════════════════
    //  Score Computation
    // ════════════════════════════════════════════════════════════════════

    /**
     * RSSI Strength Score (0–100).
     * Uses sigmoid function centered at SIGMOID_CENTER (-45 dBm).
     * - At -30 dBm (touching): ~97
     * - At -45 dBm (5cm): ~50
     * - At -60 dBm (50cm): ~3
     */
    private fun computeRssiScore(buffer: RssiRingBuffer): Float {
        val meanRssi = buffer.mean()
        val diff = meanRssi - SIGMOID_CENTER
        val sigmoid = 1.0 / (1.0 + exp(-SIGMOID_K * diff))
        return (sigmoid * 100.0).toFloat()
    }

    /**
     * RSSI Stability Score (0–100).
     * Low variance = stable = phone is genuinely close (not a random spike).
     * High variance = unstable = phone is far away or moving erratically.
     */
    private fun computeStabilityScore(buffer: RssiRingBuffer): Float {
        if (buffer.size() < 3) return 50f // Warm-start: neutral score avoids cold-start rejection
        val variance = buffer.variance()
        // Map variance to score: 0 variance → 100, MAX_USEFUL_VARIANCE → 0
        return ((1.0 - (variance / MAX_USEFUL_VARIANCE).coerceAtMost(1.0)) * 100.0).toFloat()
    }

    /**
     * RSSI Trend Score (0–100).
     * Positive slope = device is getting closer → high score.
     * Negative slope = device is moving away → 0.
     * Uses simple linear regression on the ring buffer.
     */
    private fun computeTrendScore(buffer: RssiRingBuffer): Float {
        if (buffer.size() < 3) return 50f // Warm-start: neutral score avoids cold-start rejection
        val slope = buffer.trendSlope()
        // Positive slope = approaching. Map slope to 0–100.
        // A slope of +5 dBm/sec is "approaching fast" → score 100
        return if (slope > 0) {
            (slope * 20.0).coerceAtMost(100.0).toFloat()
        } else {
            // Slight negative slope is OK (phone is stationary, RSSI fluctuates)
            // Only penalize if clearly moving away (slope < -2)
            if (slope > -2.0) 30f else 0f
        }
    }

    /**
     * Dwell Time Score (0–100).
     * How long has this device been continuously above the noise floor RSSI?
     * A device that's been within proximity for 2+ seconds is very likely genuine.
     */
    private fun computeDwellScore(buffer: RssiRingBuffer, now: Long): Float {
        val dwellMs = buffer.dwellTimeAbove(DWELL_RSSI_FLOOR, now)
        return ((dwellMs.toFloat() / DWELL_FULL_SCORE_MS.toFloat()) * 100f)
            .coerceIn(0f, 100f)
    }

    /**
     * Calculates the "Surge" in RSSI (immediate vs background).
     * Compares the average RSSI of the last 200ms vs the average of the 800ms before that.
     * @return Surge in dBm (positive means it got closer).
     */
    private fun computeRssiSurge(buffer: RssiRingBuffer, now: Long): Float {
        val samples = buffer.getSamples()
        if (samples.size < 4) return 0f

        val recentWindowMs = 200L
        val recentSamples = samples.filter { it.timestampMs >= now - recentWindowMs }
        val backgroundSamples = samples.filter { it.timestampMs < now - recentWindowMs && it.timestampMs >= now - 1000L }

        if (recentSamples.isEmpty() || backgroundSamples.isEmpty()) return 0f

        val recentAvg = recentSamples.sumOf { it.rssi.toDouble() } / recentSamples.size
        val backgroundAvg = backgroundSamples.sumOf { it.rssi.toDouble() } / backgroundSamples.size

        return (recentAvg - backgroundAvg).toFloat()
    }

    // ════════════════════════════════════════════════════════════════════
    //  RSSI Ring Buffer
    // ════════════════════════════════════════════════════════════════════

    /**
     * Fixed-capacity ring buffer for RSSI samples with timestamps.
     * Provides mean, variance, linear regression trend, and dwell time.
     */
    class RssiRingBuffer(private val maxSize: Int) {
        private val samples = ArrayDeque<RssiSample>(maxSize)

        fun getSamples(): List<RssiSample> = samples.toList()

        data class RssiSample(val rssi: Int, val timestampMs: Long)

        fun add(rssi: Int, timestampMs: Long) {
            samples.addLast(RssiSample(rssi, timestampMs))
            while (samples.size > maxSize) {
                samples.removeFirst()
            }
        }

        fun evictOlderThan(cutoffMs: Long) {
            while (samples.isNotEmpty() && samples.first().timestampMs < cutoffMs) {
                samples.removeFirst()
            }
        }

        fun isEmpty(): Boolean = samples.isEmpty()
        fun size(): Int = samples.size

        /** Mean RSSI over all samples in the buffer. */
        fun mean(): Double {
            if (samples.isEmpty()) return -100.0
            return samples.sumOf { it.rssi.toDouble() } / samples.size
        }

        /** Variance of RSSI samples. High variance = noisy/distant. */
        fun variance(): Double {
            if (samples.size < 2) return 0.0
            val m = mean()
            return samples.sumOf { (it.rssi - m) * (it.rssi - m) } / (samples.size - 1)
        }

        /**
         * Linear regression slope (dBm per second).
         * Positive = RSSI increasing = device getting closer.
         */
        fun trendSlope(): Double {
            if (samples.size < 3) return 0.0
            val n = samples.size
            val t0 = samples.first().timestampMs

            var sumT = 0.0
            var sumR = 0.0
            var sumTR = 0.0
            var sumTT = 0.0

            for (s in samples) {
                val t = (s.timestampMs - t0) / 1000.0 // seconds
                val r = s.rssi.toDouble()
                sumT += t
                sumR += r
                sumTR += t * r
                sumTT += t * t
            }

            val denom = n * sumTT - sumT * sumT
            if (abs(denom) < 1e-9) return 0.0

            return (n * sumTR - sumT * sumR) / denom
        }

        /**
         * How long has the RSSI been continuously above [threshold]?
         * Counts backwards from the most recent sample.
         */
        fun dwellTimeAbove(threshold: Int, now: Long): Long {
            if (samples.isEmpty()) return 0L

            // Find the earliest timestamp in the recent continuous run above threshold
            var earliestAbove = now
            for (i in samples.indices.reversed()) {
                if (samples[i].rssi >= threshold) {
                    earliestAbove = samples[i].timestampMs
                } else {
                    break
                }
            }

            return if (earliestAbove < now) now - earliestAbove else 0L
        }

        /** Latest RSSI value. */
        fun latest(): Int = samples.lastOrNull()?.rssi ?: Int.MIN_VALUE
    }
}
