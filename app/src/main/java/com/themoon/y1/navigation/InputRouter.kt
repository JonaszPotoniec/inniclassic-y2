package com.themoon.y1.navigation

import android.content.Context
import android.os.PowerManager
import android.view.KeyEvent
import com.themoon.y1.MainActivity
import com.themoon.y1.controllers.ScreenController

/**
 * Handles global key intercepts (wake logic, volume overlay, UMS blocking)
 * and delegates wheel/button events to active ScreenController or NavigationManager.
 */
class InputRouter private constructor() {

    fun dispatchKeyEvent(
        event: KeyEvent,
        context: Context,
        isFakeScreenOff: Boolean,
        onWakeFromFakeScreenOff: () -> Unit,
        onResetBacklightTimer: () -> Unit,
        isUsbStorageActive: Boolean,
        onStopUsbStorage: () -> Unit,
        onAdjustVolume: (isUp: Boolean) -> Unit,
        activeController: ScreenController?
    ): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isScreenOn = pm?.isScreenOn ?: true

        if (isScreenOn && !isFakeScreenOff) {
            onResetBacklightTimer()
        }

        // Intercept while USB mass storage modal is active
        if (isUsbStorageActive) {
            if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (action == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    onStopUsbStorage()
                }
                return true
            }
        }

        // Global volume button interception
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0 || (event.repeatCount % 2 == 0)) {
                    onAdjustVolume(keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                }
            }
            return true
        }

        // Wake from fake screen off
        if (isFakeScreenOff) {
            if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                onWakeFromFakeScreenOff()
            }
            return true
        }

        // Hardware wheel / capacitive swipe routing
        if (keyCode == 21 || keyCode == 22) { // KEYCODE_DPAD_LEFT / KEYCODE_DPAD_RIGHT
            if (action == KeyEvent.ACTION_DOWN) {
                val delta = if (keyCode == 22) 1 else -1
                if (activeController?.onWheel(delta, event) == true) {
                    return true
                }
            }
        }

        // Delegate key event to active controller
        if (action == KeyEvent.ACTION_DOWN) {
            if (activeController?.onKeyDown(keyCode, event) == true) {
                return true
            }
        } else if (action == KeyEvent.ACTION_UP) {
            if (activeController?.onKeyUp(keyCode, event) == true) {
                return true
            }
        }

        // Default back button delegation
        if (keyCode == KeyEvent.KEYCODE_BACK && action == KeyEvent.ACTION_DOWN) {
            if (NavigationManager.getInstance().navigateBack()) {
                return true
            }
        }

        return false
    }

    companion object {
        @Volatile
        private var instance: InputRouter? = null

        @JvmStatic
        fun getInstance(): InputRouter {
            return instance ?: synchronized(this) {
                instance ?: InputRouter().also { instance = it }
            }
        }
    }
}
