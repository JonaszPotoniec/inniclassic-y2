package com.themoon.y1.navigation

import android.view.InputDevice
import android.view.KeyEvent

/**
 * Media keys from a remote (Bluetooth AVRCP: a car or headphones; a wired headset) always act, also with the screen
 * off and Screen-Off Control Off. Screen-Off Control is only for the Y2's own buttons, so that they do not act in a pocket.
 */
object RemoteMediaKeys {

    /** Input devices of the Y2's own buttons: the click wheel and its buttons, and the side keys (`getevent -i`). */
    private val OWN_BUTTON_DEVICES = setOf("mtk-tpd-kpd", "mtk-kpd", "mtk-tpd")

    fun isMediaKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_HEADSETHOOK -> true
        else -> false
    }

    /** True for the Y2's own buttons. A key from any other device (also an unknown or injected one) is a remote key. */
    fun isOwnButtonDevice(deviceName: String?): Boolean = deviceName != null && deviceName in OWN_BUTTON_DEVICES

    /** True when a key acts while the screen is off: a remote media key always, the other keys only with Screen-Off Control. */
    fun actsWithScreenOff(keyCode: Int, deviceName: String?, isScreenOffControlEnabled: Boolean): Boolean =
        isScreenOffControlEnabled || (isMediaKey(keyCode) && !isOwnButtonDevice(deviceName))

    fun actsWithScreenOff(event: KeyEvent, isScreenOffControlEnabled: Boolean): Boolean =
        actsWithScreenOff(event.keyCode, deviceName(event), isScreenOffControlEnabled)

    /** What a key does with the screen off, and why, for the log: "acts (remote key)", "ignored (Screen-Off Control Off)". */
    fun screenOffDecision(keyCode: Int, deviceName: String?, isScreenOffControlEnabled: Boolean): String = when {
        isMediaKey(keyCode) && !isOwnButtonDevice(deviceName) -> "acts (remote key)"
        isScreenOffControlEnabled -> "acts (Screen-Off Control On)"
        else -> "ignored (Screen-Off Control Off)"
    }

    fun deviceName(event: KeyEvent): String? = try {
        InputDevice.getDevice(event.deviceId)?.name
    } catch (_: Exception) {
        null
    }
}
