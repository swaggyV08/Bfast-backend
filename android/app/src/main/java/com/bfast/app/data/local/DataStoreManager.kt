package com.bfast.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class DataStoreManager @Inject constructor(@ApplicationContext private val context: Context) {

    companion object {
        val ACCESS_TOKEN_KEY = stringPreferencesKey("access_token")
        val REFRESH_TOKEN_KEY = stringPreferencesKey("refresh_token")
        val USER_ID_KEY = stringPreferencesKey("user_id")
        val DEVICE_ID_KEY = stringPreferencesKey("device_id")
        val DISPLAY_NAME_KEY = stringPreferencesKey("display_name")
        val PHONE_NUMBER_KEY = stringPreferencesKey("phone_number")
        val DETECTION_MODE_KEY = stringPreferencesKey("detection_mode")
    }

    /** Detection mode values: "ACCEL_GYRO_BLE" or "UWB_BLE" */
    val detectionMode: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[DETECTION_MODE_KEY] ?: "ACCEL_GYRO_BLE"
    }

    val accessToken: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[ACCESS_TOKEN_KEY]
    }

    val deviceId: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[DEVICE_ID_KEY]
    }

    val displayName: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[DISPLAY_NAME_KEY]
    }

    val phoneNumber: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PHONE_NUMBER_KEY]
    }

    suspend fun saveTokens(access: String, refresh: String) {
        context.dataStore.edit { preferences ->
            preferences[ACCESS_TOKEN_KEY] = access
            preferences[REFRESH_TOKEN_KEY] = refresh
        }
    }

    suspend fun saveUserId(userId: String) {
        context.dataStore.edit { preferences ->
            preferences[USER_ID_KEY] = userId
        }
    }

    suspend fun saveDeviceId(deviceId: String) {
        context.dataStore.edit { preferences ->
            preferences[DEVICE_ID_KEY] = deviceId
        }
    }

    suspend fun saveDisplayName(name: String) {
        context.dataStore.edit { preferences ->
            preferences[DISPLAY_NAME_KEY] = name
        }
    }

    suspend fun savePhoneNumber(phone: String) {
        context.dataStore.edit { preferences ->
            preferences[PHONE_NUMBER_KEY] = phone
        }
    }

    suspend fun saveDetectionMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[DETECTION_MODE_KEY] = mode
        }
    }

    suspend fun clearAll() {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
    }
}
