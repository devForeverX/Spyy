package com.remote.androidcontroller.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remote.androidcontroller.network.PermissionStatusPayload
import com.remote.androidcontroller.ui.theme.DarkBorder
import com.remote.androidcontroller.ui.theme.DarkSurface
import com.remote.androidcontroller.ui.theme.DarkSurfaceElevated
import com.remote.androidcontroller.ui.theme.EmeraldPrimary
import com.remote.androidcontroller.ui.theme.RedDanger
import com.remote.androidcontroller.ui.theme.TextPrimary
import com.remote.androidcontroller.ui.theme.TextSecondary
import com.remote.androidcontroller.viewmodel.ConnectionStatus

@Composable
fun PermissionsScreen(
    permissionStatus: PermissionStatusPayload?,
    isRemoteDevice: Boolean,
    pairedDeviceName: String? = null,
    connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    onRevokePairing: (() -> Unit)? = null,
    screenShareActive: Boolean = false,
    screenSharePending: Boolean = false,
    onStartScreenShare: (() -> Unit)? = null,
    onStopScreenShare: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (isRemoteDevice) {
            // HOST STATUS BANNER
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Smartphone, contentDescription = null, tint = EmeraldPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Zielgerät-Host Modus",
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 16.sp
                            )
                        }

                        Text(
                            text = when (connectionStatus) {
                                ConnectionStatus.CONNECTED_P2P -> "🟢 P2P Verbunden"
                                ConnectionStatus.CONNECTED_RELAY -> "🟡 TURN Relay"
                                ConnectionStatus.CONNECTED_TRANSPORT_UNKNOWN -> "Verbunden · Pfad wird ermittelt"
                                ConnectionStatus.SIGNALING_ONLINE -> "🔵 Bereit (Signaling Online)"
                                else -> "⚪ Getrennt"
                            },
                            color = if (connectionStatus == ConnectionStatus.CONNECTED_P2P) EmeraldPrimary else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (pairedDeviceName != null) {
                        Text(
                            text = "Gekoppelt mit: $pairedDeviceName",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }

                    if (onRevokePairing != null) {
                        Button(
                            onClick = onRevokePairing,
                            colors = ButtonDefaults.buttonColors(containerColor = RedDanger),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Kopplung beenden & widerrufen", color = androidx.compose.ui.graphics.Color.White)
                        }
                    }
                }
            }
        }

        if (isRemoteDevice && onStartScreenShare != null && onStopScreenShare != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Live-Bildschirmfreigabe", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "Nur nach deiner Bestätigung im Android-Systemdialog. Während der Freigabe bleibt eine permanente Systembenachrichtigung sichtbar.",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    if (screenShareActive) {
                        Button(
                            onClick = onStopScreenShare,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = RedDanger)
                        ) {
                            Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Bildschirmfreigabe beenden", color = androidx.compose.ui.graphics.Color.White)
                        }
                    } else {
                        Button(
                            onClick = onStartScreenShare,
                            enabled = !screenSharePending && connectionStatus != ConnectionStatus.DISCONNECTED,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                        ) {
                            Icon(Icons.Default.Smartphone, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (screenSharePending) "Bildschirmfreigabe wird vorbereitet…" else "Bildschirm sichtbar freigeben",
                                color = androidx.compose.ui.graphics.Color.White
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = "Berechtigungs- & Dienstverwaltung",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary
        )

        Text(
            text = "Für die vollständige Smartphone-Fernsteuerung benötigt das Zielgerät spezifische Android-Systemberechtigungen. Diese müssen vom Nutzer in den Android-Einstellungen bewusst aktiviert werden.",
            color = TextSecondary,
            fontSize = 14.sp
        )

        // 1. Accessibility Service
        PermissionCard(
            title = "1. Bedienungshilfen-Dienst (Accessibility)",
            description = "Ermöglicht das Ausführen globaler Systemtasten (Zurück, Home, Recent Apps). Ohne diesen Dienst kann der Admin das Zielgerät nicht navigieren.",
            isGranted = permissionStatus?.accessibilityEnabled ?: false,
            actionLabel = "In Einstellungen aktivieren",
            onAction = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
        )

        // 2. Input Method Service (IME)
        PermissionCard(
            title = "2. Remote-Tastatur (InputMethodService)",
            description = "Ermöglicht das Einfügen von Text in fokussierte Eingabefelder auf dem Zielgerät. Muss als Tastatur aktiviert und ausgewählt sein.",
            isGranted = (permissionStatus?.imeEnabled == true && permissionStatus.imeSelected),
            actionLabel = "Tastatur aktivieren & wählen",
            onAction = {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
        )

        // 3. Write Settings (Systemhelligkeit)
        PermissionCard(
            title = "3. Systemeinstellungen ändern (WRITE_SETTINGS)",
            description = "Erlaubt das Anpassen der systemweiten Bildschirmhelligkeit über den Helligkeits-Schieberegler.",
            isGranted = permissionStatus?.writeSettingsGranted ?: false,
            actionLabel = "Berechtigung erteilen",
            onAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                }
            }
        )

        // 4. Benachrichtigungen (Android 13+)
        PermissionCard(
            title = "4. Benachrichtigungen & Foreground Service",
            description = "Erforderlich für transparente Android-Systemhinweise während einer aktiven Sitzung oder Audiowiedergabe.",
            isGranted = permissionStatus?.notificationsGranted ?: true,
            actionLabel = "App-Benachrichtigungen prüfen",
            onAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                }
            }
        )
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    isGranted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isGranted) EmeraldPrimary else RedDanger,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isGranted) "Aktiv" else "Fehlt",
                        color = if (isGranted) EmeraldPrimary else RedDanger,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }

            Text(
                text = description,
                color = TextSecondary,
                fontSize = 13.sp
            )

            if (!isGranted) {
                Button(
                    onClick = onAction,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated)
                ) {
                    Text(text = actionLabel, color = TextPrimary, fontSize = 13.sp)
                }
            }
        }
    }
}
