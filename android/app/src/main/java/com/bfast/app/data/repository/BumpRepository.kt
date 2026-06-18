package com.bfast.app.data.repository

import com.bfast.app.data.remote.BFastApi
import com.bfast.app.data.remote.ReceiverBumpRequest
import com.bfast.app.data.remote.SenderBumpRequest
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
}
