package com.bfast.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bfast.app.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class AuthState {
    object Idle : AuthState()
    object Loading : AuthState()
    object Success : AuthState()
    data class Error(val message: String) : AuthState()
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    fun register(phoneNumber: String, passcode: String, confirmPasscode: String, displayName: String) {
        // ── Input Validation with human-readable messages ────────────
        if (phoneNumber.isBlank()) {
            _authState.value = AuthState.Error(
                "Please enter your phone number. We need it to create your BFast account."
            )
            return
        }
        if (phoneNumber.replace("+91", "").replace(" ", "").length < 10) {
            _authState.value = AuthState.Error(
                "Your phone number seems too short. Please enter a valid 10-digit Indian mobile number (e.g., 9876543210)."
            )
            return
        }
        if (phoneNumber.replace("+91", "").replace(" ", "").length > 10) {
            _authState.value = AuthState.Error(
                "Your phone number seems too long. Please enter exactly 10 digits (without country code)."
            )
            return
        }
        if (displayName.isBlank()) {
            _authState.value = AuthState.Error(
                "Please enter your display name. This is how other BFast users will see you."
            )
            return
        }
        if (displayName.length < 2) {
            _authState.value = AuthState.Error(
                "Your display name is too short. Please enter at least 2 characters."
            )
            return
        }
        if (passcode.isBlank()) {
            _authState.value = AuthState.Error(
                "Please create a 4-digit passcode. You'll use this to log in to BFast."
            )
            return
        }
        if (passcode.length != 4) {
            _authState.value = AuthState.Error(
                "Your passcode must be exactly 4 digits. Please enter a 4-digit number (e.g., 1234)."
            )
            return
        }
        if (!passcode.all { it.isDigit() }) {
            _authState.value = AuthState.Error(
                "Your passcode must contain only numbers (0-9). Letters and special characters are not allowed."
            )
            return
        }
        if (confirmPasscode.isBlank()) {
            _authState.value = AuthState.Error(
                "Please confirm your passcode by entering it again."
            )
            return
        }
        if (passcode != confirmPasscode) {
            _authState.value = AuthState.Error(
                "Your passcodes don't match. Please make sure both passcode fields have the same 4-digit number."
            )
            return
        }

        val formattedPhone = if (!phoneNumber.startsWith("+91")) "+91$phoneNumber" else phoneNumber

        viewModelScope.launch {
            _authState.value = AuthState.Loading
            val result = authRepository.register(formattedPhone, passcode, confirmPasscode, displayName)
            result.onSuccess {
                _authState.value = AuthState.Success
            }.onFailure {
                _authState.value = AuthState.Error(
                    it.message ?: "Registration failed. Please check your details and try again."
                )
            }
        }
    }

    fun login(phoneNumber: String, passcode: String) {
        // ── Input Validation with human-readable messages ────────────
        if (phoneNumber.isBlank()) {
            _authState.value = AuthState.Error(
                "Please enter your phone number to log in."
            )
            return
        }
        if (phoneNumber.replace("+91", "").replace(" ", "").length < 10) {
            _authState.value = AuthState.Error(
                "Your phone number seems too short. Please enter a valid 10-digit Indian mobile number."
            )
            return
        }
        if (passcode.isBlank()) {
            _authState.value = AuthState.Error(
                "Please enter your 4-digit passcode to log in."
            )
            return
        }
        if (passcode.length != 4) {
            _authState.value = AuthState.Error(
                "Your passcode must be exactly 4 digits. Please check and try again."
            )
            return
        }
        if (!passcode.all { it.isDigit() }) {
            _authState.value = AuthState.Error(
                "Your passcode must contain only numbers (0-9)."
            )
            return
        }

        val formattedPhone = if (!phoneNumber.startsWith("+91")) "+91$phoneNumber" else phoneNumber

        viewModelScope.launch {
            _authState.value = AuthState.Loading
            val result = authRepository.login(formattedPhone, passcode)
            result.onSuccess {
                _authState.value = AuthState.Success
            }.onFailure {
                _authState.value = AuthState.Error(
                    it.message ?: "Login failed. Please check your phone number and passcode, then try again."
                )
            }
        }
    }
    
    fun resetState() {
        _authState.value = AuthState.Idle
    }
}
