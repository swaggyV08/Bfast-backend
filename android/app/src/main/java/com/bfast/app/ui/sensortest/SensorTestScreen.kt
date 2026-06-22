package com.bfast.app.ui.sensortest

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bfast.app.core.hardware.SensorForegroundService
import com.bfast.app.ui.theme.BackgroundDark
import com.bfast.app.ui.theme.SurfaceDark
import com.bfast.app.ui.theme.TextSecondaryDark
import kotlin.math.sqrt

val CyanColor = Color(0xFF00E5FF)
val PurpleColor = Color(0xFFAA00FF)
val BlueColor = Color(0xFF2979FF)
val GreenColor = Color(0xFF00E676)
val OrangeColor = Color(0xFFFF9100)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorTestScreen(
    onNavigateBack: () -> Unit,
    viewModel: SensorTestViewModel = hiltViewModel()
) {
    val isRecording by viewModel.isRecording.collectAsState()
    val eventLogs by viewModel.eventLogs.collectAsState()
    val deviceId by viewModel.deviceId.collectAsState()
    val apiConnected by viewModel.apiConnected.collectAsState()
    val isSenderRole by viewModel.isSenderRole.collectAsState()

    // 10 Hz sampled live sensor readings — avoids recomposing at sensor rate (100 Hz)
    var liveData by remember { mutableStateOf<SensorForegroundService.SensorData?>(null) }
    var liveImpulse by remember { mutableStateOf(0.0) }
    var liveGyroMag by remember { mutableStateOf(0.0) }

    // Peak values — held until next session start
    var peakImpulse by remember { mutableStateOf(0.0) }
    var peakGyro by remember { mutableStateOf(0.0) }
    var peakFlash by remember { mutableStateOf(false) }

    // Tap-flash animation
    val flashColor by animateColorAsState(
        targetValue = if (peakFlash) GreenColor else Color.Transparent,
        animationSpec = tween(durationMillis = 300),
        label = "tapFlash"
    )

    LaunchedEffect(isRecording) {
        if (!isRecording) return@LaunchedEffect
        peakImpulse = 0.0
        peakGyro = 0.0
        while (isRecording) {
            liveData = SensorForegroundService.sensorData.value
            liveImpulse = SensorForegroundService.liveImpulse.value
            liveGyroMag = SensorForegroundService.liveGyroMag.value

            // Read peak values from TapDetector
            val td = SensorForegroundService.instance?.tapDetector
            val newPeak = td?.lastPeakAccel ?: 0.0
            val newGyro = td?.lastGyro ?: 0.0
            if (newPeak > peakImpulse) {
                peakImpulse = newPeak
                peakGyro = newGyro
                peakFlash = true
                kotlinx.coroutines.delay(300)
                peakFlash = false
            }
            kotlinx.coroutines.delay(100) // 10 Hz
        }
    }

    val accelX = liveData?.accelX ?: 0f
    val accelY = liveData?.accelY ?: 0f
    val accelZ = liveData?.accelZ ?: 0f
    val gyroX = liveData?.gyroX ?: 0f
    val gyroY = liveData?.gyroY ?: 0f
    val gyroZ = liveData?.gyroZ ?: 0f
    val accelMag = sqrt((accelX * accelX + accelY * accelY + accelZ * accelZ).toDouble())

    val tapEvents by SensorForegroundService.tapEvents.collectAsState()
    LaunchedEffect(tapEvents) {
        val conf = tapEvents
        if (conf != null && conf > 0f) {
            viewModel.addLog("[TAP] Detected — confidence: ${"%.2f".format(conf)}", "Success")
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { viewModel.stopRecording(); onNavigateBack() }) {
                        Text("←", color = Color.White, fontSize = 24.sp)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        "⚡ Sensor Test",
                        color = BlueColor,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.weight(1.5f))
                }
                Text(
                    "Live accel + gyro + tap impulse readings",
                    color = TextSecondaryDark,
                    fontSize = 12.sp
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Status row ─────────────────────────────────────────────────
            item {
                SectionCard(title = "STATUS") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill("Accel: On", true)
                        StatusPill("Gyro: ${if (isRecording) "On" else "Off"}", isRecording)
                        StatusPill("API: ${if (apiConnected) "OK" else "–"}", apiConnected)
                        StatusPill(if (isRecording) "LIVE" else "IDLE", isRecording)
                    }
                }
            }

            // ── Accelerometer card ──────────────────────────────────────────
            item {
                SectionCard(title = "ACCELEROMETER  (m/s²)") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        AxisReading("X", accelX, PurpleColor)
                        AxisReading("Y", accelY, BlueColor)
                        AxisReading("Z", accelZ, PurpleColor)
                        AxisReading("|a|", accelMag.toFloat(), OrangeColor)
                    }
                }
            }

            // ── Gyroscope card ──────────────────────────────────────────────
            item {
                SectionCard(title = "GYROSCOPE  (rad/s)") {
                    if (!isRecording) {
                        Text(
                            "Press START to enable gyroscope readings",
                            color = TextSecondaryDark,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            AxisReading("X", gyroX, CyanColor)
                            AxisReading("Y", gyroY, CyanColor)
                            AxisReading("Z", gyroZ, CyanColor)
                            AxisReading("|ω|", liveGyroMag.toFloat(), GreenColor)
                        }
                    }
                }
            }

            // ── Live impulse + Peak tap ─────────────────────────────────────
            item {
                SectionCard(title = "TAP IMPULSE") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("LIVE IMPULSE", color = TextSecondaryDark, fontSize = 10.sp)
                            Text(
                                "${"%.3f".format(liveImpulse)} m/s²",
                                color = if (liveImpulse > 0.5) OrangeColor else Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("PEAK TAP", color = TextSecondaryDark, fontSize = 10.sp)
                            Text(
                                "${"%.3f".format(peakImpulse)} m/s²",
                                color = flashColor.takeIf { peakFlash } ?: if (peakImpulse > 1.0) GreenColor else Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("PEAK GYRO", color = TextSecondaryDark, fontSize = 10.sp)
                            Text(
                                "${"%.3f".format(peakGyro)} rad/s",
                                color = CyanColor,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    // Impulse bar
                    val clampedImpulse = (liveImpulse / 5.0).coerceIn(0.0, 1.0).toFloat()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .background(SurfaceDark, RoundedCornerShape(5.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(clampedImpulse)
                                .height(10.dp)
                                .background(
                                    when {
                                        clampedImpulse > 0.6f -> GreenColor
                                        clampedImpulse > 0.2f -> OrangeColor
                                        else -> BlueColor
                                    },
                                    RoundedCornerShape(5.dp)
                                )
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        if (!isRecording) "START sensors and tap your phone to see readings"
                        else if (peakImpulse == 0.0) "Tap your phone now..."
                        else "Last tap: ${"%.3f".format(peakImpulse)} m/s²  |  gyro: ${"%.3f".format(peakGyro)} rad/s",
                        color = TextSecondaryDark,
                        fontSize = 11.sp
                    )
                }
            }

            // ── Config ─────────────────────────────────────────────────────
            item {
                SectionCard(title = "CONFIGURATION") {
                    ConfigRow("Device ID", deviceId)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Role", color = TextSecondaryDark, fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (isSenderRole) "Sender" else "Receiver",
                                color = Color.White,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { viewModel.toggleRole() },
                                colors = ButtonDefaults.buttonColors(containerColor = BlueColor),
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text("Toggle", fontSize = 10.sp)
                            }
                        }
                    }
                    ConfigRow("Tap threshold ceiling", "1.0 m/s²")
                    ConfigRow("Proximity (arm)", "0–5 cm  (proxScore ≥ 30, RSSI ≥ -75)")
                }
            }

            // ── Controls ───────────────────────────────────────────────────
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.toggleRecording() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) Color(0xFFB71C1C) else PurpleColor
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            if (isRecording) "■  STOP  (disables gyro)" else "▶  START  (enables gyro + tap detection)",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.testApiConnection() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondaryDark),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            TextSecondaryDark.copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("🔌 Test API Connection")
                    }
                }
            }

            // ── Event log ──────────────────────────────────────────────────
            item {
                SectionCard(title = "EVENT LOG") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .background(Color.Black, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        LazyColumn {
                            items(eventLogs) { log ->
                                val color = when (log.colorType) {
                                    "Success" -> GreenColor
                                    "Error" -> Color(0xFFFF5252)
                                    else -> TextSecondaryDark
                                }
                                Text(
                                    log.text,
                                    color = color,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            color = TextSecondaryDark,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}

@Composable
fun AxisReading(axis: String, value: Float, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(axis, color = TextSecondaryDark, fontSize = 11.sp)
        Text(
            "%+.2f".format(value),
            color = color,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun StatusPill(text: String, isOk: Boolean) {
    Box(
        modifier = Modifier
            .background(
                color = if (isOk) Color(0xFF1B5E20) else Color(0xFF37474F),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text,
            color = if (isOk) Color(0xFF81C784) else TextSecondaryDark,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun ConfigRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextSecondaryDark, fontSize = 12.sp)
        Text(
            value,
            color = Color.White,
            fontSize = 11.sp,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
