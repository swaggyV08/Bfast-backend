package com.bfast.app.ui.payment

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bfast.app.ui.theme.BackgroundDark
import com.bfast.app.ui.theme.ButtonDark
import com.bfast.app.ui.theme.PrimaryBlue
import com.bfast.app.ui.theme.TextSecondaryDark

@Composable
fun PaymentEntryScreen(
    targetDeviceId: String,
    receiverName: String,
    onNavigateBack: () -> Unit,
    onNavigateToProcessing: (Long) -> Unit
) {
    var amount by remember { mutableStateOf("0") }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Not using app bar to allow full screen custom layout
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(bottom = 80.dp), // Space for pay button
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(24.dp))
                
                // Avatar
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.Gray)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text("Paying $receiverName", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("BaFT-id: ${targetDeviceId.take(8)}", color = TextSecondaryDark, fontSize = 14.sp)
                
                Spacer(modifier = Modifier.weight(1f))

                // Amount Display
                Text(
                    text = amount,
                    color = if (amount == "0") TextSecondaryDark else Color.White,
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Add note pill
                Box(
                    modifier = Modifier
                        .background(SurfaceDark.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Text("Add note...", color = TextSecondaryDark, fontSize = 14.sp)
                }

                Spacer(modifier = Modifier.weight(1f))

                // Custom NumPad
                NumPad(
                    onDigit = {
                        if (amount == "0") amount = it.toString()
                        else if (amount.length < 8) amount += it
                    },
                    onDelete = {
                        if (amount.length > 1) amount = amount.dropLast(1)
                        else amount = "0"
                    }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Bottom Pay Button
            if (amount != "0" && amount.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 24.dp)
                ) {
                    Button(
                        onClick = {
                            val rupees = amount.toDoubleOrNull() ?: 0.0
                            val paise = (rupees * 100).toLong()
                            if (paise > 0) {
                                onNavigateToProcessing(paise)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        Text("Pay $amount via B-Fast", fontSize = 16.sp, color = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = Color.White)
                    }
                }
            }
        }
    }
}

val SurfaceDark = Color(0xFF1E293B) // Add here if not accessible from theme

@Composable
fun NumPad(onDigit: (Int) -> Unit, onDelete: () -> Unit) {
    val padHeight = 64.dp
    val spacing = 8.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(spacing)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
            NumPadButton("1", "", Modifier.weight(1f), padHeight) { onDigit(1) }
            NumPadButton("2", "a b c", Modifier.weight(1f), padHeight) { onDigit(2) }
            NumPadButton("3", "d e f", Modifier.weight(1f), padHeight) { onDigit(3) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
            NumPadButton("4", "g h i", Modifier.weight(1f), padHeight) { onDigit(4) }
            NumPadButton("5", "j k l", Modifier.weight(1f), padHeight) { onDigit(5) }
            NumPadButton("6", "m n o", Modifier.weight(1f), padHeight) { onDigit(6) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
            NumPadButton("7", "p q r s", Modifier.weight(1f), padHeight) { onDigit(7) }
            NumPadButton("8", "t u v", Modifier.weight(1f), padHeight) { onDigit(8) }
            NumPadButton("9", "w x y z", Modifier.weight(1f), padHeight) { onDigit(9) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
            Spacer(modifier = Modifier.weight(1f))
            NumPadButton("0", "", Modifier.weight(1f), padHeight) { onDigit(0) }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(padHeight)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                // Delete Icon (Backscape symbol conceptually)
                Text("⌫", color = Color.White, fontSize = 24.sp)
            }
        }
    }
}

@Composable
fun NumPadButton(digit: String, letters: String, modifier: Modifier, height: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(height)
            .background(Color(0xFF2C2C2E), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(digit, color = Color.White, fontSize = 28.sp)
            if (letters.isNotEmpty()) {
                Text(letters, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
    }
}
