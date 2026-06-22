package com.bfast.app.ui.navigation

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Login : Screen("login")
    object Home : Screen("home")
    object TapDetection : Screen("tap_detection")
    object Payment : Screen("payment")
    object Processing : Screen("processing")
    object Result : Screen("result")
    object SensorTest : Screen("sensor_test")
}

@Composable
fun BFastNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: String = Screen.Onboarding.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable(Screen.Onboarding.route) {
            com.bfast.app.ui.auth.OnboardingScreen(
                onNavigateToHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                },
                onNavigateToLogin = { navController.navigate(Screen.Login.route) }
            )
        }
        composable(Screen.Login.route) {
            com.bfast.app.ui.auth.LoginScreen(
                onNavigateToHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                },
                onNavigateToRegister = { navController.navigate(Screen.Onboarding.route) }
            )
        }
        composable(Screen.Home.route) {
            // Fix: Back press on Home should exit the app, not go to login
            val context = LocalContext.current
            BackHandler {
                (context as? Activity)?.finish()
            }

            com.bfast.app.ui.home.HomeScreen(
                onNavigateToTapDetection = { navController.navigate(Screen.TapDetection.route) },
                onNavigateToSensorTest = { navController.navigate(Screen.SensorTest.route) }
            )
        }
        // ── Unified Tap Detection Screen (Layer 13) ─────────────────────────
        // Shows scanning → receiver card → armed state inline.
        // Navigates directly to Payment on TAP_CONFIRMED.
        // No separate ReceiverDetected screen.
        composable(Screen.TapDetection.route) {
            com.bfast.app.ui.payment.TapDetectionScreen(
                onNavigateBack = {
                    com.bfast.app.core.hardware.SensorForegroundService.isSenderMode.value = false
                    com.bfast.app.core.hardware.SensorForegroundService.resetHandshakeState()
                    navController.popBackStack()
                },
                onNavigateToPayment = { deviceId, receiverName ->
                    navController.navigate("${Screen.Payment.route}/$deviceId/$receiverName") {
                        // Pop TapDetection so back from Payment goes to Home
                        popUpTo(Screen.Home.route)
                    }
                }
            )
        }
        composable("${Screen.Payment.route}/{deviceId}/{receiverName}") { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getString("deviceId") ?: ""
            val receiverName = backStackEntry.arguments?.getString("receiverName") ?: ""
            com.bfast.app.ui.payment.PaymentEntryScreen(
                targetDeviceId = deviceId,
                receiverName = receiverName,
                onNavigateBack = {
                    com.bfast.app.core.hardware.SensorForegroundService.isSenderMode.value = false
                    com.bfast.app.core.hardware.SensorForegroundService.resetHandshakeState()
                    navController.popBackStack()
                },
                onNavigateToProcessing = { amountPaise ->
                    navController.navigate("${Screen.Processing.route}/$deviceId/$receiverName/$amountPaise")
                }
            )
        }
        composable("${Screen.Processing.route}/{deviceId}/{receiverName}/{amount}") { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getString("deviceId") ?: ""
            val receiverName = backStackEntry.arguments?.getString("receiverName") ?: ""
            val amount = backStackEntry.arguments?.getString("amount")?.toLongOrNull() ?: 0L
            com.bfast.app.ui.payment.ProcessingScreen(
                targetDeviceId = deviceId,
                receiverName = receiverName,
                amountPaise = amount,
                onNavigateToResult = { success, message ->
                    navController.navigate("${Screen.Result.route}/$success/$message") {
                        popUpTo(Screen.Home.route)
                    }
                }
            )
        }
        composable("${Screen.Result.route}/{success}/{message}") { backStackEntry ->
            val success = backStackEntry.arguments?.getString("success")?.toBoolean() ?: false
            val message = backStackEntry.arguments?.getString("message") ?: ""
            com.bfast.app.ui.payment.ResultScreen(
                success = success,
                message = message,
                onNavigateHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.SensorTest.route) {
            com.bfast.app.ui.sensortest.SensorTestScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
