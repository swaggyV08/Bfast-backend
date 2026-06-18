package com.bfast.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TransactionEntity::class],
    version = 1,
    exportSchema = false
)
abstract class BFastDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
}
