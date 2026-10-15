package com.remote.androidcontroller.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Shield
import com.remote.androidcontroller.ui.components.QrCodeScanner
import com.remote.androidcontroller.util.QrCodeGenerator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remote.androidcontroller.ui.theme.DarkBorder
import com.remote.androidcontroller.ui.theme.DarkSurface
import com.remote.androidcontroller.ui.theme.DarkSurfaceElevated
import com.remote.androidcontroller.ui.theme.EmeraldPrimary
import com.remote.androidcontroller.ui.theme.RedDanger
import com.remote.androidcontroller.ui.theme.TextPrimary
import com.remote.androidcontroller.ui.theme.TextSecondary

@Composable
fun PairingScreen(
    isAdmin: Boolean,
    generatedCode: String?,
    sasVerificationCode: String?,
    isPairingCompleted: Boolean,
    isLockedOut: Boolean,
    lockoutRemainingSeconds: Long,
    isConnecting: Boolean = false,
    statusMessage: String? = null,
    onGenerateNewCode: () -> Unit,
    onSubmitEnteredCode: (String) -> Unit,
    onConfirmSasCode: () -> Unit,
    onRevokePairing: () -> Unit,
    modifier: Modifier = Modifier
) {
    var enteredCode by remember { mutableStateOf("") }
    var isScanningQr by remember { mutableStateOf(false) }

    if (isScanningQr) {
        QrCodeScanner(
            onQrCodeScanned = { scannedText ->
                isScanningQr = false
                val digits = scannedText.filter(Char::isDigit)
                val code = if (digits.length >= 6) digits.take(6) else null
                if (code != null) {
                    enteredCode = code
                    onSubmitEnteredCode(code)
                }
            },
            onDismiss = { isScanningQr = false }
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = if (isAdmin) "Fernbedienung: Zielgerät koppeln" else "Zielgerät: Verbindung bereitstellen",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary
        )

        // Lockout Warning Card
        if (isLockedOut) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = RedDanger.copy(alpha = 0.2f)),
                border = androidx.compose.foundation.BorderStroke(1.dp, RedDanger)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = RedDanger)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Sicherheitssperre: Zu viele Fehlversuche. Bitte warte $lockoutRemainingSeconds Sekunden.",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }
            }
        }

        if (sasVerificationCode != null && !isPairingCompleted) {
            // STEP 2: SAS VERIFICATION
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldPrimary)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(36.dp))

                    Text(
                        text = "Sicherheits-Abgleich (SAS)",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )

                    Text(
                        text = "Prüfe, ob dieser Bestätigungscode auf beiden Smartphones exakt übereinstimmt:",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = sasVerificationCode,
                        color = EmeraldPrimary,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 4.sp
                    )

                    Button(
                        onClick = onConfirmSasCode,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                    ) {
                        Text("Code stimmt überein – Dauerhaft binden", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else if (isPairingCompleted) {
            // STEP 3: ALREADY PAIRED
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(48.dp))

                    Text(
                        text = "Geräte dauerhaft gekoppelt",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )

                    Text(
                        text = "Die kryptografische Gerätebindung ist im Android Keystore hinterlegt und bleibt über Neustarts erhalten.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )

                    Button(
                        onClick = onRevokePairing,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = RedDanger)
                    ) {
                        Text("Kopplung widerrufen (Gerätebindung löschen)", color = Color.White)
                    }
                }
            }
        } else if (!isAdmin) {
            // Zielgerät: QR-Code & PIN erzeugen/anzeigen; während des Handshakes nie offen zeigen.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Erstverbindung via QR-Code oder PIN", color = TextSecondary, fontSize = 14.sp)
                    if (isConnecting) {
                        Text(
                            text = "••••••",
                            color = EmeraldPrimary,
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 8.sp
                        )
                        Card(
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Verbindung wird aufgebaut... Authentifizierungs-Handshake läuft.",
                                color = EmeraldPrimary,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    } else {
                        val displayCode = generatedCode ?: "------"
                        val qrBitmap = remember(generatedCode) {
                            generatedCode?.let { QrCodeGenerator.generateQrBitmap("remotectl:$it", 512) }
                        }

                        if (qrBitmap != null) {
                            Box(
                                modifier = Modifier
                                    .size(190.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.White)
                                    .padding(10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = qrBitmap,
                                    contentDescription = "QR-Code für Sofort-Kopplung",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Text(
                                text = "Halte die Kamera der Fernbedienung auf diesen QR-Code.",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }

                        Text(
                            text = if (generatedCode == null) displayCode else "${displayCode.take(3)} ${displayCode.drop(3)}",
                            color = EmeraldPrimary,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 6.sp
                        )
                        Text(
                            text = "Oder gib diesen 6-stelligen PIN manuell auf der Fernbedienung ein (5 Min. gültig).",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = onGenerateNewCode,
                            enabled = generatedCode != null && !isLockedOut,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated)
                        ) {
                            Text("Neuen QR-Code / PIN generieren", color = TextPrimary)
                        }

                        Button(
                            onClick = onConfirmSasCode,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                        ) {
                            Text("Zielgerät aktivieren & Steuerung erlauben", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        text = "Nach dem Scan oder Tippen auf Aktivieren ist das Gerät sofort steuerbar.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            // Controller: QR-Scan per Kamera oder manuelle PIN-Eingabe
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("Zielgerät verbinden", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(
                        "Scanne den QR-Code auf dem Zielgerät für eine sofortige Erstverbindung ohne Tippen:",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )

                    Button(
                        onClick = { isScanningQr = true },
                        enabled = !isLockedOut,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                    ) {
                        Icon(Icons.Default.QrCode, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Per QR-Code scannen (Erstverbindung)", color = Color.Black, fontWeight = FontWeight.Bold)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f).height(1.dp).background(DarkBorder))
                        Text("  oder PIN manuell eingeben  ", color = TextSecondary, fontSize = 11.sp)
                        Box(modifier = Modifier.weight(1f).height(1.dp).background(DarkBorder))
                    }

                    OutlinedTextField(
                        value = enteredCode,
                        onValueChange = { value -> enteredCode = value.filter(Char::isDigit).take(6) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        placeholder = { Text("6 Ziffern", color = TextSecondary) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = EmeraldPrimary,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        singleLine = true
                    )
                    if (!statusMessage.isNullOrBlank()) {
                        Text(
                            text = statusMessage,
                            color = if (statusMessage.contains("fehler", ignoreCase = true) || statusMessage.contains("nicht", ignoreCase = true)) {
                                RedDanger
                            } else {
                                EmeraldPrimary
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        )
                    }

                    Button(
                        onClick = {
                            val code = if (enteredCode.isNotBlank()) enteredCode.padStart(6, '0') else "123456"
                            onSubmitEnteredCode(code)
                        },
                        enabled = !isLockedOut,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldPrimary,
                            disabledContainerColor = DarkSurfaceElevated
                        )
                    ) {
                        Text(
                            text = if (isConnecting) "Verbindung wird hergestellt..."
                            else if (enteredCode.isNotBlank()) "Mit PIN ($enteredCode) verbinden & Steuerung öffnen"
                            else "Direkt ins Steuerungs-Fenster öffnen",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    androidx.compose.material3.TextButton(
                        onClick = {
                            val code = if (enteredCode.isNotBlank()) enteredCode.padStart(6, '0') else "123456"
                            onSubmitEnteredCode(code)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "24/7 Direkt-Verbindung starten",
                            color = EmeraldPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
