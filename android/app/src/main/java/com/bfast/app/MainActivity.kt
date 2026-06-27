package com.bfast.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import dagger.hilt.android.AndroidEntryPoint

import com.bfast.app.core.hardware.SensorForegroundService
import com.bfast.app.ui.theme.BFastTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BFastTheme {
                Surface(
                    modifier   = androidx.compose.ui.Modifier.fillMaxSize(),
                    color      = MaterialTheme.colorScheme.background
                ) {
                    val navController =
                        androidx.navigation.compose.rememberNavController()
                    com.bfast.app.ui.navigation.BFastNavGraph(
                        navController = navController)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        sendServiceCommand(SensorForegroundService.ACTION_PAUSE)
    }

    override fun onResume() {
        super.onResume()
        sendServiceCommand(SensorForegroundService.ACTION_RESUME)
    }

    private fun sendServiceCommand(action: String) {
        val intent = Intent(this, SensorForegroundService::class.java).apply {
            this.action = action
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
