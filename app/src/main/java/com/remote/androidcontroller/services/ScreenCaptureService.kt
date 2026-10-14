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
import com.remote.androidcontroller.RemoteApp

/**
 * Keeps a user-approved MediaProjection session within Android's required foreground-service
 * lifetime. Capture only begins after the system consent activity returns RESULT_OK.
 */
class ScreenCaptureService : Service() {
    companion object {
        private const val NOTIFICATION_ID = 1003
        const val ACTION_START = "com.remote.androidcontroller.action.START_SCREEN_CAPTURE"
        const val ACTION_STOP = "com.remote.androidcontroller.action.STOP_SCREEN_CAPTURE"
        private const val EXTRA_RESULT_CODE = "screen_result_code"
        private const val EXTRA_RESULT_DATA = "screen_result_data"

        @Volatile private var startCallback: ((Int, Intent) -> Unit)? = null
        @Volatile private var stopCallback: (() -> Unit)? = null
        @Volatile private var failureCallback: ((String) -> Unit)? = null

        fun start(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            onForegroundReady: (Int, Intent) -> Unit,
            onNotificationStop: () -> Unit,
            onFailure: (String) -> Unit
        ) {
            startCallback = onForegroundReady
            stopCallback = onNotificationStop
            failureCallback = onFailure
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            // Callers stop the WebRTC capturer first. stopService() is allowed even while Android
            // has backgrounded the app, unlike starting a new ordinary service on Android 8+.
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                runCatching { stopCallback?.invoke() }
                stopCallback = null
                startCallback = null
                failureCallback = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                try {
                    startProjectionForeground()
                } catch (e: Exception) {
                    val callback = failureCallback
                    startCallback = null
                    stopCallback = null
                    failureCallback = null
                    // startForeground() can have partially succeeded before an exception. Remove
                    // the notification in either case so the UI cannot imply an active capture.
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                    stopSelf(startId)
                    callback?.invoke("Android-Foreground-Service konnte nicht gestartet werden: ${e.message ?: e.javaClass.simpleName}")
                    return START_NOT_STICKY
                }
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
                }
                if (resultCode == Int.MIN_VALUE || resultData == null) {
                    val failed = failureCallback
                    startCallback = null
                    stopCallback = null
                    failureCallback = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    failed?.invoke("Android hat kein gültiges Bildschirmfreigabe-Token geliefert.")
                    return START_NOT_STICKY
                }
                // This callback runs only after startForeground has succeeded. The WebRTC manager
                // creates ScreenCapturerAndroid from the user-granted token, never from a guess.
                val callback = startCallback
                val failed = failureCallback
                startCallback = null
                failureCallback = null
                try {
                    if (callback == null) throw IllegalStateException("Start-Rückruf ist nicht mehr verfügbar")
                    callback.invoke(resultCode, resultData)
                } catch (e: Exception) {
                    stopCallback = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    failed?.invoke("Bildschirmaufnahme konnte nicht initialisiert werden: ${e.message ?: e.javaClass.simpleName}")
                }
                return START_NOT_STICKY
            }
            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
    }

    private fun startProjectionForeground() {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            NOTIFICATION_ID,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, RemoteApp.CHANNEL_SCREEN_CAPTURE_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Bildschirm wird freigegeben")
            .setContentText("Bildschirmaufnahme läuft; die Übertragung hängt von der Netzwerkverbindung ab.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, "Freigabe beenden", stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // Do not leave callbacks retaining an old ViewModel if Android stops the service.
        startCallback = null
        stopCallback = null
        failureCallback = null
    }
}
