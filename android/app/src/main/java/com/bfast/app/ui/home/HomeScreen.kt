package com.bfast.app.ui.home

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import androidx.core.content.ContextCompat
import android.net.NetworkCapabilities
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bfast.app.core.hardware.DetectionMode
import com.bfast.app.core.hardware.SensorForegroundService
import com.bfast.app.data.local.db.TransactionEntity
import com.bfast.app.ui.theme.BackgroundNavy
import com.bfast.app.ui.theme.PrimaryBlue
import com.bfast.app.ui.theme.SuccessGreen
import com.bfast.app.ui.theme.ErrorRed
import com.bfast.app.ui.theme.TextSecondaryDark
import com.bfast.app.ui.theme.SurfaceDark
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToTapDetection: () -> Unit,
    onNavigateToSensorTest: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val homeState by viewModel.homeState.collectAsState()
    val transactions by viewModel.transactions.collectAsState()
    val displayName by viewModel.displayName.collectAsState()
    val paymentReceivedEvent by SensorForegroundService.paymentReceivedEvent.collectAsState()
    val receivedSuccessMessage by viewModel.receivedSuccessMessage.collectAsState()
    val detectionMode by SensorForegroundService.detectionMode.collectAsState()

    fun startSensorServiceIfPermitted() {
        try {
            val hasBle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
            if (hasBle) {
                val serviceIntent = Intent(context, SensorForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Track when payment is completely received offline over BLE
    // Note: The actual transaction recording + notification now happens in SensorForegroundService.
    // This LaunchedEffect only handles the in-app UI banner.
    LaunchedEffect(paymentReceivedEvent) {
        val event = paymentReceivedEvent
        if (event != null) {
            val amountPaise = event.displayName.toLongOrNull() ?: 50000L
            val senderId = event.deviceId

            // Show in-app success banner
            viewModel.showReceivedBanner("Sender ($senderId)", amountPaise)

            // Refresh balance to show updated wallet
            viewModel.refreshBalance()

            SensorForegroundService.resetHandshakeState()
        }
    }

    // Bluetooth & Internet status
    var isBluetoothOn by remember { mutableStateOf(true) }
    var isInternetOn by remember { mutableStateOf(true) }

    // Check status periodically
    LaunchedEffect(Unit) {
        viewModel.loadData()
        while (true) {
            isBluetoothOn = checkBluetoothEnabled(context)
            isInternetOn = checkInternetAvailable(context)
            viewModel.refreshBalance()
            kotlinx.coroutines.delay(3000)
        }
    }

    // Request BLE + Notification permissions on HomeScreen entry
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> 
        startSensorServiceIfPermitted()
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            ))
        } else {
            permissions.addAll(listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }

        // Android 13+ requires POST_NOTIFICATIONS permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        startSensorServiceIfPermitted()

        permissionLauncher.launch(permissions.toTypedArray())
    }

    // UWB error snackbar
    var showUwbError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = BackgroundNavy
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Detection Mode Toggle ───────────────────────────────────
            DetectionModeToggle(
                currentMode = detectionMode,
                onModeChange = { newMode ->
                    val error = SensorForegroundService.setDetectionMode(newMode)
                    if (error != null) {
                        showUwbError = error
                    } else {
                        viewModel.saveDetectionMode(newMode.name)
                    }
                }
            )

            // UWB Error Banner
            if (showUwbError != null) {
                StatusBanner(
                    text = showUwbError!!,
                    backgroundColor = Color(0xFFFF9800).copy(alpha = 0.9f),
                    icon = Icons.Default.Info
                )
                // Auto-dismiss after 5 seconds
                LaunchedEffect(showUwbError) {
                    kotlinx.coroutines.delay(5000)
                    showUwbError = null
                }
            }

            // Bluetooth OFF banner
            if (!isBluetoothOn) {
                StatusBanner(
                    text = "Bluetooth is OFF — Tap to Pay won't work. Please turn on Bluetooth in your phone settings.",
                    backgroundColor = ErrorRed.copy(alpha = 0.9f)
                )
            }

            // Internet OFF banner
            if (!isInternetOn) {
                StatusBanner(
                    text = "No Internet connection — Wallet sync is paused. Your balance may not be up to date.",
                    backgroundColor = Color(0xFFFF9800).copy(alpha = 0.9f)
                )
            }

            // Received Success banner
            if (receivedSuccessMessage != null) {
                StatusBanner(
                    text = receivedSuccessMessage!!,
                    backgroundColor = SuccessGreen.copy(alpha = 0.9f)
                )
            }

            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(PrimaryBlue.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = displayName.take(1).uppercase(),
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text("Welcome Back,", color = TextSecondaryDark, fontSize = 14.sp)
                        Text(
                            "$displayName!",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .border(1.dp, TextSecondaryDark.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Wallet Balance
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Wallet Balance", color = TextSecondaryDark, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(8.dp))
                val balanceText = when (homeState) {
                    is HomeState.Success -> {
                        val balance = (homeState as HomeState.Success).balancePaise
                        NumberFormat.getNumberInstance(Locale("en", "US")).apply {
                            minimumFractionDigits = 2
                            maximumFractionDigits = 2
                        }.format(balance / 100.0)
                    }
                    else -> "0.00"
                }
                Text(balanceText, color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Action Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = onNavigateToTapDetection,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Text("Send Money", fontSize = 16.sp)
                }
                Button(
                    onClick = onNavigateToSensorTest,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    border = androidx.compose.foundation.BorderStroke(1.dp, TextSecondaryDark.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Text("Sensor Test", fontSize = 16.sp, color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Transactions Bottom Sheet area
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                    .background(SurfaceDark.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                ) {
                    Text("Transactions", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)

                    Spacer(modifier = Modifier.height(16.dp))

                    var statusFilter by remember { mutableStateOf("All") }
                    var modeFilter by remember { mutableStateOf("All") }
                    var dateFilter by remember { mutableStateOf("All") }
                    
                    // Simple Dropdown states
                    var showStatusMenu by remember { mutableStateOf(false) }
                    var showModeMenu by remember { mutableStateOf(false) }
                    var showDateMenu by remember { mutableStateOf(false) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Status
                        Box {
                            FilterChip(
                                text = if (statusFilter == "All") "Status" else statusFilter,
                                onClick = { showStatusMenu = true }
                            )
                            DropdownMenu(expanded = showStatusMenu, onDismissRequest = { showStatusMenu = false }) {
                                DropdownMenuItem(text = { Text("All") }, onClick = { statusFilter = "All"; showStatusMenu = false })
                                DropdownMenuItem(text = { Text("Successful") }, onClick = { statusFilter = "Successful"; showStatusMenu = false })
                                DropdownMenuItem(text = { Text("Failed") }, onClick = { statusFilter = "Failed"; showStatusMenu = false })
                                DropdownMenuItem(text = { Text("In Progress") }, onClick = { statusFilter = "In Progress"; showStatusMenu = false })
                            }
                        }
                        // Mode
                        Box {
                            FilterChip(
                                text = if (modeFilter == "All") "Payment Mode" else modeFilter,
                                onClick = { showModeMenu = true }
                            )
                            DropdownMenu(expanded = showModeMenu, onDismissRequest = { showModeMenu = false }) {
                                DropdownMenuItem(text = { Text("All") }, onClick = { modeFilter = "All"; showModeMenu = false })
                                DropdownMenuItem(text = { Text("Bfast") }, onClick = { modeFilter = "Bfast"; showModeMenu = false })
                                DropdownMenuItem(text = { Text("Baft") }, onClick = { modeFilter = "Baft"; showModeMenu = false })
                            }
                        }
                        // Date
                        Box {
                            FilterChip(
                                text = if (dateFilter == "All") "Date" else dateFilter,
                                onClick = { showDateMenu = true }
                            )
                            DropdownMenu(expanded = showDateMenu, onDismissRequest = { showDateMenu = false }) {
                                DropdownMenuItem(text = { Text("All") }, onClick = { dateFilter = "All"; showDateMenu = false })
                                DropdownMenuItem(text = { Text("1W Back") }, onClick = { dateFilter = "1W Back"; showDateMenu = false })
                                DropdownMenuItem(text = { Text("1M Back") }, onClick = { dateFilter = "1M Back"; showDateMenu = false })
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    
                    val filteredTransactions = transactions.filter { tx ->
                        val matchesStatus = when (statusFilter) {
                            "Successful" -> tx.status == "SUCCESS"
                            "Failed" -> tx.status == "FAILED"
                            "In Progress" -> tx.status == "PENDING"
                            else -> true
                        }
                        // For demo, assume all current transactions are "Bfast"
                        val matchesMode = when (modeFilter) {
                            "Bfast" -> true 
                            "Baft" -> false // We don't have Baft transactions yet
                            else -> true
                        }
                        
                        // Simple date filtering (1w, 1m) based on timestamp length comparison or simple logic.
                        // For now we'll just allow all through unless we parse the dates
                        matchesStatus && matchesMode
                    }

                    if (filteredTransactions.isEmpty()) {
                        // Empty state
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No transactions yet.\nSend money to get started!",
                                color = TextSecondaryDark,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(filteredTransactions) { tx ->
                                TransactionItem(tx)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Detection Mode Toggle (Segmented Control) ──────────────────────────────

@Composable
fun DetectionModeToggle(
    currentMode: DetectionMode,
    onModeChange: (DetectionMode) -> Unit
) {
    val isAccelMode = currentMode == DetectionMode.ACCEL_GYRO_BLE

    val accelBgColor by animateColorAsState(
        targetValue = if (isAccelMode) PrimaryBlue else Color.Transparent,
        animationSpec = tween(300),
        label = "accelBg"
    )
    val uwbBgColor by animateColorAsState(
        targetValue = if (!isAccelMode) PrimaryBlue else Color.Transparent,
        animationSpec = tween(300),
        label = "uwbBg"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(SurfaceDark.copy(alpha = 0.6f))
                .border(1.dp, TextSecondaryDark.copy(alpha = 0.3f), RoundedCornerShape(22.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Accel + Gyro + BLE option
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(3.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(accelBgColor)
                    .clickable { onModeChange(DetectionMode.ACCEL_GYRO_BLE) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Accel+Gyro+BLE",
                    color = if (isAccelMode) Color.White else TextSecondaryDark,
                    fontSize = 13.sp,
                    fontWeight = if (isAccelMode) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
            }

            // UWB + BLE option
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(3.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(uwbBgColor)
                    .clickable { onModeChange(DetectionMode.UWB_BLE) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "UWB+BLE",
                    color = if (!isAccelMode) Color.White else TextSecondaryDark,
                    fontSize = 13.sp,
                    fontWeight = if (!isAccelMode) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ── Shared Components ───────────────────────────────────────────────────────

@Composable
fun StatusBanner(
    text: String,
    backgroundColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Warning
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun FilterChip(text: String, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .border(1.dp, TextSecondaryDark.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = Color.White, fontSize = 14.sp)
        Spacer(modifier = Modifier.width(4.dp))
        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun TransactionItem(tx: TransactionEntity) {
    val isReceived = tx.type == "RECEIVED"
    val color = if (isReceived) SuccessGreen else ErrorRed
    val prefix = if (isReceived) "+" else "-"
    val formatted = NumberFormat.getNumberInstance(Locale("en", "US")).format(tx.amountPaise / 100.0)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (isReceived) SuccessGreen.copy(alpha = 0.15f) else ErrorRed.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = (tx.counterpartyName ?: "?").take(1).uppercase(),
                    color = color,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(tx.counterpartyName ?: "Unknown", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(tx.timestamp.take(10), color = TextSecondaryDark, fontSize = 12.sp)
            }
        }
        Text("$prefix$formatted", color = color, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

private fun checkBluetoothEnabled(context: Context): Boolean {
    return try {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bm?.adapter?.isEnabled == true
    } catch (e: Exception) {
        false
    }
}

private fun checkInternetAvailable(context: Context): Boolean {
    return try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        // Be very permissive to avoid false positives on fast Samsung networks
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) {
        false
    }
}
