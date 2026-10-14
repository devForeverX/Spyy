package com.remote.androidcontroller.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remote.androidcontroller.network.AudioStatusPayload
import com.remote.androidcontroller.ui.theme.DarkBorder
import com.remote.androidcontroller.ui.theme.DarkSurface
import com.remote.androidcontroller.ui.theme.DarkSurfaceElevated
import com.remote.androidcontroller.ui.theme.EmeraldPrimary
import com.remote.androidcontroller.ui.theme.RedDanger
import com.remote.androidcontroller.ui.theme.TextPrimary
import com.remote.androidcontroller.ui.theme.TextSecondary

@Composable
fun AudioStreamScreen(
    enabled: Boolean,
    audioStatus: AudioStatusPayload?,
    onSelectAudioFile: (Uri) -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onPlayTestTone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var volumeSlider by remember { mutableFloatStateOf(0.8f) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
            // Extract filename from Uri
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            val name = cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) it.getString(nameIndex) else "Audiodatei"
                } else "Audiodatei"
            } ?: "Audiodatei"
            selectedFileName = name
            onSelectAudioFile(uri)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Audio-Streaming auf Remote-Gerät",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary
        )

        // CARD 1: File Selection
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Audiodatei auswählen",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = TextPrimary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(DarkSurfaceElevated, CircleShape)
                            .border(1.dp, DarkBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Audiotrack,
                            contentDescription = null,
                            tint = EmeraldPrimary
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = selectedFileName ?: "Keine Datei gewählt (MP3, WAV, AAC)",
                            color = if (selectedFileName != null) TextPrimary else TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (selectedFileName != null) "Bereit zur Übertragung" else "Tippe auf Durchsuchen",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }

                    Button(
                        onClick = { filePickerLauncher.launch(arrayOf("audio/*")) },
                        enabled = enabled,
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Auswählen", color = TextPrimary)
                    }
                }
            }
        }

        // CARD 2: Playback Status & Controls
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Wiedergabestatus",
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )

                    val statusText = when {
                        audioStatus?.isPlaying == true && audioStatus.isPaused -> "Pausiert"
                        audioStatus?.isPlaying == true -> "Wiedergabe aktiv"
                        else -> "Gestoppt"
                    }
                    val statusColor = when {
                        audioStatus?.isPlaying == true && !audioStatus.isPaused -> EmeraldPrimary
                        audioStatus?.isPaused == true -> Color.Yellow
                        else -> TextSecondary
                    }

                    Text(
                        text = statusText,
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                // Buffer Progress
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Jitter-Pufferfüllung:", color = TextSecondary, fontSize = 12.sp)
                        Text(
                            text = "${audioStatus?.bufferFillPercent ?: 0}% (Underruns: ${audioStatus?.underrunCount ?: 0})",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (audioStatus?.bufferFillPercent ?: 0) / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = EmeraldPrimary,
                        trackColor = DarkSurfaceElevated
                    )
                }

                // Playback Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val canPlay = enabled && selectedUri != null && audioStatus?.isPlaying != true
                    IconButton(
                        onClick = onPlay,
                        enabled = canPlay,
                        modifier = Modifier
                            .size(52.dp)
                            .background(if (canPlay) EmeraldPrimary else DarkSurfaceElevated, CircleShape)
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Start",
                            tint = if (canPlay) Color.Black else TextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // Pause / Resume
                    IconButton(
                        onClick = {
                            if (audioStatus?.isPaused == true) onResume() else onPause()
                        },
                        enabled = enabled && audioStatus?.isPlaying == true,
                        modifier = Modifier
                            .size(52.dp)
                            .background(DarkSurfaceElevated, CircleShape)
                    ) {
                        Icon(
                            imageVector = if (audioStatus?.isPaused == true) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = "Pause",
                            tint = TextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // Stop
                    IconButton(
                        onClick = onStop,
                        enabled = enabled && audioStatus?.isPlaying == true,
                        modifier = Modifier
                            .size(52.dp)
                            .background(RedDanger, CircleShape)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Stopp", tint = Color.White)
                    }
                }
            }
        }

        // CARD 3: Volume Control & Locator Chime
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.VolumeUp, contentDescription = null, tint = EmeraldPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Remote-Lautsprecher Lautstärke: ${(volumeSlider * 100).toInt()}%",
                        color = TextPrimary,
                        fontWeight = FontWeight.Medium
                    )
                }

                Slider(
                    value = volumeSlider,
                    onValueChange = {
                        volumeSlider = it
                        onVolumeChange(it)
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = EmeraldPrimary,
                        activeTrackColor = EmeraldPrimary,
                        inactiveTrackColor = DarkSurfaceElevated
                    ),
                    enabled = enabled
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Find device acoustic test tone
                Button(
                    onClick = onPlayTestTone,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated)
                ) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = EmeraldPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Gerät finden (Ortungston auf Zielgerät abspielen)", color = TextPrimary)
                }
            }
        }
    }
}
