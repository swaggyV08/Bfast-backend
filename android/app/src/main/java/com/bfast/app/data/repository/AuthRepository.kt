package com.bfast.app.data.repository

import com.bfast.app.data.local.DataStoreManager
import com.bfast.app.data.remote.BFastApi
import com.bfast.app.data.remote.MobileLoginRequest
import com.bfast.app.data.remote.MobileRegisterRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: BFastApi,
    private val dataStoreManager: DataStoreManager
) {
    suspend fun getDeviceId(): String {
        return dataStoreManager.deviceId.first() ?: java.util.UUID.randomUUID().toString().also {
            dataStoreManager.saveDeviceId(it)
        }
    }

    suspend fun isLoggedIn(): Boolean {
        return dataStoreManager.accessToken.first() != null
    }

    suspend fun getDisplayName(): String {
        return dataStoreManager.displayName.firstOrNull() ?: "User"
    }

    suspend fun getStoredDeviceId(): String {
        return dataStoreManager.deviceId.first() ?: java.util.UUID.randomUUID().toString().also {
            dataStoreManager.saveDeviceId(it)
        }
    }

    suspend fun register(phoneNumber: String, passcode: String, confirmPasscode: String, displayName: String): Result<Unit> {
        return try {
            val deviceId = getDeviceId()
            val request = MobileRegisterRequest(
                phoneNumber = phoneNumber,
                passcode = passcode,
                confirmPasscode = confirmPasscode,
                displayName = displayName,
                deviceId = deviceId
            )
            val response = api.mobileRegister(request)
            if (response.isSuccessful && response.body()?.success == true) {
                val data = response.body()?.data
                if (data != null) {
                    dataStoreManager.saveTokens(data.tokens.accessToken, data.tokens.refreshToken)
                    dataStoreManager.saveUserId(data.user.id)
                    dataStoreManager.saveDisplayName(data.user.displayName)
                    dataStoreManager.savePhoneNumber(data.user.phoneNumber)
                    Result.success(Unit)
                } else {
                    Result.failure(Exception(
                        "We received an unexpected response from the server. " +
                        "Please try again in a moment."
                    ))
                }
            } else {
                val errorMessage = parseHttpError(response.code(), "registration")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: java.net.ConnectException) {
            Result.failure(Exception(
                "Cannot connect to the BFast server. Make sure your phone is on the same " +
                "WiFi network as the server (not mobile data) and try again."
            ))
        } catch (e: java.net.UnknownHostException) {
            Result.failure(Exception(
                "Cannot reach BFast servers. Make sure your phone is connected to WiFi, " +
                "not mobile data."
            ))
        } catch (e: java.net.SocketTimeoutException) {
            val isConnectTimeout = e.message?.contains("connect", ignoreCase = true) == true
            Result.failure(Exception(
                if (isConnectTimeout)
                    "Could not reach the BFast server. Make sure your phone and the server " +
                    "are on the same WiFi network and the server is running."
                else
                    "The server took too long to respond. Please try again."
            ))
        } catch (e: Exception) {
            Result.failure(Exception(
                "Something went wrong during registration: ${e.localizedMessage ?: "Unknown error"}. " +
                "Please try again."
            ))
        }
    }

    suspend fun login(phoneNumber: String, passcode: String): Result<Unit> {
        return try {
            val deviceId = getDeviceId()
            val request = MobileLoginRequest(
                phoneNumber = phoneNumber,
                passcode = passcode,
                deviceId = deviceId
            )
            val response = api.mobileLogin(request)
            if (response.isSuccessful && response.body()?.success == true) {
                val data = response.body()?.data
                if (data != null) {
                    dataStoreManager.saveTokens(data.tokens.accessToken, data.tokens.refreshToken)
                    dataStoreManager.saveUserId(data.user.id)
                    dataStoreManager.saveDisplayName(data.user.displayName)
                    dataStoreManager.savePhoneNumber(data.user.phoneNumber)
                    Result.success(Unit)
                } else {
                    Result.failure(Exception(
                        "We received an unexpected response from the server. " +
                        "Please try again in a moment."
                    ))
                }
            } else {
                val errorMessage = parseHttpError(response.code(), "login")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: java.net.ConnectException) {
            Result.failure(Exception(
                "Cannot connect to the BFast server. Make sure your phone is on the same " +
                "WiFi network as the server (not mobile data) and try again."
            ))
        } catch (e: java.net.UnknownHostException) {
            Result.failure(Exception(
                "Cannot reach BFast servers. Make sure your phone is connected to WiFi, " +
                "not mobile data."
            ))
        } catch (e: java.net.SocketTimeoutException) {
            val isConnectTimeout = e.message?.contains("connect", ignoreCase = true) == true
            Result.failure(Exception(
                if (isConnectTimeout)
                    "Could not reach the BFast server. Make sure your phone and the server " +
                    "are on the same WiFi network and the server is running."
                else
                    "The server took too long to respond. Please try again."
            ))
        } catch (e: Exception) {
            Result.failure(Exception(
                "Something went wrong during login: ${e.localizedMessage ?: "Unknown error"}. " +
                "Please try again."
            ))
        }
    }

    suspend fun logout() {
        dataStoreManager.clearAll()
    }

    /**
     * Converts HTTP status codes into human-readable error messages.
     * No more "400 LOGIN ERROR" — every error is explained clearly.
     */
    private fun parseHttpError(statusCode: Int, action: String): String {
        return when (statusCode) {
            400 -> "The information you entered is incorrect. Please double-check your " +
                    "phone number and passcode, then try again."
            401 -> "Your session has expired. Please log in again with your phone number and passcode."
            403 -> "Your account has been temporarily restricted. " +
                    "Please contact BFast support for help."
            404 -> if (action == "login") {
                "We couldn't find an account with this phone number. " +
                "Please check the number or create a new account."
            } else {
                "The service you're trying to reach is currently unavailable. " +
                "Please try again later."
            }
            409 -> "An account with this phone number already exists. " +
                    "Please log in instead, or use a different phone number."
            422 -> "Some of the information you entered is invalid. " +
                    "Please check all fields and try again."
            429 -> "You've made too many attempts. Please wait a few minutes before trying again."
            500, 502, 503 -> "BFast servers are experiencing issues right now. " +
                    "This is not your fault — please try again in a few minutes."
            504 -> "The server took too long to respond. " +
                    "Please check your internet connection and try again."
            else -> "Something unexpected happened (Error $statusCode). " +
                    "Please try again or contact support if the problem continues."
        }
    }
}
