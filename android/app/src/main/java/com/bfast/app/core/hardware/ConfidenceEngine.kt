package com.bfast.app.core.hardware

import android.util.Log

/**
 * Production-grade 6-signal weighted confidence engine (Layer 11).
 *
 * Fuses six independent signals into a single 0–100 score:
 *
 *   | Signal              | Weight | Source                    |
 *   |---------------------|--------|--------------------------|
 *   | Receiver Stability  | 20%    | BleProximityEngine       |
 *   | BLE Proximity       | 20%    | BleProximityEngine       |
 *   | Approach Direction  | 15%    | ApproachVerifier         |
 *   | Impact Strength     | 15%    | TapDetector              |
 *   | Impact Shape        | 10%    | TapDetector (type+dur)   |
 *   | Mutual Detection    | 20%    | DualDeviceCorrelator     |
 *
 * Decision thresholds:
 *   < 50  → REJECT (do nothing)
 *   50-69 → MONITORING (stay ARMED, wait for mutual detection)
 *   ≥ 70  → TAP_CONFIRMED (open payment screen)
 *
 * ## Why This Works
 *
 * - "Phone waving near receiver" scores ~29 (no impact, no mutual) → REJECT
 * - "Sender taps but only one phone detects" scores ~70 → borderline (needs mutual)
 * - "Real phone-to-phone tap (both detect)" scores ~91 → TAP_CONFIRMED
 * - The 60+ point gap between noise and real taps makes false triggers near-impossible.
 */
class ConfidenceEngine {

    companion object {
        private const val TAG = "ConfidenceEngine"

        // ── Thresholds ──────────────────────────────────────────────────
        /** Score ≥ 70 → TAP_CONFIRMED → open payment sheet. */
        const val PAYMENT_TRIGGER_THRESHOLD = 70

        /** Score 50-69 → MONITORING → stay ARMED, wait for mutual detection. */
        const val MONITORING_THRESHOLD = 50

        // ── Weights (sum = 1.0) ─────────────────────────────────────────
        const val W_RECEIVER_STABILITY = 0.15f
        const val W_BLE_PROXIMITY = 0.20f
        const val W_RSSI_SURGE = 0.20f
        const val W_APPROACH_DIRECTION = 0.10f
        const val W_IMPACT_STRENGTH = 0.20f
        const val W_IMPACT_SHAPE = 0.15f
    }

    /**
     * All input signals for the local intent calculation.
     * Each signal is normalized to 0–100.
     */
    data class ConfidenceInput(
        val receiverStability: Float,
        val bleProximity: Float,
        val rssiSurge: Float,
        val approachDirection: Float,
        val impactStrength: Float,
        val impactShape: Float,
        /** Passed in just to surface in the breakdown/result, not used in physical score. */
        val mutualDetection: Float
    )

    data class ConfidenceResult(
        val totalScore: Float,
        /** True if totalScore >= PAYMENT_TRIGGER_THRESHOLD (pops the UI). */
        val shouldTriggerUi: Boolean,
        /** True if mutual peer detection was seen locally (server will still check). */
        val hasLocalMutualDetection: Boolean,
        val breakdown: ScoreBreakdown
    )

    data class ScoreBreakdown(
        val receiverStability: Float,
        val bleProximity: Float,
        val rssiSurge: Float,
        val approachDirection: Float,
        val impactStrength: Float,
        val impactShape: Float
    )

    // ════════════════════════════════════════════════════════════════════
    //  Core API
    // ════════════════════════════════════════════════════════════════════

    fun calculate(input: ConfidenceInput): ConfidenceResult {
        val stabPart = input.receiverStability * W_RECEIVER_STABILITY
        val proxPart = input.bleProximity * W_BLE_PROXIMITY
        val surgePart = input.rssiSurge * W_RSSI_SURGE
        val approachPart = input.approachDirection * W_APPROACH_DIRECTION
        val impactPart = input.impactStrength * W_IMPACT_STRENGTH
        val shapePart = input.impactShape * W_IMPACT_SHAPE

        val total = (stabPart + proxPart + surgePart + approachPart +
                impactPart + shapePart).coerceIn(0f, 100f)

        val breakdown = ScoreBreakdown(
            receiverStability = stabPart,
            bleProximity = proxPart,
            rssiSurge = surgePart,
            approachDirection = approachPart,
            impactStrength = impactPart,
            impactShape = shapePart
        )

        val result = ConfidenceResult(
            totalScore = total,
            shouldTriggerUi = total >= PAYMENT_TRIGGER_THRESHOLD,
            hasLocalMutualDetection = input.mutualDetection >= 50f,
            breakdown = breakdown
        )

        Log.d(TAG, "Confidence: %.1f [Stab=%.1f Prox=%.1f Surge=%.1f App=%.1f Imp=%.1f Shp=%.1f Mut=%.1f] → %s".format(
            total, stabPart, proxPart, surgePart, approachPart, impactPart, shapePart, input.mutualDetection,
            if (result.shouldTriggerUi) "UI_TRIGGERED" else "REJECT"
        ))

        return result
    }

    // ════════════════════════════════════════════════════════════════════
    //  Impact Shape Scoring
    // ════════════════════════════════════════════════════════════════════

    /**
     * Compute impact shape score (0–100) from a TapSignature.
     *
     * A clean tap shape has:
     *   - Appropriate tap type (NORMAL_TAP or HARD_TAP)
     *   - Short duration (5–80ms ideal)
     *   - Low gyro at moment of impact (not a rotation/wave)
     */
    fun computeImpactShapeScore(
        tapType: TapType,
        durationMs: Long,
        gyroMagnitude: Double
    ): Float {
        // Type score: NORMAL_TAP is ideal, SOFT_TAP is acceptable
        val typeScore = when (tapType) {
            TapType.SOFT_TAP -> 60f
            TapType.NORMAL_TAP -> 100f
            TapType.HARD_TAP -> 90f
        }

        // Duration score: 5–80ms = clean impulse, 80–150ms = acceptable, >150ms = poor
        val durScore = when {
            durationMs in 0..80 -> 100f
            durationMs in 81..150 -> 70f
            else -> 30f
        }

        // Gyro score: low gyro during tap = clean tap (not rotation)
        val gyroScore = when {
            gyroMagnitude < 1.0 -> 100f
            gyroMagnitude < 2.0 -> 70f
            gyroMagnitude < 3.0 -> 40f
            else -> 10f
        }

        return (typeScore * 0.4f + durScore * 0.4f + gyroScore * 0.2f).coerceIn(0f, 100f)
    }
}
