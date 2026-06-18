package com.bfast.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bfast.app.data.local.DataStoreManager
import com.bfast.app.data.local.db.TransactionEntity
import com.bfast.app.data.repository.HomeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class HomeState {
    object Loading : HomeState()
    data class Success(val balancePaise: Long) : HomeState()
    data class Error(val message: String) : HomeState()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val homeRepository: HomeRepository,
    private val dataStoreManager: DataStoreManager
) : ViewModel() {

    private val _homeState = MutableStateFlow<HomeState>(HomeState.Loading)
    val homeState: StateFlow<HomeState> = _homeState.asStateFlow()

    val transactions: StateFlow<List<TransactionEntity>> =
        homeRepository.getLocalTransactions()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // User's display name from DataStore
    private val _displayName = MutableStateFlow("User")
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    init {
        viewModelScope.launch {
            dataStoreManager.displayName.collect { name ->
                _displayName.value = name ?: "User"
            }
        }
    }

    fun loadData() {
        viewModelScope.launch {
            _homeState.value = HomeState.Loading

            // Sync transactions in background
            launch { homeRepository.syncTransactions() }

            // Fetch balance
            val result = homeRepository.fetchBalance()
            result.onSuccess { balance ->
                _homeState.value = HomeState.Success(balance)
            }.onFailure {
                _homeState.value = HomeState.Error(
                    "We couldn't load your wallet balance right now. " +
                    "Please check your internet connection and try again. " +
                    "If the problem continues, restart the app."
                )
            }
        }
    }

    fun refreshBalance() {
        viewModelScope.launch {
            val result = homeRepository.fetchBalance()
            result.onSuccess { balance ->
                _homeState.value = HomeState.Success(balance)
            }
        }
    }

    /**
     * Show the in-app received success banner.
     * Note: The actual transaction recording now happens in SensorForegroundService,
     * so this method only handles the UI banner.
     */
    fun showReceivedBanner(senderName: String, amountPaise: Long) {
        viewModelScope.launch {
            val amountRs = amountPaise / 100
            _receivedSuccessMessage.value = "Successfully received ₹$amountRs from $senderName via BFast! 🎉"
            kotlinx.coroutines.delay(5000)
            _receivedSuccessMessage.value = null
        }
    }

    /**
     * Legacy method kept for backward compatibility.
     * Transaction recording is now done in the SensorForegroundService to work
     * even when HomeScreen is not active.
     */
    fun recordReceivedTransaction(senderName: String, amountPaise: Long) {
        viewModelScope.launch {
            val tx = TransactionEntity(
                id = java.util.UUID.randomUUID().toString(),
                type = "RECEIVED",
                amountPaise = amountPaise,
                status = "SUCCESS",
                counterpartyName = senderName,
                timestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            )
            homeRepository.insertLocalTransaction(tx)
            
            // Reload balance
            val result = homeRepository.fetchBalance()
            result.onSuccess { balance ->
                _homeState.value = HomeState.Success(balance)
            }

            showReceivedBanner(senderName, amountPaise)
        }
    }

    /**
     * Save the detection mode preference to DataStore.
     */
    fun saveDetectionMode(mode: String) {
        viewModelScope.launch {
            dataStoreManager.saveDetectionMode(mode)
        }
    }

    private val _receivedSuccessMessage = MutableStateFlow<String?>(null)
    val receivedSuccessMessage: StateFlow<String?> = _receivedSuccessMessage.asStateFlow()
}
