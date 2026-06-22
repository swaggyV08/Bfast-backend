package com.bfast.app.ui.payment

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.bfast.app.core.hardware.DiscoveredDevice
import com.bfast.app.core.hardware.SensorForegroundService
import com.bfast.app.ui.theme.PrimaryBlue
import com.bfast.app.ui.theme.TextSecondaryDark

@Composable
fun TapDetectionScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPayment: (deviceId: String, receiverName: String) -> Unit,
    viewModel: PaymentViewModel = hiltViewModel()
) {
    val tapConfirmed by viewModel.tapConfirmedForPayment.collectAsState()
    // Read nearbyReceivers directly from SensorForegroundService to avoid
    // the async ViewModel collection lag that caused the race condition.
    val nearbyReceivers by SensorForegroundService.nearbyReceivers.collectAsState()

    var hasNavigated by remember { mutableStateOf(false) }
    var selectedReceiver by remember { mutableStateOf<DiscoveredDevice?>(null) }

    LaunchedEffect(Unit) {
        hasNavigated = false
        selectedReceiver = null
        viewModel.startSenderMode()
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.clearNearbyReceiver() }
    }

    // Fix: read receiver identity directly from SensorForegroundService at the moment
    // of navigation — avoids the race where nearbyDeviceId was null in the ViewModel.
    LaunchedEffect(tapConfirmed) {
        if (tapConfirmed && !hasNavigated) {
            val receiver = SensorForegroundService.nearbyReceiver.value
                ?: SensorForegroundService.outgoingPaymentMatch.value
            if (receiver != null) {
                hasNavigated = true
                onNavigateToPayment(receiver.deviceId, receiver.displayName)
            }
        }
    }

    // ── Animations ──────────────────────────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "scan_pulse")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.8f,
        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "pulse_scale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "pulse_alpha"
    )
    val pulseScale2 by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.5f,
        animationSpec = infiniteRepeatable(tween(1500, 400, FastOutSlowInEasing), RepeatMode.Restart),
        label = "pulse_scale2"
    )
    val pulseAlpha2 by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1500, 400, FastOutSlowInEasing), RepeatMode.Restart),
        label = "pulse_alpha2"
    )
    val tapBounce by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = -6f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "tap_bounce"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0A0E21), Color(0xFF0D1B2A), Color(0xFF0A0E21))
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
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            // ── Pulsing scan animation ───────────────────────────────────
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier.size(180.dp).scale(pulseScale).clip(CircleShape)
                        .background(PrimaryBlue.copy(alpha = pulseAlpha * 0.25f))
                )
                Box(
                    modifier = Modifier.size(180.dp).scale(pulseScale2).clip(CircleShape)
                        .background(PrimaryBlue.copy(alpha = pulseAlpha2 * 0.2f))
                )
                Box(
                    modifier = Modifier.size(120.dp).clip(CircleShape)
                        .background(PrimaryBlue.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "📱", fontSize = 44.sp, modifier = Modifier.offset(y = tapBounce.dp))
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text("Ready to Pay", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Tap your phone on the\nreceiver's device to pay",
                color = TextSecondaryDark,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            // ── Multiple receiver picker ─────────────────────────────────
            when {
                nearbyReceivers.size > 1 -> {
                    // More than one receiver nearby — show a list to choose from
                    Text(
                        text = "${nearbyReceivers.size} receivers nearby — select one:",
                        color = PrimaryBlue,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(nearbyReceivers) { device ->
                            ReceiverListItem(
                                device = device,
                                isSelected = selectedReceiver?.deviceId == device.deviceId,
                                onClick = {
                                    selectedReceiver = device
                                    // Lock the state machine onto this specific receiver
                                    SensorForegroundService.nearbyReceiver.value?.let { current ->
                                        if (current.deviceId != device.deviceId) {
                                            SensorForegroundService.resetHandshakeState()
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
                nearbyReceivers.size == 1 -> {
                    // Single receiver — show their name as a status
                    val r = nearbyReceivers.first()
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = PrimaryBlue.copy(alpha = 0.15f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text("📲", fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(r.displayName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("Tap phones together to pay", color = TextSecondaryDark, fontSize = 11.sp)
                            }
                        }
                    }
                }
                else -> {
                    // No receiver detected yet — show scanning indicator
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
                            text = "Scanning for receiver…",
                            color = TextSecondaryDark.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceiverListItem(
    device: DiscoveredDevice,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) PrimaryBlue.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.06f),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape)
                    .background(PrimaryBlue.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(device.displayName.take(1).uppercase(), color = PrimaryBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.displayName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("Signal: ${device.rssi} dBm", color = TextSecondaryDark, fontSize = 11.sp)
            }
            if (isSelected) {
                Text("✓", color = PrimaryBlue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
