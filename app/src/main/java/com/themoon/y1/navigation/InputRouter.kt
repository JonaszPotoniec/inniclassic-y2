package com.themoon.y1.navigation

import android.app.Activity
import android.content.Context
import android.os.PowerManager
import android.view.KeyEvent
import android.view.View
import android.widget.AbsListView
import android.widget.LinearLayout
import com.themoon.y1.MainActivity
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.AudioSources
import java.lang.ref.WeakReference

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

    private val backPress = BackPress()

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

    // One centre push does one action, on release (see CentrePressTracker)
    private val centrePress = CentrePressTracker()
    // The focused view when the centre press came: it can have taken the press
    private var centrePressView: WeakReference<View>? = null

    private fun isCentreKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER

    /**
     * A dropped release: the view that had the press does not get it (or did not use it). Stop its pending
     * long press and its pressed state; Android 4.4 cancels them only in the view's own key-up of a click.
     */
    private fun releaseCentrePressView() {
        val view = centrePressView?.get() ?: return
        centrePressView = null
        view.cancelLongPress()
        if (view.isPressed) view.isPressed = false
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
        // A press kept below (wake, USB storage screen) must not pair with its release
        if (isCentreKey(keyCode) && action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) centrePress.reset()
        // A Back press kept below (wake, USB storage screen, suppression) must not pair with its release
        if (isBackKey(keyCode) && action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) backPress.reset()

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
            // Media keys from a remote (Bluetooth, wired headset) always act; Screen-Off Control is for the Y2's own buttons
            if (!RemoteMediaKeys.actsWithScreenOff(event, isScreenOffControlEnabled)) {
                return true
            }
            // The same media keys as RemoteMediaKeys, also the wired headset button (HEADSETHOOK: play/pause)
            val isMediaKey = RemoteMediaKeys.isMediaKey(keyCode)

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

        if (isCentreKey(keyCode)) {
            // The real screen controller: MainActivity can give a wrapper (event log) that is new for each event
            val screen = NavigationManager.getInstance().activeController
            if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                centrePress.onPress(screen)
                centrePressView = (context as? Activity)?.currentFocus?.let { WeakReference(it) }
            } else if (action == KeyEvent.ACTION_UP && !centrePress.onRelease(screen)) {
                releaseCentrePressView()
                return true
            }
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
        // A focused row did not take this press (it takes it for its own click or long press)
        if (isCentreKey(keyCode) && event.repeatCount == 0) centrePress.onPressRouted()
        if (keyCode == 21 || keyCode == 22) {
            val delta = if (keyCode == 22) 1 else -1
            if (activeController?.onWheel(delta, event) == true) {
                return true
            }

            // A screen with no own wheel code: one step in the list of the selected row
            val focus = activeController?.rootView?.findFocus()
            val rows = focus?.parent
            val moved = when {
                focus is AbsListView -> RowFocus.step(focus, delta) // the list has the focus, not a row
                rows is AbsListView -> RowFocus.step(rows, delta)
                rows is LinearLayout -> RowFocus.step(rows, delta)
                else -> null
            }
            if (moved != null) {
                (activeController?.rootView?.context as? MainActivity)?.clickFeedback()
                return true
            }
        }
        if (activeController?.onKeyDown(keyCode, event) == true) {
            return true
        }
        if (isBackKey(keyCode)) {
            // Back on release (onKeyUp), like the other buttons: a held press does nothing yet
            val now = System.currentTimeMillis()
            if (!isBackSuppressed && now >= backDebounceUntil) backPress.onPress(event.repeatCount)
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

    private fun isBackKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_BACK || keyCode == 19 || keyCode == 4

    /** The one Back path: one click, then the screen's own Back (a level up in its lists), else the previous screen. */
    fun goBack(activeController: ScreenController?) {
        (activeController?.rootView?.context as? MainActivity)?.clickFeedback()
        if (activeController?.onBackPressed() != true) NavigationManager.getInstance().popBackStack()
    }

    fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
        activeController: ScreenController?
    ): Boolean {
        // A focused row had the press and did not use the release (its long press acted): no click
        if (isCentreKey(keyCode) && !centrePress.onReleaseRouted()) {
            releaseCentrePressView()
            return true
        }
        if (activeController?.onKeyUp(keyCode, event) == true) {
            return true
        }
        if (isBackKey(keyCode)) {
            isBackSuppressed = false
            if (backPress.onRelease(event.isCanceled)) goBack(activeController)
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
