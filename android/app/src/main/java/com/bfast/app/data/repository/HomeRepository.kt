package com.bfast.app.data.repository

import com.bfast.app.data.local.db.TransactionDao
import com.bfast.app.data.local.db.TransactionEntity
import com.bfast.app.data.remote.BFastApi
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeRepository @Inject constructor(
    private val api: BFastApi,
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
            Result.failure(e)
        }
    }

    suspend fun syncTransactions(): Result<Unit> {
        return try {
            val response = api.getTransactions()
            if (response.isSuccessful && response.body()?.success == true) {
                val transactions = response.body()?.data?.transactions ?: emptyList()
                val entities = transactions.map {
                    TransactionEntity(
                        id = it.id,
                        type = it.type,
                        amountPaise = it.amountPaise,
                        status = it.status,
                        counterpartyName = it.counterpartyName,
                        timestamp = it.timestamp
                    )
                }
                transactionDao.insertTransactions(entities)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to sync transactions"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getLocalTransactions(): Flow<List<TransactionEntity>> {
        return transactionDao.getAllTransactions()
    }

    suspend fun insertLocalTransaction(tx: TransactionEntity) {
        transactionDao.insertTransaction(tx)
    }
}
