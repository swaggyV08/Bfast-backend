package com.bfast.app.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey val id: String,
    val type: String,
    val amountPaise: Long,
    val status: String,
    val counterpartyName: String?,
    val timestamp: String
)
