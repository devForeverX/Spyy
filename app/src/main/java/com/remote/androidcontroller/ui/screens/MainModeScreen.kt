package com.remote.androidcontroller.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remote.androidcontroller.ui.theme.DarkBorder
import com.remote.androidcontroller.ui.theme.DarkSurface
import com.remote.androidcontroller.ui.theme.DarkSurfaceElevated
import com.remote.androidcontroller.ui.theme.EmeraldPrimary
import com.remote.androidcontroller.ui.theme.TextPrimary
import com.remote.androidcontroller.ui.theme.TextSecondary

@Composable
fun MainModeScreen(
    serverUrl: String,
    errorMessage: String?,
    onServerUrlChange: (String) -> Unit,
    onSelectAdminMode: () -> Unit,
    onSelectRemoteMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isMongoMode = serverUrl.isBlank() || serverUrl.startsWith("mongodb", true) || serverUrl.startsWith("https://", true)
    val parsedUrl = android.net.Uri.parse(serverUrl.trim())
    val host = parsedUrl.host?.lowercase().orEmpty()
    val localCleartext = host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2" ||
        host.startsWith("192.168.") || host.startsWith("10.") ||
        (host.startsWith("172.") && (host.substringAfter("172.").substringBefore('.').toIntOrNull() ?: 0) in 16..31)
    val validServerUrl = isMongoMode || (host.isNotBlank() && (parsedUrl.scheme.equals("wss", true) ||
        (parsedUrl.scheme.equals("ws", true) && localCleartext)) && parsedUrl.port != 0 && parsedUrl.port <= 65535)

    var showCustomServer by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(!isMongoMode) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(DarkSurfaceElevated, CircleShape)
                .border(1.5.dp, EmeraldPrimary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.SettingsRemote,
                contentDescription = null,
                tint = EmeraldPrimary,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Android Phone Remote Control",
            style = MaterialTheme.typography.headlineLarge,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Wähle die Rolle dieses Smartphones für die Kopplung:",
            color = TextSecondary,
            fontSize = 15.sp
        )

        Spacer(modifier = Modifier.height(18.dp))

        // Connection Mode Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (!showCustomServer) EmeraldPrimary else DarkBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (!showCustomServer) Icons.Default.CloudDone else Icons.Default.Storage,
                            contentDescription = null,
                            tint = if (!showCustomServer) EmeraldPrimary else TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (!showCustomServer) "MongoDB Cloud (Weltweit aktiv)" else "Eigener WebSocket-Server",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }

                    androidx.compose.material3.TextButton(
                        onClick = {
                            showCustomServer = !showCustomServer
                            if (!showCustomServer) {
                                onServerUrlChange("mongodb://cloud")
                            } else if (serverUrl.startsWith("mongodb")) {
                                onServerUrlChange("")
                            }
                        }
                    ) {
                        Text(
                            text = if (!showCustomServer) "Eigenen Server nutzen" else "MongoDB Cloud nutzen",
                            color = EmeraldPrimary,
                            fontSize = 12.sp
                        )
                    }
                }

                if (!showCustomServer) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkSurfaceElevated, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.QrCode, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Erstverbindung per QR-Code • Läuft über jedes normale Internet weltweit",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = onServerUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Signalisierungsserver (WSS-Adresse)") },
                        placeholder = { Text("wss://dein-server.example:8443") },
                        isError = !validServerUrl,
                        supportingText = {
                            Text(
                                if (validServerUrl) "Öffentliche Server müssen TLS (wss://) verwenden."
                                else "Gültige Serveradresse erforderlich (z.B. wss://...).",
                                color = if (validServerUrl) TextSecondary else MaterialTheme.colorScheme.error
                            )
                        }
                    )
                }
            }
        }

        if (!errorMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(errorMessage, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Role 1: Admin Controller
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = validServerUrl) { onSelectAdminMode() },
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(DarkSurfaceElevated, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.SettingsRemote, contentDescription = null, tint = EmeraldPrimary)
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = "Admin-Gerät (Controller)",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    Text(
                        text = "Steuere das andere Smartphone über die native Android-Fernbedienung.",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Role 2: Remote Target Device
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = validServerUrl) { onSelectRemoteMode() },
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(DarkSurfaceElevated, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = EmeraldPrimary)
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = "Remote-Gerät (Zielgerät)",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    Text(
                        text = "Empfängt Steuerbefehle und führt Navigation, Audio und IME aus.",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
