package com.bfast.app.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Server Contract for the Proximity Tap-to-Pay Intent Engine.
 * 
 * Enforces the "Sensors Propose, Server Disposes" architecture.
 * Local taps must be correlated by the server before any money moves.
 */
interface PaymentCommitClient {

    /**
     * Request body for Session Correlation.
     * The Sender submits this when the UI pops.
     * The server correlates it against the Receiver's session within a tunable receive-time window.
     */
    data class CorrelationRequest(
        val sessionId: String,
        val senderDeviceId: String,
        val receiverDeviceId: String,
        val amountPaise: Long,
        val rangingMethod: String, // "CS" or "RSSI"
        val reportedDistanceCm: Float?, // For CS path
        val senderRssiDb: Int?, // For RSSI spoof-plausibility check
        val receiverRssiDb: Int? // For RSSI spoof-plausibility check
    )

    data class CorrelationResponse(
        val correlationId: String,
        val status: String // "MATCHED", "PEER_OFFLINE", "SPOOF_SUSPECTED"
    )

    /**
     * Endpoint 1: Correlate Tap
     * Server checks receive-time correlation windows (e.g. ±500ms).
     * If RSSI is used, it applies a sanity check (spoof-plausibility).
     */
    @POST("session/correlate")
    suspend fun correlateTap(@Body request: CorrelationRequest): Response<CorrelationResponse>

    /**
     * Request body for Idempotent Commit.
     */
    data class CommitRequest(
        val correlationId: String,
        val idempotencyKey: String, // Client generated UUID
        val finalAmountPaise: Long
    )

    data class CommitResponse(
        val transactionId: String,
        val status: String // "SUCCESS", "INSUFFICIENT_FUNDS", "EXPIRED"
    )

    /**
     * Endpoint 2: Commit Transaction
     * Safely executes the debit once the Correlation Gate is passed.
     */
    @POST("transaction/commit")
    suspend fun commitTransaction(@Body request: CommitRequest): Response<CommitResponse>

    /**
     * Request body for Reversal.
     */
    data class ReversalRequest(
        val transactionId: String,
        val reason: String = "ACCIDENTAL_TAP"
    )

    data class ReversalResponse(
        val status: String // "REVERSED", "TOO_LATE"
    )

    /**
     * Endpoint 3: Reverse Transaction
     * Fulfills the requirement: "EVERY transaction is reversible within a grace window with one tap"
     */
    @POST("transaction/reverse")
    suspend fun reverseTransaction(@Body request: ReversalRequest): Response<ReversalResponse>
}
