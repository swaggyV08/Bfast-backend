package com.bfast.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class BFastApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialization logic (like logging) will go here
    }
}
