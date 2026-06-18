package com.bfast.app.di

import android.content.Context
import androidx.room.Room
import com.bfast.app.data.local.db.BFastDatabase
import com.bfast.app.data.local.db.TransactionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BFastDatabase {
        return Room.databaseBuilder(
            context,
            BFastDatabase::class.java,
            "bfast_db"
        ).build()
    }

    @Provides
    @Singleton
    fun provideTransactionDao(database: BFastDatabase): TransactionDao {
        return database.transactionDao()
    }
}
