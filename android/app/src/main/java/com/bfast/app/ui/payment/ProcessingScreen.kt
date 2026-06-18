package com.bfast.app.ui.payment

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bfast.app.ui.theme.BackgroundDark
import com.bfast.app.ui.theme.TextSecondaryDark

@Composable
fun ProcessingScreen(
    targetDeviceId: String,
    receiverName: String,
    amountPaise: Long,
    onNavigateToResult: (Boolean, String) -> Unit,
    viewModel: PaymentViewModel = hiltViewModel()
) {
    val paymentState by viewModel.paymentState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.processPayment(targetDeviceId, receiverName, amountPaise)
    }

    LaunchedEffect(paymentState) {
        when (paymentState) {
            is PaymentState.Success -> onNavigateToResult(true, "Payment Successful")
            is PaymentState.Error -> onNavigateToResult(false, (paymentState as PaymentState.Error).message)
            else -> {}
        }
    }

    // Animation for the purple scan line
    val infiniteTransition = rememberInfiniteTransition()
    val scanLineOffset by infiniteTransition.animateFloat(
        initialValue = -300f,
        targetValue = 300f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {}
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Avatar
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.Gray)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text("Paying $receiverName", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)

                Spacer(modifier = Modifier.height(32.dp))

                // Amount
                Text(
                    text = "${amountPaise / 100}",
                    color = Color.White,
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Light
                )

                Spacer(modifier = Modifier.height(32.dp))

                Text("Processing Payment", color = TextSecondaryDark, fontSize = 16.sp)
            }

            // Purple Scan Line
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.Center)
                    .offset(y = scanLineOffset.dp)
                    .background(Color(0xFF9C27B0))
            )
        }
    }
}

