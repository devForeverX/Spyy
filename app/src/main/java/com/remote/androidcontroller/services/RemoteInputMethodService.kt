package com.remote.androidcontroller.services

import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.TextView

class RemoteInputMethodService : InputMethodService() {

    companion object {
        @Volatile
        private var instance: RemoteInputMethodService? = null

        fun isServiceRunning(): Boolean = instance != null

        fun hasActiveInputConnection(): Boolean = instance?.currentInputConnection != null

        fun isImeEnabled(context: Context): Boolean {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
            val enabledList = imm.enabledInputMethodList
            val myPackage = context.packageName
            return enabledList.any { it.packageName == myPackage }
        }

        fun isImeSelected(context: Context): Boolean {
            val currentIme = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            ) ?: return false
            val myComponent = ComponentName(context, RemoteInputMethodService::class.java).flattenToString()
            return currentIme == myComponent || currentIme.contains(context.packageName)
        }

        fun insertText(text: String): Boolean {
            val service = instance ?: return false
            val ic = service.currentInputConnection ?: return false
            return ic.commitText(text, 1)
        }

        fun sendSpecialKey(isEnter: Boolean, isBackspace: Boolean): Boolean {
            val service = instance ?: return false
            val ic = service.currentInputConnection ?: return false

            return if (isBackspace) {
                ic.deleteSurroundingText(1, 0) ||
                        (ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)) &&
                                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)))
            } else if (isEnter) {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)) &&
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            } else {
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onCreateInputView(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#121214"))
        }

        val textView = TextView(this).apply {
            text = "⚡ Remote Control Tastatur aktiv\nEingaben werden vom Controller übertragen"
            setTextColor(Color.parseColor("#34D399"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(32, 24, 32, 24)
        }

        root.addView(textView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        })

        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        instance = this
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }
}
