package com.bfast.app.ui.sensortest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bfast.app.data.remote.SensorReadingDto
import com.bfast.app.data.repository.SensorRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class LogEvent(val text: String, val colorType: String = "Neutral")

@HiltViewModel
class SensorTestViewModel @Inject constructor(
    private val sensorRepository: SensorRepository
) : ViewModel() {

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _statusMessage = MutableStateFlow("Idle")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _eventLogs = MutableStateFlow<List<LogEvent>>(emptyList())
    val eventLogs: StateFlow<List<LogEvent>> = _eventLogs.asStateFlow()

    private val _deviceId = MutableStateFlow("")
    val deviceId: StateFlow<String> = _deviceId.asStateFlow()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    private val _apiConnected = MutableStateFlow(false)
    val apiConnected: StateFlow<Boolean> = _apiConnected.asStateFlow()

    private val _isSenderRole = MutableStateFlow(false)
    val isSenderRole: StateFlow<Boolean> = _isSenderRole.asStateFlow()

    init {
        viewModelScope.launch {
            val id = sensorRepository.getDeviceId()
            _deviceId.value = id
            addLog("[System] Initialized with Device ID: $id", "Neutral")
            addLog("[System] Ready - press \"Start Sensors\" to begin", "Neutral")
        }
    }

    fun addLog(message: String, colorType: String) {
        val currentLogs = _eventLogs.value.toMutableList()
        currentLogs.add(LogEvent(message, colorType))
        if (currentLogs.size > 50) currentLogs.removeAt(0)
        _eventLogs.value = currentLogs
    }

    fun toggleRecording() {
        _isRecording.value = !_isRecording.value
        _statusMessage.value = if (_isRecording.value) "Recording..." else "Stopped"
        val status = if (_isRecording.value) "Started" else "Stopped"
        addLog("[Sensor] $status live readings", "Neutral")
        // Stop automatically handled in UI by checking state
    }

    fun stopRecording() {
        _isRecording.value = false
        _statusMessage.value = "Stopped"
        addLog("[Sensor] Stopped live readings", "Neutral")
    }

    fun sendLastTap(confidence: Float?) {
        if (confidence != null && confidence >= 0.8f) {
            addLog("[Action] Sent bump event (confidence: $confidence)", "Success")
        } else {
            addLog("[Warning] No strong tap detected recently!", "Error")
        }
    }

    fun testApiConnection() {
        addLog("[API] Testing connection...", "Neutral")
        viewModelScope.launch {
            try {
                // Dummy network simulation
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
        addLog("[Config] Changed role to $role", "Neutral")
    }
}
