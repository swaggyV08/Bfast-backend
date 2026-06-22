package com.bfast.app.data.repository

import com.bfast.app.data.local.DataStoreManager
import com.bfast.app.data.remote.BFastApi
import com.bfast.app.data.remote.BatchSensorRequest
import com.bfast.app.data.remote.SensorReadingDto
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SensorRepository @Inject constructor(
    private val api: BFastApi,
    private val dataStoreManager: DataStoreManager
) {
    suspend fun uploadSensorBatch(request: BatchSensorRequest): Result<Unit> {
        return try {
            val response = api.uploadSensorBatch(request)
            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to upload sensor batch: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getDeviceId(): String {
        return dataStoreManager.deviceId.first() ?: ""
    }
}
