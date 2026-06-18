package com.bfast.app.ui.payment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bfast.app.data.repository.PaymentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class PaymentState {
    object Idle : PaymentState()
    object Processing : PaymentState()
    data class Success(val amountPaise: Long) : PaymentState()
    data class Error(val message: String) : PaymentState()
}

@HiltViewModel
class PaymentViewModel @Inject constructor(
    private val paymentRepository: PaymentRepository
) : ViewModel() {

    private val _paymentState = MutableStateFlow<PaymentState>(PaymentState.Idle)
    val paymentState: StateFlow<PaymentState> = _paymentState.asStateFlow()

    fun processPayment(targetDeviceId: String, targetName: String, amountPaise: Long) {
        viewModelScope.launch {
            _paymentState.value = PaymentState.Processing
            
            // Validate amount
            if (amountPaise <= 0) {
                _paymentState.value = PaymentState.Error(
                    "Please enter a valid amount greater than ₹0."
                )
                return@launch
            }
            if (amountPaise > 10000000) { // ₹1,00,000 max
                _paymentState.value = PaymentState.Error(
                    "The maximum amount you can send in a single transaction is ₹1,00,000. " +
                    "Please enter a smaller amount."
                )
                return@launch
            }

            // Check balance first
            try {
                val balanceResponse = paymentRepository.fetchBalance()
                val balance = balanceResponse.getOrDefault(0L)
                if (balance > 0L && amountPaise > balance) {
                    val balanceRs = balance / 100.0
                    val amountRs = amountPaise / 100.0
                    _paymentState.value = PaymentState.Error(
                        "You don't have enough balance for this payment. " +
                        "Your current balance is ₹${String.format("%.2f", balanceRs)}, " +
                        "but you're trying to send ₹${String.format("%.2f", amountRs)}. " +
                        "Please add funds to your wallet or enter a smaller amount."
                    )
                    return@launch
                }
            } catch (e: Exception) {
                // Allow payment to proceed if balance check fails (network issue)
                // The backend will do the final balance validation
            }

            val result = paymentRepository.processPayment(targetDeviceId, targetName, amountPaise)
            result.onSuccess {
                // Trigger offline BLE payment broadcast so receiver instantly knows
                com.bfast.app.core.hardware.SensorForegroundService.advertisePayment(amountPaise)
                _paymentState.value = PaymentState.Success(amountPaise)
            }.onFailure {
                _paymentState.value = PaymentState.Error(
                    it.message ?: "Payment failed. Please check your connection and try again."
                )
            }
        }
    }
    
    private val _matchedDeviceId = MutableStateFlow<String?>(null)
    val matchedDeviceId: StateFlow<String?> = _matchedDeviceId.asStateFlow()

    private val _matchedName = MutableStateFlow<String?>(null)
    val matchedName: StateFlow<String?> = _matchedName.asStateFlow()

    fun startSenderMode() {
        com.bfast.app.core.hardware.SensorForegroundService.resetHandshakeState()
        com.bfast.app.core.hardware.SensorForegroundService.isSenderMode.value = true
        
        // Observe matches from SensorForegroundService
        viewModelScope.launch {
            com.bfast.app.core.hardware.SensorForegroundService.outgoingPaymentMatch.collect { match ->
                if (match != null) {
                    _matchedDeviceId.value = match.deviceId
                    _matchedName.value = match.displayName
                }
            }
        }
    }

    fun stopSenderMode() {
        com.bfast.app.core.hardware.SensorForegroundService.isSenderMode.value = false
        com.bfast.app.core.hardware.SensorForegroundService.resetHandshakeState()
        _matchedDeviceId.value = null
        _matchedName.value = null
    }

    fun resetState() {
        _paymentState.value = PaymentState.Idle
    }
}
