package com.bfast.app.ui.sensortest

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorTestScreen(
    onNavigateBack: () -> Unit,
    viewModel: SensorTestViewModel = hiltViewModel()
) {
    val isRecording by viewModel.isRecording.collectAsState()
    val eventLogs by viewModel.eventLogs.collectAsState()
    val deviceId by viewModel.deviceId.collectAsState()
    val sensorData by SensorForegroundService.sensorData.collectAsState()
    val tapEvents by SensorForegroundService.tapEvents.collectAsState()

    val apiConnected by viewModel.apiConnected.collectAsState()
    val isSenderRole by viewModel.isSenderRole.collectAsState()

    // Freeze readings unless started or just tapped
    var frozenData by remember { mutableStateOf(sensorData) }
    var readingCount by remember { mutableStateOf(0) }

    LaunchedEffect(sensorData) {
        if (isRecording) {
            frozenData = sensorData
            readingCount++
            // Auto stop after 5 consecutive readings for accurate capture
            if (readingCount >= 5) {
                viewModel.stopRecording()
                readingCount = 0
            }
        }
    }

    // Unfreeze for a single reading if a manual tap is detected
    LaunchedEffect(tapEvents) {
        if (tapEvents != null && tapEvents!! > 0f) {
            frozenData = sensorData
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onNavigateBack) {
                        Text("←", color = Color.White, fontSize = 24.sp)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text("⚡ BFast Sensor Test", color = BlueColor, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.weight(1.5f))
                }
                Text("Tap your phone to test accelerometer + gyroscope bump detection", color = TextSecondaryDark, fontSize = 12.sp)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SectionCard(title = "📡 SENSOR STATUS") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill("Accel: On", true)
                        StatusPill("Gyro: On", true)
                        StatusPill(if (apiConnected) "API: Connected" else "API: Not connected", apiConnected)
                    }
                }
            }

            item {
                SectionCard(title = "📊 LIVE SENSOR READINGS") {
                    val data = frozenData
                    val accelX = data?.accelX ?: 0f
                    val accelY = data?.accelY ?: 0f
                    val accelZ = data?.accelZ ?: 0f
                    val gyroX = data?.gyroX ?: 0f
                    val gyroY = data?.gyroY ?: 0f
                    val gyroZ = data?.gyroZ ?: 0f

                    val accelMag = sqrt(accelX*accelX + accelY*accelY + accelZ*accelZ)
                    val gyroMag = sqrt(gyroX*gyroX + gyroY*gyroY + gyroZ*gyroZ)

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ReadingColumn("Accel X", accelX, PurpleColor, "Gyro X", gyroX, CyanColor)
                        ReadingColumn("Accel Y", accelY, BlueColor, "Gyro Y", gyroY, CyanColor)
                        ReadingColumn("Accel Z", accelZ, PurpleColor, "Gyro Z", gyroZ, CyanColor)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Magnitude", color = TextSecondaryDark, fontSize = 10.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("|a| = ${"%.2f".format(accelMag)}", color = PurpleColor, fontWeight = FontWeight.Bold)
                            Text("|w| = ${"%.2f".format(gyroMag)}", color = CyanColor, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .border(1.dp, TextSecondaryDark.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .background(SurfaceDark, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("👆", fontSize = 32.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Tap or bump your phone to detect", color = TextSecondaryDark, fontSize = 14.sp)
                    }
                }
            }

            item {
                SectionCard(title = "⚙ CONFIGURATION") {
                    ConfigRow("Backend URL", "http://192.168.0.147:3000")
                    ConfigRow("Device ID", deviceId)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Role", color = TextSecondaryDark, fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (isSenderRole) "Sender" else "Receiver", color = Color.White, fontSize = 12.sp)
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
                    ConfigRow("Tap Threshold (m/s²)", "4.0")
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.toggleRecording() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PurpleColor),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(if (isRecording) "■ Stop Sensors" else "▶ Start Sensors", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { viewModel.sendLastTap(tapEvents) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BFA5)), // Teal
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("📤 Send Last Tap as Bump", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { viewModel.testApiConnection() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondaryDark),
                        border = androidx.compose.foundation.BorderStroke(1.dp, TextSecondaryDark.copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("🔌 Test API Connection")
                    }
                }
            }

            item {
                SectionCard(title = "📋 EVENT LOG") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .background(Color.Black, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        LazyColumn {
                            items(eventLogs) { log ->
                                val color = when (log.colorType) {
                                    "Success" -> Color(0xFF00E676)
                                    "Error" -> Color(0xFFFF5252)
                                    else -> TextSecondaryDark
                                }
                                Text(log.text, color = color, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
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
        Text(title, color = TextSecondaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
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
fun StatusPill(text: String, isOk: Boolean) {
    Box(
        modifier = Modifier
            .background(
                color = if (isOk) Color(0xFF1B5E20) else Color(0xFFB71C1C),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(text, color = if (isOk) Color(0xFF81C784) else Color(0xFFE57373), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ReadingColumn(titleA: String, valA: Float, colorA: Color, titleG: String, valG: Float, colorG: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(titleA, color = TextSecondaryDark, fontSize = 10.sp)
        Text("%.2f".format(valA), color = colorA, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(titleG, color = TextSecondaryDark, fontSize = 10.sp)
        Text("%.2f".format(valG), color = colorG, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ConfigRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextSecondaryDark, fontSize = 12.sp)
        Box(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                .border(1.dp, TextSecondaryDark.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(value, color = Color.White, fontSize = 12.sp)
        }
    }
}
