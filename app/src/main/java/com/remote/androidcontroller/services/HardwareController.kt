package com.remote.androidcontroller.services

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.remote.androidcontroller.network.VibrationPattern

class HardwareController(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    /**
     * Controls physical device flashlight / torch with real error handling.
     */
    fun setFlashlight(enabled: Boolean): Boolean {
        val cm = cameraManager ?: return false
        return try {
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                val chars = cm.getCameraCharacteristics(id)
                chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false

            cm.setTorchMode(cameraId, enabled)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Triggers vibration patterns.
     */
    fun triggerVibration(pattern: VibrationPattern): Boolean {
        return try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return false

            if (!vibrator.hasVibrator()) return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                when (pattern) {
                    VibrationPattern.SHORT_PULSE -> {
                        vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                    VibrationPattern.DOUBLE_PULSE -> {
                        val timings = longArrayOf(0, 100, 100, 100)
                        vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
                    }
                    VibrationPattern.SOS_ALARM -> {
                        val timings = longArrayOf(0, 150, 100, 150, 100, 150, 300, 400, 100, 400, 100, 400, 300, 150, 100, 150)
                        vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(200)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Changes system volume. Returns actual new volume level or -1 on error.
     */
    fun adjustVolume(directionUp: Boolean): Int {
        val am = audioManager ?: return -1
        return try {
            val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val dir = if (directionUp) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
            // A successful API call is not enough at the min/max boundary; require a read-back change.
            val actual = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val changedAsRequested = if (directionUp) actual > before else actual < before
            actual.takeIf { it >= 0 && changedAsRequested } ?: -1
        } catch (_: SecurityException) {
            -1
        } catch (_: RuntimeException) {
            -1
        }
    }

    fun setVolume(volumeLevel: Int): Int {
        val am = audioManager ?: return -1
        return try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max < 0) return -1
            val target = volumeLevel.coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
            // Read back because policy restrictions can prevent the requested level from applying.
            am.getStreamVolume(AudioManager.STREAM_MUSIC).takeIf { it == target } ?: -1
        } catch (_: SecurityException) {
            -1
        } catch (_: RuntimeException) {
            -1
        }
    }

    fun getCurrentVolume(): Int = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
    fun getMaxVolume(): Int = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: -1

    /**
     * Changes screen brightness if WRITE_SETTINGS is granted, and verifies write.
     */
    fun setScreenBrightness(percent: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(context)) {
                return false
            }
        }
        if (percent !in 0..100) return false
        return try {
            val brightnessValue = (percent * 255 / 100).coerceIn(0, 255)
            val success = Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightnessValue
            )
            if (!success) return false

            // Read back to confirm
            val actual = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            actual == brightnessValue
        } catch (e: Exception) {
            false
        }
    }

    /** Actual physical display dimensions, or an explicit unknown marker if Android cannot report them. */
    fun getDisplayResolution(): String {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return "Unbekannt"
        val metrics = DisplayMetrics()
        return try {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                "${metrics.widthPixels}x${metrics.heightPixels}"
            } else {
                "Unbekannt"
            }
        } catch (_: RuntimeException) {
            "Unbekannt"
        }
    }

    fun getCurrentBrightnessPercent(): Int? {
        return try {
            val value = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
            (value * 100 / 255).coerceIn(0, 100)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Manually copies text into system clipboard.
     */
    fun copyToClipboard(text: String): Boolean {
        val cm = clipboardManager ?: return false
        return try {
            val clip = ClipData.newPlainText("Remote Text", text)
            cm.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Retrieves actual real battery metrics from Android BatteryManager.
     */
    fun getBatteryMetrics(): Pair<Int, Boolean> {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return Pair(-1, false)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        // BatteryManager reports -1 when a property is unavailable; preserve that instead of faking 0%.
        return Pair(level.takeIf { it in 0..100 } ?: -1, isCharging)
    }

    /**
     * Real permission checks querying Android package manager and settings.
     */
    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasWriteSettingsPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(context)
        } else {
            true
        }
    }
}
