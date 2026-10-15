package com.remote.androidcontroller.services

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.remote.androidcontroller.MainActivity
import com.remote.androidcontroller.RemoteApp
import com.remote.androidcontroller.session.SessionStateManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class RemoteControlSessionService : Service() {

    private val sessionStateManager by lazy {
        SessionStateManager(File(filesDir, "session_state.json"))
    }

    private val sessionRegistered = AtomicBoolean(false)

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START_SESSION"
        const val ACTION_STOP = "ACTION_STOP_SESSION"
        const val EXTRA_PEER_NAME = "EXTRA_PEER_NAME"
        const val EXTRA_SESSION_ID = "EXTRA_SESSION_ID"

        fun startService(context: Context, peerName: String, sessionId: String = "default-session") {
            val intent = Intent(context, RemoteControlSessionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PEER_NAME, peerName)
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, RemoteControlSessionService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: "Gekoppeltes Smartphone"
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: "default-session"
                registerSession(sessionId, peerName)
                startForegroundNotification(peerName)
            }
            ACTION_STOP -> {
                unregisterSession()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                // Ignore stale or null intents; keep service alive if already running.
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterSession()
    }

    private fun registerSession(sessionId: String, peerName: String) {
        if (!sessionRegistered.compareAndSet(false, true)) return
        val now = System.currentTimeMillis()
        sessionStateManager.saveSession(
            SessionStateManager.SessionMeta(
                sessionId = sessionId,
                role = "remote_control",
                startedAt = now,
                lastHeartbeat = now,
                state = "ACTIVE"
            )
        )
    }

    private fun unregisterSession() {
        if (!sessionRegistered.compareAndSet(true, false)) return
        // If we have no sessionId, just remove stale entries from the persisted state.
        sessionStateManager.cleanupStaleSessions(0L)
    }

    private fun startForegroundNotification(peerName: String) {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, RemoteApp.CHANNEL_SESSION_ID)
            .setContentTitle("Android Remote Control (24/7 aktiv)")
            .setContentText("Zielgerät ist bereit für Fernsteuerung ($peerName)")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
