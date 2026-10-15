package com.remote.androidcontroller.ui.screens

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import com.remote.androidcontroller.ui.theme.DarkBorder
import com.remote.androidcontroller.ui.theme.DarkSurface
import com.remote.androidcontroller.ui.theme.EmeraldPrimary
import com.remote.androidcontroller.ui.theme.TextPrimary
import com.remote.androidcontroller.ui.theme.TextSecondary
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun RemoteScreenViewer(
    isConnected: Boolean,
    isScreenShareActive: Boolean,
    remoteVideoTrack: VideoTrack?,
    eglContext: EglBase.Context?,
    screenWidth: Int,
    screenHeight: Int,
    onRemoteTap: (Float, Float) -> Unit,
    onRemoteSwipe: (Float, Float, Float, Float, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // A negotiated VideoTrack alone does not prove that frames are arriving. Only enable tap/swipe
    // after observing at least one frame from the active share session.
    val frameState = remember(remoteVideoTrack, isScreenShareActive) { mutableStateOf(false) }
    val hasReceivedVideoFrame by frameState
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val frameNoticePosted = remember(remoteVideoTrack, isScreenShareActive) { AtomicBoolean(false) }
    val frameObserver = remember(remoteVideoTrack, isScreenShareActive, mainHandler, frameNoticePosted) {
        object : VideoSink {
            override fun onFrame(frame: VideoFrame) {
                // WebRTC delivers frames off the UI thread. Post at most once and update Compose
                // state on main, rather than flooding the main looper when a frame first arrives.
                if (frameNoticePosted.compareAndSet(false, true)) {
                    mainHandler.post { frameState.value = true }
                }
            }
        }
    }
    DisposableEffect(remoteVideoTrack, frameObserver) {
        val track = remoteVideoTrack
        if (track != null) runCatching { track.addSink(frameObserver) }
        onDispose { if (track != null) runCatching { track.removeSink(frameObserver) } }
    }

    val aspectRatio = if (screenWidth > 0 && screenHeight > 0) {
        (screenWidth.toFloat() / screenHeight.toFloat()).coerceIn(0.25f, 4f)
    } else 0.46f

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (isScreenShareActive) Icons.Default.CastConnected else Icons.Default.TouchApp,
                contentDescription = null,
                tint = if (isScreenShareActive && isConnected) EmeraldPrimary else TextSecondary,
                modifier = Modifier.size(17.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = when {
                    !isConnected -> "Nicht verbunden"
                    isScreenShareActive && remoteVideoTrack != null && hasReceivedVideoFrame -> "Live-Bild · Tippen oder wischen"
                    isScreenShareActive -> "Freigabe aktiv · warte auf echte Bilddaten"
                    else -> "Bildschirmfreigabe auf dem Zielgerät starten"
                },
                color = if (isScreenShareActive && isConnected) EmeraldPrimary else TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (!isScreenShareActive || remoteVideoTrack == null || eglContext == null || !hasReceivedVideoFrame) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = BorderStroke(1.dp, DarkBorder)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.CastConnected, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(34.dp))
                        Text(
                            if (isScreenShareActive && remoteVideoTrack != null && !hasReceivedVideoFrame) "Warte auf erstes Videobild" else "Kein Live-Bild",
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (!isScreenShareActive) "Öffne am Zielgerät den Bereich „Rechte“ und starte die Bildschirmfreigabe. Android zeigt vorher eine eigene Zustimmungsabfrage." else "Die Freigabe ist gemeldet, aber beim Controller ist noch kein Videoframe angekommen. Tippen und Wischen bleiben bis dahin deaktiviert.",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                val renderer = rememberRenderer(context, eglContext)
                DisposableEffect(renderer) {
                    onDispose { runCatching { renderer.release() } }
                }
                DisposableEffect(renderer, remoteVideoTrack) {
                    runCatching { remoteVideoTrack.addSink(renderer) }
                    onDispose { runCatching { remoteVideoTrack.removeSink(renderer) } }
                }

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = 340.dp)
                        .aspectRatio(aspectRatio)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, DarkBorder, RoundedCornerShape(14.dp))
                        .background(Color.Black)
                        .pointerInput(isConnected, isScreenShareActive, hasReceivedVideoFrame, onRemoteTap, onRemoteSwipe) {
                            if (!isConnected || !isScreenShareActive || remoteVideoTrack == null || !hasReceivedVideoFrame) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                val start = down.position
                                var end = start
                                var moved = false
                                var endTime = down.uptimeMillis
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    end = change.position
                                    endTime = change.uptimeMillis
                                    if ((end - start).getDistance() > viewConfiguration.touchSlop) moved = true
                                    val stillPressed = change.pressed
                                    change.consume()
                                    if (!stillPressed) break
                                }
                                val widthPx = size.width.toFloat().coerceAtLeast(1f)
                                val heightPx = size.height.toFloat().coerceAtLeast(1f)
                                val x = (start.x / widthPx).coerceIn(0f, 1f)
                                val y = (start.y / heightPx).coerceIn(0f, 1f)
                                if (moved) {
                                    val endX = (end.x / widthPx).coerceIn(0f, 1f)
                                    val endY = (end.y / heightPx).coerceIn(0f, 1f)
                                    val duration = (endTime - down.uptimeMillis).coerceIn(60L, 1_500L)
                                    onRemoteSwipe(x, y, endX, endY, duration)
                                } else {
                                    onRemoteTap(x, y)
                                }
                            }
                        }
                ) {
                    AndroidView(
                        factory = { renderer },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberRenderer(context: Context, eglContext: EglBase.Context): SurfaceViewRenderer {
    return remember(context, eglContext) {
        SurfaceViewRenderer(context).apply {
            runCatching {
                init(eglContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setEnableHardwareScaler(true)
                setMirror(false)
            }
        }
    }
}
