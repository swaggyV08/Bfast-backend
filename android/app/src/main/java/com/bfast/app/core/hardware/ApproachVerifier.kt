package com.bfast.app.core.hardware

import android.util.Log
import kotlin.math.abs

/**
 * Approach Direction & RSSI Peak Verifier (Layers 7 + 8).
 *
 * Layer 7 — Approach Direction:
 *   Verifies that the sender physically moved toward the receiver
 *   by checking that RSSI improved (became less negative) over the
 *   last several samples. Example: -70 → -65 → -58 → -50 ✓
 *
 * Layer 8 — RSSI Peak Detection:
 *   Detects a large RSSI spike during the impact moment. When two
 *   phones are tapped together, the RSSI jumps dramatically
 *   (e.g., baseline=-58, peak=-35, delta=23 dBm). If RSSI remains
 *   flat during the alleged tap, the tap is rejected.
 *
 * Both signals are fed into the ConfidenceEngine as independent components.
 */
class ApproachVerifier {

    companion object {
        private const val TAG = "ApproachVerifier"

        /** Ring buffer size for RSSI history per device. */
        const val RSSI_HISTORY_SIZE = 20

        /** Maximum age of RSSI samples before eviction (ms). */
        const val RSSI_MAX_AGE_MS = 5000L

        /** Minimum slope (dBm/sec) to consider as "approaching". */
        const val MIN_APPROACH_SLOPE = 0.5

        /** Minimum RSSI delta (dBm) for peak detection to pass. */
        const val MIN_PEAK_DELTA_DB = 8

        /** Window before impact to calculate baseline RSSI (ms). */
        const val BASELINE_WINDOW_MS = 2000L

        /** Window around impact time to search for RSSI peak (ms). */
        const val PEAK_WINDOW_MS = 500L
    }

    data class TimestampedRssi(val rssi: Int, val timestampMs: Long)

    /**
     * Result of checking for an RSSI peak during an impact event.
     */
    data class RssiPeakResult(
        /** Whether a significant RSSI peak was detected. */
        val detected: Boolean,
        /** Average RSSI before the impact window. */
        val baselineRssi: Int,
        /** Maximum RSSI near the impact timestamp. */
        val peakRssi: Int,
        /** Difference: peakRssi − baselineRssi (dBm). */
        val deltaDb: Int,
        /** Score 0–100 based on delta magnitude. */
        val score: Float
    )

    // Per-device RSSI history
    private val rssiHistory = mutableMapOf<String, ArrayDeque<TimestampedRssi>>()

    // ════════════════════════════════════════════════════════════════════
    //  RSSI Feed
    // ════════════════════════════════════════════════════════════════════

    /**
     * Feed an RSSI sample for a device. Call this on every BLE scan result.
     */
    fun feedRssi(deviceId: String, rssi: Int, timestampMs: Long) {
        val history = rssiHistory.getOrPut(deviceId) { ArrayDeque() }
        history.addLast(TimestampedRssi(rssi, timestampMs))

        // Cap ring buffer size
        while (history.size > RSSI_HISTORY_SIZE) {
            history.removeFirst()
        }

        // Evict samples older than max age
        while (history.isNotEmpty() && timestampMs - history.first().timestampMs > RSSI_MAX_AGE_MS) {
            history.removeFirst()
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Layer 7: Approach Direction
    // ════════════════════════════════════════════════════════════════════

    /**
     * Check if RSSI shows an approaching trend.
     * @return true if the approach score exceeds 40.
     */
    fun isApproaching(deviceId: String): Boolean {
        return getApproachScore(deviceId) > 40f
    }

    /**
     * Compute approach direction score (0–100).
     *
     * Uses linear regression on recent RSSI-vs-time samples.
     * Positive slope = approaching (RSSI becoming less negative).
     *
     * Scoring:
     *   slope ≥ 3.0 dBm/s → 100 (approaching fast)
     *   slope = 0.5 dBm/s → ~50 (barely approaching)
     *   slope ≤ -1.0 dBm/s → 0 (moving away)
     */
    fun getApproachScore(deviceId: String): Float {
        val history = rssiHistory[deviceId] ?: return 0f
        if (history.size < 4) return 30f  // Warm-start: neutral

        val n = history.size
        val t0 = history.first().timestampMs

        var sumT = 0.0; var sumR = 0.0
        var sumTR = 0.0; var sumTT = 0.0

        for (entry in history) {
            val t = (entry.timestampMs - t0) / 1000.0  // seconds
            val r = entry.rssi.toDouble()
            sumT += t; sumR += r
            sumTR += t * r; sumTT += t * t
        }

        val denom = n * sumTT - sumT * sumT
        if (abs(denom) < 1e-9) return 30f

        val slope = (n * sumTR - sumT * sumR) / denom  // dBm/sec

        return when {
            slope >= 3.0 -> 100f
            slope >= MIN_APPROACH_SLOPE -> {
                ((slope - MIN_APPROACH_SLOPE) / (3.0 - MIN_APPROACH_SLOPE) * 60.0 + 40.0).toFloat()
            }
            slope >= -0.5 -> (30f + ((slope + 0.5) * 20.0).toFloat()).coerceAtLeast(10f)
            else -> 0f
        }.coerceIn(0f, 100f)
    }

    // ════════════════════════════════════════════════════════════════════
    //  Layer 8: RSSI Peak Detection
    // ════════════════════════════════════════════════════════════════════

    /**
     * Check for an RSSI peak near the impact timestamp.
     *
     * Compares the peak RSSI within [PEAK_WINDOW_MS] of impactTimestampMs
     * against the baseline RSSI from [BASELINE_WINDOW_MS] before the peak window.
     *
     * A genuine phone-to-phone tap produces a large delta:
     *   baseline = -58, peak = -35  →  delta = 23  ✓
     *   baseline = -58, peak = -55  →  delta = 3   ✗
     */
    fun checkRssiPeak(deviceId: String, impactTimestampMs: Long): RssiPeakResult {
        val history = rssiHistory[deviceId]
        if (history == null || history.size < 3) {
            return RssiPeakResult(false, -100, -100, 0, 0f)
        }

        // Baseline: average RSSI from BASELINE_WINDOW *before* the peak window
        val baselineStart = impactTimestampMs - BASELINE_WINDOW_MS
        val baselineEnd = impactTimestampMs - PEAK_WINDOW_MS
        val baselineSamples = history.filter { it.timestampMs in baselineStart..baselineEnd }

        val baselineRssi = if (baselineSamples.isNotEmpty()) {
            baselineSamples.map { it.rssi }.average().toInt()
        } else {
            // Fallback: use the oldest half of the buffer
            val half = history.size / 2
            if (half > 0) history.toList().take(half).map { it.rssi }.average().toInt()
            else history.first().rssi
        }

        // Peak: maximum RSSI within PEAK_WINDOW of impact
        val peakStart = impactTimestampMs - PEAK_WINDOW_MS
        val peakEnd = impactTimestampMs + PEAK_WINDOW_MS
        val peakSamples = history.filter { it.timestampMs in peakStart..peakEnd }

        val peakRssi = if (peakSamples.isNotEmpty()) {
            peakSamples.maxOf { it.rssi }
        } else {
            history.last().rssi
        }

        val deltaDb = peakRssi - baselineRssi
        val detected = deltaDb >= MIN_PEAK_DELTA_DB

        // Score mapping:
        //   delta = 0  → 0
        //   delta = MIN_PEAK_DELTA_DB → 40
        //   delta = 20 → 100
        val score = when {
            deltaDb <= 0 -> 0f
            deltaDb < MIN_PEAK_DELTA_DB ->
                (deltaDb.toFloat() / MIN_PEAK_DELTA_DB * 40f)
            deltaDb < 20 ->
                (40f + (deltaDb - MIN_PEAK_DELTA_DB).toFloat() / (20 - MIN_PEAK_DELTA_DB) * 60f)
            else -> 100f
        }.coerceIn(0f, 100f)

        if (detected) {
            Log.i(TAG, "RSSI Peak DETECTED: baseline=$baselineRssi peak=$peakRssi " +
                    "delta=$deltaDb score=${"%.1f".format(score)}")
        }

        return RssiPeakResult(
            detected = detected,
            baselineRssi = baselineRssi,
            peakRssi = peakRssi,
            deltaDb = deltaDb,
            score = score
        )
    }

    // ════════════════════════════════════════════════════════════════════
    //  Utilities
    // ════════════════════════════════════════════════════════════════════

    /** Clear history for a single device. */
    fun clearDevice(deviceId: String) { rssiHistory.remove(deviceId) }

    /** Clear all RSSI history. */
    fun clearAll() { rssiHistory.clear() }
}
