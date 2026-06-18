package com.bfast.app.ui.payment

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun TapDetectionScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPayment: (deviceId: String, receiverName: String) -> Unit,
    viewModel: PaymentViewModel = hiltViewModel()
) {
    val matchedDeviceId by viewModel.matchedDeviceId.collectAsState()
    val matchedName by viewModel.matchedName.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.startSenderMode()
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopSenderMode()
        }
    }

    LaunchedEffect(matchedDeviceId) {
        if (matchedDeviceId != null) {
            onNavigateToPayment(matchedDeviceId!!, matchedName ?: "Unknown User")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "Waiting for tap detection",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
