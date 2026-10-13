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

    fun interface VolumeAdjuster {
        fun adjustVolume(isUp: Boolean)
    }

    fun dispatchKeyEvent(
        event: KeyEvent,
        context: Context,
        isFakeScreenOff: Boolean,
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
        }
        if (activeController?.onKeyDown(keyCode, event) == true) {
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == 19) {
            if (activeController?.onBackPressed() == true) {
                return true
            }
            if (NavigationManager.getInstance().navigateBack()) {
                return true
            }
        }
        return false
    }

    fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
        activeController: ScreenController?
    ): Boolean {
        return activeController?.onKeyUp(keyCode, event) == true
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
