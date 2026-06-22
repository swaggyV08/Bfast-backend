package com.bfast.app.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface BFastApi {

    @POST("auth/mobile/register")
    suspend fun mobileRegister(@Body request: MobileRegisterRequest): Response<AuthResponse>

    @POST("auth/mobile/login")
    suspend fun mobileLogin(@Body request: MobileLoginRequest): Response<AuthResponse>

    @GET("wallet/balance")
    suspend fun getBalance(): Response<BalanceResponse>
    
    @GET("transactions")
    suspend fun getTransactions(): Response<TransactionsResponse>
    
    @POST("sensor-readings/batch")
    suspend fun uploadSensorBatch(@Body request: BatchSensorRequest): Response<GenericResponse>
    
    @POST("bumps/sender")
    suspend fun senderBump(@Body request: SenderBumpRequest): Response<BumpResponse>

    @POST("bumps/receiver")
    suspend fun receiverBump(@Body request: ReceiverBumpRequest): Response<BumpResponse>

    @GET("session/status/{sessionId}")
    suspend fun getSessionStatus(@retrofit2.http.Path("sessionId") sessionId: String): Response<SessionStatusResponse>

    @GET("bumps/match/{matchId}")
    suspend fun getMatch(@retrofit2.http.Path("matchId") matchId: String): Response<MatchResponse>

    // ── Tap event endpoints ─────────────────────────────────────────────────
    // Receiver calls tap-event when its accelerometer detects a physical tap.
    // Sender polls tap-poll every ~300ms to know when receiver confirmed the tap.
    @POST("session/tap-event")
    suspend fun reportTapEvent(@Body request: TapEventRequest): Response<TapEventResponse>

    @GET("session/tap-poll")
    suspend fun pollTapStatus(
        @retrofit2.http.Query("senderDeviceId") senderDeviceId: String,
        @retrofit2.http.Query("receiverDeviceId") receiverDeviceId: String
    ): Response<TapPollResponse>
}

// --- DTOs ---

data class MobileRegisterRequest(
    val phoneNumber: String,
    val passcode: String,
    val confirmPasscode: String,
    val displayName: String,
    val deviceId: String,
    val platform: String = "android"
)

data class MobileLoginRequest(
    val phoneNumber: String,
    val passcode: String,
    val deviceId: String
)

data class AuthResponse(
    val success: Boolean,
    val data: AuthData?
)

data class AuthData(
    val user: UserDto,
    val wallet: WalletDto,
    val tokens: TokensDto
)

data class UserDto(
    val id: String,
    val phoneNumber: String,
    val displayName: String
)

data class WalletDto(
    val id: String,
    val balancePaise: Long,
    val currency: String
)

data class TokensDto(
    val accessToken: String,
    val refreshToken: String
)

data class BalanceResponse(
    val success: Boolean,
    val data: BalanceData?
)

data class BalanceData(
    val balancePaise: Long,
    val currency: String
)

data class TransactionsResponse(
    val success: Boolean,
    val data: TransactionsData?
)

data class TransactionsData(
    val transactions: List<TransactionDto>
)

data class TransactionDto(
    val id: String,
    val type: String, // SENT, RECEIVED
    val amountPaise: Long,
    val status: String,
    val counterpartyName: String?,
    val timestamp: String
)

data class BatchSensorRequest(
    val sessionId: String,
    val role: String,
    val peerDeviceId: String?,
    val readings: List<SensorReadingDto>
)

data class SessionStatusResponse(
    val success: Boolean,
    val data: SessionStatusData?
)

data class SessionStatusData(
    val status: String
)

data class SensorReadingDto(
    val deviceId: String,
    val accelX: Double,
    val accelY: Double,
    val accelZ: Double,
    val accelMagnitude: Double,
    val gyroX: Double,
    val gyroY: Double,
    val gyroZ: Double,
    val gyroMagnitude: Double,
    val tapDetected: Boolean,
    val tapConfidence: Double?,
    val recordedAt: String
)

data class GenericResponse(
    val success: Boolean,
    val data: GenericMessage?
)

data class GenericMessage(
    val message: String
)

data class NearbyDeviceDto(
    val deviceId: String,
    val rssi: Int,
    val bleConfidence: Double = 0.0
)

data class SensorSnapshotDto(
    val accelPeakMs2: Double,
    val accelDurationMs: Int,
    val gyroMagnitudeRads: Double,
    val tapTimestamp: String,
    val accelConfidence: Double = 0.0,
    val gyroConfidence: Double = 0.0
)

data class SenderBumpRequest(
    val deviceId: String,
    val nearbyDevices: List<NearbyDeviceDto>,
    val rssi: Int? = null,
    val sensorSnapshot: SensorSnapshotDto
)

data class ReceiverBumpRequest(
    val deviceId: String,
    val rssi: Int? = null,
    val sensorSnapshot: SensorSnapshotDto
)

data class BumpResponse(
    val success: Boolean,
    val data: BumpData?,
    val message: String? = null
)

data class BumpData(
    val matched: Boolean,
    val matchId: String? = null,
    val receiverDeviceId: String? = null,
    val receiverUserId: String? = null,
    val rssiScore: Double? = null,
    val timeDeltaMs: Long? = null,
    val sensorValidated: Boolean? = null,
    val registered: Boolean? = null,
    val message: String? = null
)

data class MatchResponse(
    val success: Boolean,
    val data: MatchData?
)

data class MatchData(
    val matchId: String,
    val senderDeviceId: String,
    val senderUserId: String,
    val receiverDeviceId: String,
    val receiverUserId: String,
    val rssiScore: Double,
    val timeDeltaMs: Long,
    val senderAccelMs2: Double,
    val receiverAccelMs2: Double,
    val matchedAt: String,
    val consumed: Boolean
)

// ── Tap event DTOs ──────────────────────────────────────────────────────────

data class TapEventRequest(
    val receiverDeviceId: String,
    val senderDeviceId: String,
    val accelPeakMs2: Double,
    val rssi: Int,
    val tapTimestamp: String
)

data class TapEventResponse(
    val success: Boolean,
    val data: TapEventData?
)

data class TapEventData(
    val tapEventId: String
)

data class TapPollResponse(
    val success: Boolean,
    val data: TapPollData?
)

data class TapPollData(
    val confirmed: Boolean
)
