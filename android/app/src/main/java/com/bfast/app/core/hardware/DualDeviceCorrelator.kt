package com.bfast.app.core.hardware

import android.util.Log
import kotlin.math.abs
import kotlin.math.ln

/**
 * Cross-device tap correlation engine (Layer 9).
 *
 * When Device A detects a tap, it broadcasts its [TapSignature] via BLE ("B" command).
 * Device B receives this and compares it with its own recent taps using three signals:
 *
 *   1. **Time Delta** (50%): Both taps must occur within 150ms of each other
 *   2. **Magnitude Ratio** (30%): Peak accelerations should be within 0.2x–5.0x
 *   3. **Duration Similarity** (20%): Impulse durations should be within 100ms
 *
 * This is the **strongest false-positive prevention mechanism** because it's
 * physically impossible for a phone at 150cm to produce a correlated tap signal
 * with the same timing, magnitude, and duration as a phone that was physically
 * tapped against another.
 *
 * The ≤150ms window ensures both devices must detect the SAME physical event.
 */
class DualDeviceCorrelator {

    companion object {
        private const val TAG = "DualDeviceCorrelator"

        /** Maximum time difference between two correlated taps (ms).
         *  Tightened to 150ms — both devices must detect the SAME physical impact. */
        const val MAX_TIME_DELTA_MS = 150L

        /** Magnitude ratio range for valid correlation (0.2x to 5.0x). */
        const val MIN_MAGNITUDE_RATIO = 0.2
        const val MAX_MAGNITUDE_RATIO = 5.0

        /** Maximum duration difference for valid correlation (ms). */
        const val MAX_DURATION_DELTA_MS = 100L

        // ── Scoring Weights ─────────────────────────────────────────────
        const val W_TIME = 0.50f
        const val W_MAGNITUDE = 0.30f
        const val W_DURATION = 0.20f

        /** Minimum correlation score to consider the taps as matched. */
        const val MIN_CORRELATION_SCORE = 40.0

        /** How long to keep local tap signatures for potential correlation (ms). */
        const val LOCAL_TAP_BUFFER_DURATION_MS = 2000L
    }

    /**
     * Result of correlating two tap signatures.
     */
    data class CorrelationResult(
        /** Whether the taps are considered a match. */
        val matched: Boolean,
        /** Time difference between the two taps (ms). */
        val timeDeltaMs: Long,
        /** Ratio of local peak / remote peak. */
        val magnitudeRatio: Double,
        /** Correlation score 0–100. */
        val correlationScore: Double
    )

    /**
     * Remote tap data received via BLE "B" command.
     * Compact representation that fits in BLE advertising payload.
     */
    data class RemoteTapData(
        val deviceId: String,
        /** Peak acceleration × 100 (integer, e.g., 1250 = 12.50 m/s²) */
        val peakAccelX100: Int,
        /** Impulse duration in ms */
        val durationMs: Int,
        /** Last 4 digits of timestamp (for 500ms correlation window) */
        val timestampLast4: Int,
        /** Full receive timestamp on this device */
        val receivedAtMs: Long = System.currentTimeMillis()
    )

    // ── Local tap history ───────────────────────────────────────────────
    private val localTapBuffer = mutableListOf<TapSignature>()

    // ── Remote tap history ──────────────────────────────────────────────
    private val remoteTapBuffer = mutableListOf<RemoteTapData>()

    // ── Last correlation result ─────────────────────────────────────────
    @Volatile
    var lastCorrelationResult: CorrelationResult? = null
        private set

    @Volatile
    var lastCorrelationScore: Float = 0f
        private set

    // ════════════════════════════════════════════════════════════════════
    //  Core API
    // ════════════════════════════════════════════════════════════════════

    /**
     * Record a local tap signature. Called by SensorForegroundService
     * when the TapDetector fires.
     *
     * @return A correlation result if a matching remote tap was already received,
     *         or null if no remote tap is available yet.
     */
    fun recordLocalTap(signature: TapSignature): CorrelationResult? {
        evictStale()
        localTapBuffer.add(signature)

        // Try to correlate with any recent remote taps
        for (remote in remoteTapBuffer) {
            val result = correlate(signature, remote)
            if (result.matched) {
                lastCorrelationResult = result
                lastCorrelationScore = result.correlationScore.toFloat()
                Log.i(TAG, "LOCAL→REMOTE correlation MATCHED: " +
                        "timeDelta=${result.timeDeltaMs}ms, " +
                        "magRatio=${result.magnitudeRatio}, " +
                        "score=${result.correlationScore}")
                return result
            }
        }

        return null
    }

    /**
     * Record a remote tap received via BLE "B" command.
     *
     * @return A correlation result if a matching local tap was already detected,
     *         or null if no local tap is available yet.
     */
    fun recordRemoteTap(remote: RemoteTapData): CorrelationResult? {
        evictStale()
        remoteTapBuffer.add(remote)

        // Try to correlate with any recent local taps
        for (local in localTapBuffer) {
            val result = correlate(local, remote)
            if (result.matched) {
                lastCorrelationResult = result
                lastCorrelationScore = result.correlationScore.toFloat()
                Log.i(TAG, "REMOTE→LOCAL correlation MATCHED: " +
                        "timeDelta=${result.timeDeltaMs}ms, " +
                        "magRatio=${result.magnitudeRatio}, " +
                        "score=${result.correlationScore}")
                return result
            }
        }

        return null
    }

    /**
     * Get the best correlation score from recent history.
     * Returns 0 if no correlation has been established.
     */
    fun getCurrentCorrelationScore(): Float = lastCorrelationScore

    /**
     * Clear all buffers. Called after a successful payment or role switch.
     */
    fun reset() {
        localTapBuffer.clear()
        remoteTapBuffer.clear()
        lastCorrelationResult = null
        lastCorrelationScore = 0f
    }

    // ════════════════════════════════════════════════════════════════════
    //  Correlation Algorithm
    // ════════════════════════════════════════════════════════════════════

    /**
     * Correlate a local TapSignature with a remote tap received via BLE.
     *
     * Uses three independent signals:
     * 1. Time alignment (50%) — taps must be within 500ms
     * 2. Magnitude similarity (30%) — peaks should be within 0.2x–5.0x ratio
     * 3. Duration similarity (20%) — impulse durations within 100ms
     */
    private fun correlate(local: TapSignature, remote: RemoteTapData): CorrelationResult {
        // Time correlation using last 4 digits of timestamp
        val localLast4 = (local.timestampMs % 10000).toInt()
        var timeDelta = abs(localLast4 - remote.timestampLast4).toLong()
        // Handle wraparound (e.g., local=9990, remote=0010 → delta should be 20, not 9980)
        if (timeDelta > 5000) timeDelta = 10000 - timeDelta

        // Time score: 100 at 0ms, linearly drops to 0 at MAX_TIME_DELTA_MS
        val timeScore = if (timeDelta <= MAX_TIME_DELTA_MS) {
            (1.0 - timeDelta.toDouble() / MAX_TIME_DELTA_MS) * 100.0
        } else {
            0.0
        }

        // Magnitude correlation
        val remotePeakAccel = remote.peakAccelX100 / 100.0
        val magRatio = if (remotePeakAccel > 0.01) {
            local.peakAccelMs2 / remotePeakAccel
        } else {
            99.0 // Invalid remote data
        }

        val magScore = if (magRatio in MIN_MAGNITUDE_RATIO..MAX_MAGNITUDE_RATIO) {
            // Score is highest when ratio is close to 1.0
            val logRatio = abs(ln(magRatio))
            val logMax = ln(MAX_MAGNITUDE_RATIO)
            (1.0 - logRatio / logMax).coerceAtLeast(0.0) * 100.0
        } else {
            0.0
        }

        // Duration correlation
        val durDelta = abs(local.durationMs - remote.durationMs)
        val durScore = if (durDelta <= MAX_DURATION_DELTA_MS) {
            (1.0 - durDelta.toDouble() / MAX_DURATION_DELTA_MS) * 100.0
        } else {
            0.0
        }

        // Weighted fusion
        val totalScore = timeScore * W_TIME + magScore * W_MAGNITUDE + durScore * W_DURATION

        val matched = totalScore >= MIN_CORRELATION_SCORE && timeDelta <= MAX_TIME_DELTA_MS

        return CorrelationResult(
            matched = matched,
            timeDeltaMs = timeDelta,
            magnitudeRatio = magRatio,
            correlationScore = totalScore
        )
    }

    // ════════════════════════════════════════════════════════════════════
    //  BLE Payload Encoding/Decoding
    // ════════════════════════════════════════════════════════════════════

    /**
     * Encode a TapSignature into a compact BLE payload string.
     * Format: "B|<peakX100>|<durationMs>|<tsLast4>|<deviceId>"
     * Must fit within 31-byte BLE advertising limit.
     */
    fun encodeTapForBle(signature: TapSignature, deviceId: String): String {
        val peakX100 = (signature.peakAccelMs2 * 100).toInt().coerceIn(0, 9999)
        val durMs = signature.durationMs.toInt().coerceIn(0, 999)
        val tsLast4 = (signature.timestampMs % 10000).toInt()
        return "B|$peakX100|$durMs|$tsLast4|${deviceId.take(10)}"
    }

    /**
     * Decode a BLE payload string into a RemoteTapData.
     * Returns null if the payload is malformed.
     */
    fun decodeTapFromBle(payload: String): RemoteTapData? {
        try {
            val parts = payload.split("|")
            if (parts.size < 5 || parts[0] != "B") return null
            return RemoteTapData(
                deviceId = parts[4],
                peakAccelX100 = parts[1].toIntOrNull() ?: return null,
                durationMs = parts[2].toIntOrNull() ?: return null,
                timestampLast4 = parts[3].toIntOrNull() ?: return null
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode tap BLE payload: $payload", e)
            return null
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Internal
    // ════════════════════════════════════════════════════════════════════

    /** Remove stale entries from both buffers. */
    private fun evictStale() {
        val cutoff = System.currentTimeMillis() - LOCAL_TAP_BUFFER_DURATION_MS
        localTapBuffer.removeAll { it.timestampMs < cutoff }
        remoteTapBuffer.removeAll { it.receivedAtMs < cutoff }
    }
}
