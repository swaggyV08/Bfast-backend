package com.bfast.app.core.telemetry

import android.util.Log
import org.json.JSONObject

/**
 * Production-grade Telemetry Harness for the Proximity Tap-to-Pay Intent Engine.
 *
 * This explicitly isolates the two types of False Positives (FPR) discussed during architecture:
 * 1. FPR_UNMATCHED_NO_PEER: The local model triggered, but there was no peer nearby. (Model tuning required)
 * 2. FPR_PEER_UNREACHABLE: The local model triggered, peer was expected, but peer app was dead (OS background kill). (Exemption required)
 */
object AccuracyHarness {

    private const val TAG = "BFastTelemetry"

    enum class EventType {
        TAP_INTENT_LOCAL_SUCCESS,
        COMMIT_GATE_SUCCESS,
        FPR_UNMATCHED_NO_PEER,
        FPR_PEER_UNREACHABLE,
        TX_REVERSED
    }

    /**
     * Logs structured JSON to logcat. In production, this ships to Datadog/Sentry/Firebase.
     */
    fun logEvent(
        eventType: EventType,
        deviceId: String? = null,
        peerId: String? = null,
        confidenceScore: Float? = null,
        correlationId: String? = null,
        extraData: Map<String, Any> = emptyMap()
    ) {
        val payload = JSONObject().apply {
            put("event_type", eventType.name)
            put("timestamp", System.currentTimeMillis())
            deviceId?.let { put("device_id", it) }
            peerId?.let { put("peer_id", it) }
            confidenceScore?.let { put("confidence_score", it) }
            correlationId?.let { put("correlation_id", it) }
            extraData.forEach { (key, value) ->
                put(key, value)
            }
        }

        Log.i(TAG, "TELEMETRY_PAYLOAD: $payload")
    }
}
