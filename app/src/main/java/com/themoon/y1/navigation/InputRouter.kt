package com.themoon.y1.navigation

import android.content.Context
import android.os.PowerManager
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.themoon.y1.MainActivity
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.AudioSources

/**
 * Handles global key intercepts (wake logic, volume overlay, UMS blocking)
 * and delegates wheel/button events to active ScreenController or NavigationManager.
 */
class InputRouter private constructor() {

    @Volatile
    var isBackSuppressed: Boolean = false
        private set

    @Volatile
    var backDebounceUntil: Long = 0L
        private set

    fun suppressBackUntilRelease(debounceMs: Long = 350L) {
        isBackSuppressed = true
        backDebounceUntil = System.currentTimeMillis() + debounceMs
    }

    fun resetBackSuppression() {
        isBackSuppressed = false
        backDebounceUntil = 0L
    }

    fun interface VolumeAdjuster {
        fun adjustVolume(isUp: Boolean)
    }

    fun dispatchKeyEvent(
        event: KeyEvent,
        context: Context,
        isFakeScreenOff: Boolean,
        isScreenOffControlEnabled: Boolean = false,
        onWakeFromFakeScreenOff: Runnable,
        onResetBacklightTimer: Runnable,
        isUsbStorageActive: Boolean,
        onStopUsbStorage: Runnable,
        onAdjustVolume: VolumeAdjuster,
        activeController: ScreenController?
    ): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isScreenOn = if (android.os.Build.VERSION.SDK_INT >= 20) {
            pm?.isInteractive ?: true
        } else {
            pm?.isScreenOn ?: true
        }

        if (isScreenOn && !isFakeScreenOff) {
            onResetBacklightTimer.run()
        }

        // Intercept while USB mass storage modal is active
        if (isUsbStorageActive) {
            if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (action == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    onStopUsbStorage.run()
                }
                return true
            }
        }

        // Global volume button interception
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0 || (event.repeatCount % 2 == 0)) {
                    onAdjustVolume.adjustVolume(keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                }
            }
            return true
        }

        // Screen is off: block or allow only media controls based on isScreenOffControlEnabled
        if (!isScreenOn) {
            if (!isScreenOffControlEnabled) {
                return true
            }
            val isMediaKey = keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                    keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                    keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
                    keyCode == 85 ||
                    keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
                    keyCode == 87 ||
                    keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
                    keyCode == 88

            if (isMediaKey) {
                if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_MEDIA_NEXT, 87 -> com.themoon.y1.managers.AudioSources.app.skipKey(true)
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS, 88 -> com.themoon.y1.managers.AudioSources.app.skipKey(false)
                        else -> com.themoon.y1.managers.AudioSources.app.playPauseKey()
                    }
                }
                return true
            }
            return true
        }

        // Wake from fake screen off
        if (isFakeScreenOff) {
            if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                onWakeFromFakeScreenOff.run()
            }
            return true
        }

        // Allow active controller to intercept raw dispatchKeyEvent if needed (when screen is on)
        if (isScreenOn && activeController?.dispatchKeyEvent(event) == true) {
            return true
        }

        // Suppress back key repeat / debounce after modal or long-press dismissal
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == 19 || keyCode == 4) {
            if (isBackSuppressed || System.currentTimeMillis() < backDebounceUntil) {
                if (action == KeyEvent.ACTION_UP) {
                    isBackSuppressed = false
                }
                return true
            }
        }

        return false
    }

    fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
        activeController: ScreenController?
    ): Boolean {
        if (keyCode == 21 || keyCode == 22) {
            val delta = if (keyCode == 22) 1 else -1
            if (activeController?.onWheel(delta, event) == true) {
                return true
            }

            val focused = activeController?.rootView?.findFocus()
            val parent = focused?.parent as? ViewGroup
            if (parent is LinearLayout) {
                val idx = parent.indexOfChild(focused)
                if (delta < 0) {
                    for (i in idx - 1 downTo 0) {
                        val child = parent.getChildAt(i)
                        if (child != null && child.visibility == View.VISIBLE && child.isFocusable) {
                            child.requestFocus()
                            (activeController?.rootView?.context as? MainActivity)?.clickFeedback()
                            return true
                        }
                    }
                } else {
                    for (i in idx + 1 until parent.childCount) {
                        val child = parent.getChildAt(i)
                        if (child != null && child.visibility == View.VISIBLE && child.isFocusable) {
                            child.requestFocus()
                            (activeController?.rootView?.context as? MainActivity)?.clickFeedback()
                            return true
                        }
                    }
                }
            }
        }
        if (activeController?.onKeyDown(keyCode, event) == true) {
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == 19 || keyCode == 4) {
            val now = System.currentTimeMillis()
            if (isBackSuppressed || now < backDebounceUntil || event.repeatCount > 0) {
                return true
            }
            if (activeController?.onBackPressed() == true) {
                return true
            }
            if (NavigationManager.getInstance().navigateBack()) {
                return true
            }
            (activeController?.rootView?.context as? MainActivity)?.clickFeedback()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE || keyCode == 85) {
            AudioSources.app.playPauseKey()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == 87) {
            AudioSources.app.skipKey(true)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == 88) {
            AudioSources.app.skipKey(false)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == 23 || keyCode == KeyEvent.KEYCODE_ENTER) {
            val main = (activeController?.rootView?.context as? MainActivity) ?: MainActivity.instance
            if (event.repeatCount == 0) {
                main?.isLongPressConsumed = false
                event.startTracking()
            }
        }
        return false
    }

    fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
        activeController: ScreenController?
    ): Boolean {
        if (activeController?.onKeyUp(keyCode, event) == true) {
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == 19 || keyCode == 4) {
            isBackSuppressed = false
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == 23 || keyCode == KeyEvent.KEYCODE_ENTER) {
            val main = (activeController?.rootView?.context as? MainActivity) ?: MainActivity.instance
            if (main?.isLongPressConsumed == true) {
                main.isLongPressConsumed = false
                return true
            }
            if ((event.flags and KeyEvent.FLAG_CANCELED_LONG_PRESS) == 0) {
                val focused = activeController?.rootView?.findFocus()
                if (focused != null && focused !is android.widget.Button && focused.isClickable) {
                    focused.performClick()
                    return true
                }
            }
        }
        return false
    }

    fun onKeyLongPress(
        keyCode: Int,
        event: KeyEvent,
        activeController: ScreenController?
    ): Boolean {
        return activeController?.onKeyLongPress(keyCode, event) == true
    }

    companion object {
        @Volatile
        private var instance: InputRouter? = null

        fun getInstance(): InputRouter {
            return instance ?: synchronized(this) {
                instance ?: InputRouter().also { instance = it }
            }
        }
    }
}
