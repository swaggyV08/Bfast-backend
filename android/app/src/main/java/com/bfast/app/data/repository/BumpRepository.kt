package com.bfast.app.data.repository

import com.bfast.app.data.remote.BFastApi
import com.bfast.app.data.remote.ReceiverBumpRequest
import com.bfast.app.data.remote.SenderBumpRequest
import com.bfast.app.data.remote.TapEventRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BumpRepository @Inject constructor(
    private val api: BFastApi
) {
    suspend fun submitSenderBump(request: SenderBumpRequest) = withContext(Dispatchers.IO) {
        try {
            val response = api.senderBump(request)
            if (response.isSuccessful) {
                Result.success(response.body()?.data)
            } else {
                val errorMsg = when (response.code()) {
                    400 -> "The tap data was invalid. Please try tapping the phones together again, " +
                            "making sure they touch firmly."
                    401 -> "Your session has expired. Please go back to the home screen and try again."
                    422 -> "The sensor data from your tap didn't meet the quality threshold. " +
                            "Please try tapping the phones together more firmly."
                    429 -> "Too many tap attempts in a short time. Please wait a moment before trying again."
                    500, 502, 503 -> "BFast servers are temporarily unavailable. " +
                            "Please try again in a moment."
                    else -> "Something went wrong while processing the tap (Error ${response.code()}). " +
                            "Please try again."
                }
                Result.failure(Exception(errorMsg))
            }
        } catch (e: java.net.UnknownHostException) {
            Result.failure(Exception(
                "No internet connection. Please check your WiFi or mobile data and try again."
            ))
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(Exception(
                "The connection timed out. Please check your internet speed and try again."
            ))
        } catch (e: Exception) {
            Result.failure(Exception(
                "Failed to process tap: ${e.localizedMessage ?: "Unknown error"}. Please try again."
            ))
        }
    }

    suspend fun submitReceiverBump(request: ReceiverBumpRequest) = withContext(Dispatchers.IO) {
        try {
            val response = api.receiverBump(request)
            if (response.isSuccessful) {
                Result.success(response.body()?.data)
            } else {
                val errorMsg = when (response.code()) {
                    400 -> "The received tap data was invalid. Please ask the sender to try again."
                    422 -> "The sensor data didn't pass validation. " +
                            "Please ensure both phones are tapped together firmly."
                    500, 502, 503 -> "Server temporarily unavailable. The tap will be retried automatically."
                    else -> "Receiver bump failed (Error ${response.code()}). Please try again."
                }
                Result.failure(Exception(errorMsg))
            }
        } catch (e: java.net.UnknownHostException) {
            Result.failure(Exception(
                "No internet connection on receiver's end. Payment may still process when connectivity returns."
            ))
        } catch (e: Exception) {
            Result.failure(Exception(
                "Failed to register tap: ${e.localizedMessage ?: "Unknown error"}"
            ))
        }
    }

    suspend fun getMatch(matchId: String) = withContext(Dispatchers.IO) {
        try {
            val response = api.getMatch(matchId)
            if (response.isSuccessful) {
                Result.success(response.body()?.data)
            } else {
                val errorMsg = when (response.code()) {
                    404 -> "This tap match was not found. It may have expired. Please try a new tap."
                    else -> "Could not retrieve match details (Error ${response.code()})."
                }
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Result.failure(Exception(
                "Failed to get match: ${e.localizedMessage ?: "Unknown error"}"
            ))
        }
    }

    suspend fun uploadSensorBatch(request: com.bfast.app.data.remote.BatchSensorRequest) = withContext(Dispatchers.IO) {
        try {
            val response = api.uploadSensorBatch(request)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to upload sensor batch: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSessionStatus(sessionId: String) = withContext(Dispatchers.IO) {
        try {
            val response = api.getSessionStatus(sessionId)
            if (response.isSuccessful) {
                Result.success(response.body()?.data?.status)
            } else {
                Result.failure(Exception("Failed to get session status: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Called by RECEIVER when its accelerometer detects a physical tap.
     * Posts the tap event to the backend so the sender can poll for it.
     */
    suspend fun reportTap(
        receiverDeviceId: String,
        senderDeviceId: String,
        accelPeakMs2: Double,
        rssi: Int
    ) = withContext(Dispatchers.IO) {
        try {
            val request = TapEventRequest(
                receiverDeviceId = receiverDeviceId,
                senderDeviceId = senderDeviceId,
                accelPeakMs2 = accelPeakMs2,
                rssi = rssi,
                tapTimestamp = java.time.Instant.now().toString()
            )
            val response = api.reportTapEvent(request)
            if (response.isSuccessful) {
                Result.success(response.body()?.data?.tapEventId)
            } else {
                Result.failure(Exception("Failed to report tap: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Called by SENDER every ~300ms while in ARMED state.
     * Returns true when the backend has a fresh tap event from the receiver.
     */
    suspend fun pollTapStatus(senderDeviceId: String, receiverDeviceId: String) = withContext(Dispatchers.IO) {
        try {
            val response = api.pollTapStatus(senderDeviceId, receiverDeviceId)
            if (response.isSuccessful) {
                Result.success(response.body()?.data?.confirmed ?: false)
            } else {
                Result.failure(Exception("Tap poll failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
