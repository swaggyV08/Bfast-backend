package com.bfast.app.ui.payment

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
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
import com.bfast.app.ui.theme.BackgroundDark
import com.bfast.app.ui.theme.PrimaryBlue
import com.bfast.app.ui.theme.SuccessGreen
import com.bfast.app.ui.theme.TextSecondaryDark

/**
 * Phase 2: Receiver Detected — Confirmation Screen.
 *
 * Shows receiver details (name, bank info, device ID) and instructs
 * the sender to physically tap their phone on the receiver's device.
 *
 * CRITICAL: Payment screen is ONLY triggered when tapConfirmedForPayment
 * becomes true (set by ConfidenceEngine in SensorForegroundService after
 * a real physical tap passes all gates).
 *
 * This screen prevents false payment triggers from:
 * - Phone waving in air
 * - Incidental BLE proximity without physical tap
 * - Stale handshake state from previous interactions
 */
@Composable
fun ReceiverDetectedScreen(
    deviceId: String,
    receiverName: String,
    onNavigateBack: () -> Unit,
    onNavigateToPayment: (deviceId: String, receiverName: String) -> Unit,
    viewModel: PaymentViewModel = hiltViewModel()
) {
    val tapConfirmed by viewModel.tapConfirmedForPayment.collectAsState()

    // Track whether we've already navigated to prevent double-navigation
    var hasNavigated by remember { mutableStateOf(false) }

    // Reset tap confirmed state when entering this screen to prevent stale triggers
    LaunchedEffect(Unit) {
        hasNavigated = false
        // Clear any stale tap confirmation from previous sessions
        SensorForegroundService.resetTapConfirmation()
    }

    DisposableEffect(Unit) {
        onDispose {
            // Don't stop sender mode here — it continues into PaymentEntryScreen
        }
    }

    // ONLY navigate to payment when a REAL physical tap is confirmed
    LaunchedEffect(tapConfirmed) {
        if (tapConfirmed && !hasNavigated) {
            hasNavigated = true
            onNavigateToPayment(deviceId, receiverName)
        }
    }

    // ── Animations ──────────────────────────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "tap_prompt")

    // Pulsing animation for tap icon
    val tapPulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "tap_pulse"
    )

    // Glow ring animation
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    // Subtle arrow bounce for "tap" instruction
    val arrowOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "arrow_bounce"
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
            )
    ) {
        // Back button
        IconButton(
            onClick = {
                // Reset handshake state to prevent re-triggering
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
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // ── "Receiver Detected" badge ────────────────────────────────
            Box(
                modifier = Modifier
                    .background(
                        SuccessGreen.copy(alpha = 0.15f),
                        RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = SuccessGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Receiver Detected",
                        color = SuccessGreen,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // ── Receiver Avatar with glow ────────────────────────────────
            Box(contentAlignment = Alignment.Center) {
                // Glow ring
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(PrimaryBlue.copy(alpha = glowAlpha * 0.3f))
                )
                // Avatar circle
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    PrimaryBlue,
                                    Color(0xFF6366F1) // indigo
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = receiverName.take(1).uppercase(),
                        color = Color.White,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Receiver Name ────────────────────────────────────────────
            Text(
                text = receiverName,
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Receiver Details Card ────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1A2332))
                    .border(
                        1.dp,
                        Color.White.copy(alpha = 0.08f),
                        RoundedCornerShape(16.dp)
                    )
                    .padding(20.dp)
            ) {
                Column {
                    ReceiverDetailRow(
                        label = "Bank",
                        value = "BFast Wallet"
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(
                        color = Color.White.copy(alpha = 0.06f),
                        thickness = 1.dp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    ReceiverDetailRow(
                        label = "BFast ID",
                        value = deviceId.take(12)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(
                        color = Color.White.copy(alpha = 0.06f),
                        thickness = 1.dp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    ReceiverDetailRow(
                        label = "Status",
                        value = "Ready to Receive",
                        valueColor = SuccessGreen
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // ── Tap Instruction ──────────────────────────────────────────
            Box(
                modifier = Modifier
                    .scale(tapPulseScale)
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                PrimaryBlue.copy(alpha = 0.4f),
                                PrimaryBlue.copy(alpha = 0.1f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "👆",
                    fontSize = 32.sp,
                    modifier = Modifier.offset(y = (-arrowOffset).dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Tap on Receiver's Phone",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Physically tap your phone on ${receiverName}'s\ndevice to initiate the payment",
                color = TextSecondaryDark,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )
        }
    }
}

@Composable
private fun ReceiverDetailRow(
    label: String,
    value: String,
    valueColor: Color = Color.White
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = TextSecondaryDark,
            fontSize = 14.sp
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
