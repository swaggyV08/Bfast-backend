package com.bfast.app.ui.payment

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bfast.app.core.hardware.SensorForegroundService
import com.bfast.app.ui.theme.PrimaryBlue
import com.bfast.app.ui.theme.TextSecondaryDark

/**
 * Tap Detection Screen — Pure Scanning UI.
 *
 * This screen shows ONLY a scanning animation + "Tap to Pay" instruction.
 * It does NOT show any receiver name, receiver card, or receiver details.
 *
 * The receiver is discovered silently in the background. The user's only
 * interaction is physically tapping their phone on the receiver's device.
 *
 * When a tap is confirmed by the ConfidenceEngine (score ≥ 70),
 * the screen navigates directly to PaymentEntryScreen with the
 * receiver's identity resolved at that moment.
 *
 * This matches the NFC UX:
 *   1. Open "Pay" → see scanning animation
 *   2. Tap phones together
 *   3. Payment screen appears with receiver's name
 */
@Composable
fun TapDetectionScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPayment: (deviceId: String, receiverName: String) -> Unit,
    viewModel: PaymentViewModel = hiltViewModel()
) {
    val tapConfirmed by viewModel.tapConfirmedForPayment.collectAsState()
    val nearbyDeviceId by viewModel.nearbyReceiverDeviceId.collectAsState()
    val nearbyName by viewModel.nearbyReceiverName.collectAsState()

    // Track whether we've already navigated to prevent double-navigation
    var hasNavigated by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        hasNavigated = false
        viewModel.startSenderMode()
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.clearNearbyReceiver()
        }
    }

    // Navigate to PaymentEntryScreen ONLY when a physical tap is confirmed
    LaunchedEffect(tapConfirmed) {
        if (tapConfirmed && !hasNavigated && nearbyDeviceId != null) {
            hasNavigated = true
            onNavigateToPayment(
                nearbyDeviceId!!,
                nearbyName ?: "Unknown User"
            )
        }
    }

    // ── Animations ──────────────────────────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "scan_pulse")

    // Outer pulse ring
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_scale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_alpha"
    )

    // Second pulse ring (offset timing for layered effect)
    val pulseScale2 by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, 400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_scale2"
    )
    val pulseAlpha2 by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, 400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_alpha2"
    )

    // Tap icon gentle bounce
    val tapBounce by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -6f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "tap_bounce"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0A0E21),
                        Color(0xFF0D1B2A),
                        Color(0xFF0A0E21)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // Back button
        IconButton(
            onClick = {
                SensorForegroundService.isSenderMode.value = false
                SensorForegroundService.resetHandshakeState()
                onNavigateBack()
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Icon(
                Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = Color.White
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            // ── Pulsing scan animation with layered rings ────────────────
            Box(contentAlignment = Alignment.Center) {
                // Outer pulse ring 1
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(PrimaryBlue.copy(alpha = pulseAlpha * 0.25f))
                )
                // Outer pulse ring 2 (delayed)
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .scale(pulseScale2)
                        .clip(CircleShape)
                        .background(PrimaryBlue.copy(alpha = pulseAlpha2 * 0.2f))
                )
                // Inner static ring
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(PrimaryBlue.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    // Tap icon with gentle bounce
                    Text(
                        text = "📱",
                        fontSize = 44.sp,
                        modifier = Modifier.offset(y = tapBounce.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "Ready to Pay",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Tap your phone on the\nreceiver's device to pay",
                color = TextSecondaryDark,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Subtle scanning indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = PrimaryBlue.copy(alpha = 0.5f),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Scanning nearby devices…",
                    color = TextSecondaryDark.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
            }
        }
    }
}
