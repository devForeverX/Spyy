package com.remote.androidcontroller.ui.screens

import android.view.HapticFeedbackConstants
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remote.androidcontroller.network.DeviceStatusPayload
import com.remote.androidcontroller.network.NavigationAction
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import com.remote.androidcontroller.ui.components.AndroidNavigationBar
import com.remote.androidcontroller.ui.theme.DarkBorder
import com.remote.androidcontroller.ui.theme.DarkSurface
import com.remote.androidcontroller.ui.theme.DarkSurfaceElevated
import com.remote.androidcontroller.ui.theme.EmeraldPrimary
import com.remote.androidcontroller.ui.theme.TextPrimary
import com.remote.androidcontroller.ui.theme.TextSecondary

@Composable
fun AdminRemoteScreen(
    isConnected: Boolean,
    deviceStatus: DeviceStatusPayload?,
    remoteVideoTrack: VideoTrack?,
    eglContext: EglBase.Context?,
    remoteScreenShareActive: Boolean,
    remoteScreenWidth: Int,
    remoteScreenHeight: Int,
    onRemoteTap: (Float, Float) -> Unit,
    onRemoteSwipe: (Float, Float, Float, Float, Long) -> Unit,
    measuredRttMs: Long,
    lastActionResultMessage: String?,
    onNavigationAction: (NavigationAction) -> Unit,
    onAdjustVolume: (directionUp: Boolean) -> Unit,
    onDisconnect: (() -> Unit)? = null,
    // Child tabs content
    keyboardContent: @Composable () -> Unit,
    audioContent: @Composable () -> Unit,
    extrasContent: @Composable () -> Unit,
    permissionsContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("Bildschirm", "Tastatur", "Audio", "Extras", "Rechte")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // TOP 1: COMPACT DEVICE STATUS BAR
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = deviceStatus?.modelName ?: if (isConnected) "Gekoppeltes Zielgerät" else "Nicht verbunden",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = if (isConnected) {
                            val path = deviceStatus?.connectionPath ?: "Verbunden"
                            val ver = deviceStatus?.androidVersion?.let { " · $it" } ?: ""
                            "$path$ver"
                        } else "Warte auf Verbindung...",
                        color = if (isConnected) EmeraldPrimary else TextSecondary,
                        fontSize = 12.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Battery
                    Icon(
                        imageVector = Icons.Default.BatteryChargingFull,
                        contentDescription = null,
                        tint = if (deviceStatus?.batteryPercent?.let { it in 0..100 } == true) EmeraldPrimary else TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = deviceStatus?.batteryPercent?.takeIf { it in 0..100 }?.let { "$it%" } ?: "—",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    // RTT Latency
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (measuredRttMs > 0 && isConnected) "$measuredRttMs ms" else "—",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    if (onDisconnect != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = onDisconnect,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = "Trennen",
                                tint = EmeraldPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // TOP 2: VOLUME CONTROL SECTION (Lauter & Leiser)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VolumeUp, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Hardware-Lautstärke", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }

                    Text(
                        text = if (deviceStatus != null && deviceStatus.currentVolume >= 0 && deviceStatus.maxVolume > 0) {
                            "Pegel: ${deviceStatus.currentVolume} / ${deviceStatus.maxVolume}"
                        } else "Pegel: —",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Volume Up (+) Button
                    VolumeButton(
                        label = "Lauter ( + )",
                        icon = Icons.Default.Add,
                        enabled = isConnected,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            runCatching { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                            onAdjustVolume(true)
                        }
                    )

                    // Volume Down (-) Button
                    VolumeButton(
                        label = "Leiser ( − )",
                        icon = Icons.Default.Remove,
                        enabled = isConnected,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            runCatching { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                            onAdjustVolume(false)
                        }
                    )
                }
            }
        }

        // ACTION RESULT FEEDBACK HUD (e.g. "Zurück bestätigt", "Fokus fehlt")
        if (!lastActionResultMessage.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .background(DarkSurfaceElevated, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = lastActionResultMessage,
                    color = EmeraldPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // TAB NAVIGATION (Tastatur / Audio / Extras / Rechte)
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = EmeraldPrimary,
            indicator = { tabPositions ->
                if (selectedTab in tabPositions.indices) {
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = EmeraldPrimary
                    )
                }
            },
            divider = {}
        ) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            color = if (selectedTab == index) EmeraldPrimary else TextSecondary,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp
                        )
                    }
                )
            }
        }

        // TAB BODY
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (selectedTab) {
                0 -> RemoteScreenViewer(
                    isConnected = isConnected,
                    isScreenShareActive = remoteScreenShareActive,
                    remoteVideoTrack = remoteVideoTrack,
                    eglContext = eglContext,
                    screenWidth = remoteScreenWidth,
                    screenHeight = remoteScreenHeight,
                    onRemoteTap = onRemoteTap,
                    onRemoteSwipe = onRemoteSwipe
                )
                1 -> keyboardContent()
                2 -> audioContent()
                3 -> extrasContent()
                4 -> permissionsContent()
            }
        }

        // PERSISTENT LOWER ANDROID NAVIGATION BAR (Back, Home, Recent Apps)
        AndroidNavigationBar(
            enabled = isConnected,
            onAction = onNavigationAction
        )
    }
}

@Composable
private fun VolumeButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurfaceElevated)
            .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = if (enabled) EmeraldPrimary else Color.Gray, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = label, color = if (enabled) TextPrimary else Color.Gray, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
}
