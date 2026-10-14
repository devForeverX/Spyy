package com.remote.androidcontroller

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class RemoteApp : Application() {

    companion object {
        const val CHANNEL_SESSION_ID = "remote_control_session_channel"
        const val CHANNEL_AUDIO_ID = "remote_control_audio_channel"
        const val CHANNEL_SCREEN_CAPTURE_ID = "remote_control_screen_capture_channel"
        lateinit var instance: RemoteApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Session Foreground Service Channel (connectedDevice)
            val sessionChannel = NotificationChannel(
                CHANNEL_SESSION_ID,
                getString(R.string.channel_session_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_session_desc)
                setShowBadge(false)
            }

            // Audio Streaming Playback Channel (mediaPlayback)
            val audioChannel = NotificationChannel(
                CHANNEL_AUDIO_ID,
                getString(R.string.channel_audio_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_audio_desc)
                setShowBadge(false)
            }

            val screenCaptureChannel = NotificationChannel(
                CHANNEL_SCREEN_CAPTURE_ID,
                "Bildschirmfreigabe",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zeigt an, wenn der Bildschirm für das gekoppelte Gerät freigegeben ist."
                setShowBadge(false)
            }

            notificationManager.createNotificationChannel(sessionChannel)
            notificationManager.createNotificationChannel(audioChannel)
            notificationManager.createNotificationChannel(screenCaptureChannel)
        }
    }
}
