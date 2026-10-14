package com.remote.androidcontroller.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import com.remote.androidcontroller.network.NavigationAction

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

class RemoteControlAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: RemoteControlAccessibilityService? = null

        fun isServiceRunning(): Boolean = instance != null

        fun isServiceEnabledInSettings(context: Context): Boolean {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
            val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            val myPackage = context.packageName
            return enabledServices.any { it.resolveInfo.serviceInfo.packageName == myPackage }
        }

        fun performAction(action: NavigationAction): Boolean {
            val service = instance ?: return false
            return when (action) {
                NavigationAction.BACK -> service.performGlobalAction(GLOBAL_ACTION_BACK)
                NavigationAction.HOME -> service.performGlobalAction(GLOBAL_ACTION_HOME)
                NavigationAction.RECENTS -> service.performGlobalAction(GLOBAL_ACTION_RECENTS)
                NavigationAction.NOTIFICATIONS -> service.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                NavigationAction.QUICK_SETTINGS -> service.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                NavigationAction.LOCK_SCREEN -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                    } else false
                }
                NavigationAction.POWER_DIALOG -> service.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
                NavigationAction.TAKE_SCREENSHOT -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        service.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                    } else false
                }
                NavigationAction.SWIPE_UP -> dispatchSwipe(540f, 1500f, 540f, 500f, 250)
                NavigationAction.SWIPE_DOWN -> dispatchSwipe(540f, 500f, 540f, 1500f, 250)
                NavigationAction.SWIPE_LEFT -> dispatchSwipe(850f, 1000f, 150f, 1000f, 250)
                NavigationAction.SWIPE_RIGHT -> dispatchSwipe(150f, 1000f, 850f, 1000f, 250)
                NavigationAction.LOCATOR_CHIME,
                NavigationAction.VOLUME_UP,
                NavigationAction.VOLUME_DOWN -> false
            }
        }

        fun dispatchSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 250): Boolean {
            return dispatchNormalizedGesture(
                startX / 1080f,
                startY / 1920f,
                endX / 1080f,
                endY / 1920f,
                durationMs
            )
        }

        /**
         * Dispatches a user-authorized tap/swipe whose coordinates are normalized against
         * the captured display. Raw screen coordinates are never accepted from the network.
         */
        fun dispatchNormalizedGesture(
            startX: Float,
            startY: Float,
            endX: Float = startX,
            endY: Float = startY,
            durationMs: Long = 120L
        ): Boolean {
            val service = instance ?: return false
            if (!startX.isFinite() || !startY.isFinite() || !endX.isFinite() || !endY.isFinite()) return false
            val sx = startX.coerceIn(0f, 1f)
            val sy = startY.coerceIn(0f, 1f)
            val ex = endX.coerceIn(0f, 1f)
            val ey = endY.coerceIn(0f, 1f)
            val metrics = android.util.DisplayMetrics()
            val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager ?: return false
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels.toFloat().coerceAtLeast(1f)
            val height = metrics.heightPixels.toFloat().coerceAtLeast(1f)
            val path = Path().apply {
                moveTo(sx * (width - 1f), sy * (height - 1f))
                lineTo(ex * (width - 1f), ey * (height - 1f))
            }
            val safeDuration = durationMs.coerceIn(60L, 1_500L)
            val stroke = GestureDescription.StrokeDescription(path, 0L, safeDuration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return try {
                service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        // Android confirms injection asynchronously; the command ACK only confirms
                        // dispatchGesture accepted the gesture, not that the app visibly reacted.
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {}
                }, null)
            } catch (_: RuntimeException) {
                false
            }
        }

        fun setTextOnFocusedNode(text: String): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used; service exists solely for dispatching allowed global navigation actions
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }
}
