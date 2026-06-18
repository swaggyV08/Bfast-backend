package com.bfast.app.data.repository

import com.bfast.app.data.local.DataStoreManager
import com.bfast.app.data.local.db.TransactionDao
import com.bfast.app.data.local.db.TransactionEntity
import com.bfast.app.data.remote.BFastApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PaymentRepository @Inject constructor(
    private val api: BFastApi,
    private val dataStoreManager: DataStoreManager,
    private val transactionDao: TransactionDao
) {
    suspend fun fetchBalance(): Result<Long> {
        return try {
            val sent = transactionDao.getTotalSent() ?: 0L
            val received = transactionDao.getTotalReceived() ?: 0L
            val baseBalance = 50000L // Start with 500 Rs dummy balance
            val currentBalance = baseBalance - sent + received
            Result.success(currentBalance)
        } catch (e: Exception) {
            Result.success(50000L)
        }
    }

    suspend fun processPayment(targetDeviceId: String, targetName: String, amountPaise: Long): Result<Unit> {
        return try {
            // TODO: Replace with real API call when backend is ready
            kotlinx.coroutines.delay(1500)

            // Save SENT transaction locally
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val tx = TransactionEntity(
                id = UUID.randomUUID().toString(),
                type = "SENT",
                amountPaise = amountPaise,
                status = "SUCCESS",
                counterpartyName = targetName,
                timestamp = timestamp
            )
            transactionDao.insertTransaction(tx)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun recordReceivedPayment(senderName: String, amountPaise: Long): Result<Unit> {
        return try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val tx = TransactionEntity(
                id = UUID.randomUUID().toString(),
                type = "RECEIVED",
                amountPaise = amountPaise,
                status = "SUCCESS",
                counterpartyName = senderName,
                timestamp = timestamp
            )
            transactionDao.insertTransaction(tx)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
