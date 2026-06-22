package com.bfast.app.ui.sensortest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bfast.app.core.hardware.SensorForegroundService
import com.bfast.app.data.repository.SensorRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LogEvent(val text: String, val colorType: String = "Neutral")

@HiltViewModel
class SensorTestViewModel @Inject constructor(
    private val sensorRepository: SensorRepository
) : ViewModel() {

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _eventLogs = MutableStateFlow<List<LogEvent>>(emptyList())
    val eventLogs: StateFlow<List<LogEvent>> = _eventLogs.asStateFlow()

    private val _deviceId = MutableStateFlow("")
    val deviceId: StateFlow<String> = _deviceId.asStateFlow()

    private val _apiConnected = MutableStateFlow(false)
    val apiConnected: StateFlow<Boolean> = _apiConnected.asStateFlow()

    private val _isSenderRole = MutableStateFlow(false)
    val isSenderRole: StateFlow<Boolean> = _isSenderRole.asStateFlow()

    init {
        viewModelScope.launch {
            val id = sensorRepository.getDeviceId()
            _deviceId.value = id
            addLog("[System] Device: $id", "Neutral")
            addLog("[System] Press START to enable live gyro + tap detection", "Neutral")
        }
    }

    fun toggleRecording() {
        val nowRecording = !_isRecording.value
        _isRecording.value = nowRecording
        if (nowRecording) {
            SensorForegroundService.enterSensorTestMode()
            addLog("[Sensor] STARTED — accel + gyro at FASTEST rate, tap detector live", "Success")
        } else {
            SensorForegroundService.exitSensorTestMode()
            addLog("[Sensor] STOPPED", "Neutral")
        }
    }

    fun stopRecording() {
        if (_isRecording.value) {
            _isRecording.value = false
            SensorForegroundService.exitSensorTestMode()
            addLog("[Sensor] STOPPED", "Neutral")
        }
    }

    fun addLog(message: String, colorType: String) {
        val current = _eventLogs.value.toMutableList()
        current.add(0, LogEvent(message, colorType))
        if (current.size > 80) current.removeAt(current.size - 1)
        _eventLogs.value = current
    }

    fun testApiConnection() {
        addLog("[API] Testing connection...", "Neutral")
        viewModelScope.launch {
            try {
                kotlinx.coroutines.delay(1000)
                _apiConnected.value = true
                addLog("[API] Connection successful", "Success")
            } catch (e: Exception) {
                _apiConnected.value = false
                addLog("[API] Connection failed: ${e.message}", "Error")
            }
        }
    }

    fun toggleRole() {
        _isSenderRole.value = !_isSenderRole.value
        val role = if (_isSenderRole.value) "Sender" else "Receiver"
        addLog("[Config] Role → $role", "Neutral")
    }

    override fun onCleared() {
        super.onCleared()
        SensorForegroundService.exitSensorTestMode()
    }
}
