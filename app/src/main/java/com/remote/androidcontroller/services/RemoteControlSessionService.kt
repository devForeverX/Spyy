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
import com.remote.androidcontroller.R
import com.remote.androidcontroller.RemoteApp

class RemoteControlSessionService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START_SESSION"
        const val ACTION_STOP = "ACTION_STOP_SESSION"
        const val EXTRA_PEER_NAME = "EXTRA_PEER_NAME"

        fun startService(context: Context, peerName: String) {
            val intent = Intent(context, RemoteControlSessionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PEER_NAME, peerName)
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
                startForegroundNotification(peerName)
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
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
