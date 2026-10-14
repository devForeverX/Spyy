package com.remote.androidcontroller.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remote.androidcontroller.network.DeviceStatusPayload
import com.remote.androidcontroller.network.VibrationPattern
import com.remote.androidcontroller.ui.theme.DarkBorder
import com.remote.androidcontroller.ui.theme.DarkSurface
import com.remote.androidcontroller.ui.theme.DarkSurfaceElevated
import com.remote.androidcontroller.ui.theme.EmeraldPrimary
import com.remote.androidcontroller.ui.theme.TextPrimary
import com.remote.androidcontroller.ui.theme.TextSecondary

import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import com.remote.androidcontroller.network.NavigationAction

@Composable
fun ExtrasScreen(
    enabled: Boolean,
    deviceStatus: DeviceStatusPayload?,
    measuredRttMs: Long,
    onSetBrightness: (Int) -> Unit,
    onSetFlashlight: (Boolean) -> Unit,
    onTriggerVibration: (VibrationPattern) -> Unit,
    onTransferClipboard: (String) -> Unit,
    onNavigationAction: (NavigationAction) -> Unit,
    modifier: Modifier = Modifier
) {
    var brightnessSlider by remember { mutableFloatStateOf(deviceStatus?.currentBrightness?.toFloat() ?: 0f) }
    LaunchedEffect(deviceStatus?.currentBrightness) {
        deviceStatus?.currentBrightness?.let { brightnessSlider = it.toFloat() }
    }
    var clipboardText by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Gerätesteuerung & Technische Extras",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary
        )

        // CARD 1: Display Helligkeit
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Brightness6, contentDescription = null, tint = EmeraldPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Helligkeit einstellen: ${brightnessSlider.toInt()}%",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Slider(
                    value = brightnessSlider,
                    onValueChange = { brightnessSlider = it },
                    onValueChangeFinished = { onSetBrightness(brightnessSlider.toInt()) },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(
                        thumbColor = EmeraldPrimary,
                        activeTrackColor = EmeraldPrimary,
                        inactiveTrackColor = DarkSurfaceElevated
                    ),
                    enabled = enabled
                )

                Text(
                    text = "Aktueller Systemwert am Ziel: ${deviceStatus?.currentBrightness?.let { "$it%" } ?: "nicht empfangen"}. Der neue Wert wird nach Android-Bestätigung geprüft. WRITE_SETTINGS muss am Zielgerät erlaubt sein.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        // CARD 2: Taschenlampe & Vibration
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Explicit actions avoid displaying a local switch state as if it reflected the remote LED.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FlashlightOn, contentDescription = null, tint = EmeraldPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Taschenlampe (LED-Blitz)",
                            color = TextPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { onSetFlashlight(true) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                    ) { Text("Einschalten", color = Color.White) }
                    Button(
                        onClick = { onSetFlashlight(false) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated)
                    ) { Text("Ausschalten", color = TextPrimary) }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Vibration Muster
                Text(
                    text = "Haptisches Feedback / Vibration testen:",
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onTriggerVibration(VibrationPattern.SHORT_PULSE) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated)
                    ) {
                        Text("Kurz (150ms)", fontSize = 12.sp, color = TextPrimary)
                    }
                    Button(
                        onClick = { onTriggerVibration(VibrationPattern.DOUBLE_PULSE) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated)
                    ) {
                        Text("Doppelpuls", fontSize = 12.sp, color = TextPrimary)
                    }
                    Button(
                        onClick = { onTriggerVibration(VibrationPattern.SOS_ALARM) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated)
                    ) {
                        Text("SOS-Alarm", fontSize = 12.sp, color = TextPrimary)
                    }
                }
            }
        }

        // CARD 3: Manuelle Zwischenablage
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, tint = EmeraldPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Manuelle Zwischenablage übertragen",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedTextField(
                    value = clipboardText,
                    onValueChange = { clipboardText = it },
                    placeholder = { Text("Text zum Übertragen eingeben...", color = TextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = EmeraldPrimary,
                        unfocusedBorderColor = DarkBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    enabled = enabled
                )

                Button(
                    onClick = {
                        if (clipboardText.isNotBlank()) {
                            onTransferClipboard(clipboardText)
                            clipboardText = ""
                        }
                    },
                    enabled = enabled && clipboardText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                ) {
                    Text("Ausdrücklich in Remote-Zwischenablage kopieren", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        // CARD 4: Touch-Gesten (Wischen & Scrollen via AccessibilityService)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Speed, contentDescription = null, tint = EmeraldPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Touch-Gesten & Bildschirm-Navigation",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = "Löst echte Wischgesten auf dem Android-Touchscreen über den Accessibility-Dienst aus:",
                    color = TextSecondary,
                    fontSize = 12.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onNavigationAction(NavigationAction.SWIPE_UP) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated)
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(16.dp), tint = EmeraldPrimary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Hoch", fontSize = 12.sp, color = TextPrimary)
                    }
                    Button(
                        onClick = { onNavigationAction(NavigationAction.SWIPE_DOWN) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated)
                    ) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(16.dp), tint = EmeraldPrimary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Runter", fontSize = 12.sp, color = TextPrimary)
                    }
                    Button(
                        onClick = { onNavigationAction(NavigationAction.SWIPE_LEFT) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated)
                    ) {
                        Text("◀ Links", fontSize = 12.sp, color = TextPrimary)
                    }
                    Button(
                        onClick = { onNavigationAction(NavigationAction.SWIPE_RIGHT) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated)
                    ) {
                        Text("Rechts ▶", fontSize = 12.sp, color = TextPrimary)
                    }
                }
            }
        }

        // CARD 5: Android System- & Schnellaktionen
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = EmeraldPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Android System- & Schnellaktionen",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onNavigationAction(NavigationAction.NOTIFICATIONS) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated)
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(15.dp), tint = EmeraldPrimary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Statusleiste", fontSize = 11.sp, color = TextPrimary)
                    }
                    Button(
                        onClick = { onNavigationAction(NavigationAction.QUICK_SETTINGS) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(15.dp), tint = EmeraldPrimary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Quick-Settings", fontSize = 11.sp, color = TextPrimary)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onNavigationAction(NavigationAction.LOCK_SCREEN) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated)
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(15.dp), tint = EmeraldPrimary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Sperren", fontSize = 11.sp, color = TextPrimary)
                    }
                    Button(
                        onClick = { onNavigationAction(NavigationAction.POWER_DIALOG) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated)
                    ) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(15.dp), tint = EmeraldPrimary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Power-Menü", fontSize = 11.sp, color = TextPrimary)
                    }
                    Button(
                        onClick = { onNavigationAction(NavigationAction.TAKE_SCREENSHOT) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(15.dp), tint = EmeraldPrimary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Screenshot", fontSize = 11.sp, color = TextPrimary)
                    }
                }
            }
        }

        // CARD 6: Technische Hardware- & Netzwerk-Informationen
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = EmeraldPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Technische Geräte- & Netzwerkdaten",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                InfoRow("Gerätemodell", deviceStatus?.modelName ?: "Nicht empfangen")
                InfoRow("Android-Version", deviceStatus?.androidVersion ?: "Unbekannt")
                InfoRow("Displayauflösung", deviceStatus?.displayResolution ?: "Unbekannt")
                InfoRow("Akkustand", deviceStatus?.batteryPercent?.takeIf { it in 0..100 }?.let { "$it% ${if (deviceStatus.isCharging) "(lädt)" else ""}" } ?: "—")
                InfoRow("Verbindungspfad", deviceStatus?.connectionPath ?: "—")
                InfoRow("Gemessene RTT-Latenz", if (measuredRttMs > 0) "$measuredRttMs ms" else "—")
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = TextSecondary, fontSize = 13.sp)
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}
