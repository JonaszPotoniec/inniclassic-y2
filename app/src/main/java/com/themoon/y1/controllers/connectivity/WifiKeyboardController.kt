package com.themoon.y1.controllers.connectivity

import android.app.Activity
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.themoon.y1.R
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.navigation.InputRouter
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.views.QwertyKeyboardView

/**
 * Controller for the click-wheel driven On-Screen Keyboard.
 * Supports both standard QWERTY grid view and legacy linear scrollwheel strip across
 * Wi-Fi passwords, Podcast searches, Last.fm credentials, library searches, and Jellyfin setup.
 */
class WifiKeyboardController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val clickFeedback: () -> Unit,
    private val onExitKeyboard: (mode: Int) -> Unit,
    private val onSubmitSearchPodcast: (keyword: String) -> Unit,
    private val onSubmitSearchLibrary: (keyword: String) -> Unit,
    private val onSubmitLastFmUsername: (username: String) -> Unit,
    private val onSubmitLastFmPassword: (password: String) -> Unit,
    private val onSubmitJellyfinServerUrl: (url: String) -> Unit,
    private val onSubmitJellyfinUsername: (username: String) -> Unit,
    private val onSubmitJellyfinPassword: (password: String) -> Unit,
    private val onSubmitJellyfinToken: (token: String) -> Unit,
    private val onConnectWifi: (ssid: String, password: String, isOpen: Boolean) -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    private val layoutWifiKeyboard: LinearLayout = rootView.findViewById(R.id.layout_wifi_keyboard)
    private val layoutKeyboardOriginal: LinearLayout = rootView.findViewById(R.id.layout_keyboard_original)
    private val keyboardQwertyView: QwertyKeyboardView? = rootView.findViewById(R.id.keyboard_qwerty_view)

    private val tvKeyboardSsid: TextView = rootView.findViewById(R.id.tv_keyboard_ssid)
    private val tvKeyboardInput: TextView = rootView.findViewById(R.id.tv_keyboard_input)

    private val tvKeyPprev: TextView = rootView.findViewById(R.id.tv_key_pprev)
    private val tvKeyPrev: TextView = rootView.findViewById(R.id.tv_key_prev)
    private val tvKeyCurrent: TextView = rootView.findViewById(R.id.tv_key_current)
    private val tvKeyNext: TextView = rootView.findViewById(R.id.tv_key_next)
    private val tvKeyNnext: TextView = rootView.findViewById(R.id.tv_key_nnext)

    private val prefs = activity.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

    var currentKeyboardMode = 0
    var targetWifiSsid = ""
    var isTargetWifiOpen = false
    var typedText = ""
    var pendingLastFmUsername = ""
    var pendingJellyfinUsername = ""

    private var keyboardIndex = 0
    private var isBackLongPressConsumed = false
    private var isLongPressConsumed = false
    private val handler = Handler(Looper.getMainLooper())
    private val longPressBackRunnable = Runnable {
        isBackLongPressConsumed = true
        InputRouter.getInstance().suppressBackUntilRelease(350L)
        clickFeedback()
        exitKeyboard()
    }

    private val KEYBOARD_CHARS = arrayOf(
        "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u",
        "v", "w", "x", "y", "z",
        "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U",
        "V", "W", "X", "Y", "Z",
        "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
        "!", "@", "#", "$", "%", "^", "&", "*", "-", "_", "+", "=", ".", "?",
        ";", ":", ",", "/", "\\", "(", ")", "[", "]", "{", "}", "<", ">", "'", "~", "`", "|",
        "[SPACE]", "[DEL]", "[CONN]"
    )

    init {
        keyboardQwertyView?.setOnKeyboardActionListener(object : QwertyKeyboardView.OnKeyboardActionListener {
            override fun onText(text: String) {
                clickFeedback()
                typedText += text
                updateKeyboardUI()
            }

            override fun onDelete() {
                clickFeedback()
                if (typedText.isNotEmpty()) {
                    typedText = typedText.substring(0, typedText.length - 1)
                    updateKeyboardUI()
                }
            }

            override fun onClear() {
                clickFeedback()
                typedText = ""
                updateKeyboardUI()
            }

            override fun onSubmit() {
                clickFeedback()
                submitKeyboard()
            }

            override fun onSpecialChar(specialChar: String, replaceLast: Boolean) {
                clickFeedback()
                if (replaceLast && typedText.isNotEmpty()) {
                    typedText = typedText.substring(0, typedText.length - 1) + specialChar
                } else {
                    typedText += specialChar
                }
                updateKeyboardUI()
            }
        })
    }

    override fun onEnter(state: ScreenState) {
        handler.removeCallbacks(longPressBackRunnable)
        isBackLongPressConsumed = false
        layoutWifiKeyboard.visibility = View.VISIBLE
        if (state is ScreenState.WifiKeyboard) {
            targetWifiSsid = state.ssid
        }
        openKeyboard()
    }

    override fun onExit() {
        handler.removeCallbacks(longPressBackRunnable)
        isBackLongPressConsumed = false
        layoutWifiKeyboard.visibility = View.GONE
    }

    fun isQwertyKeyboard(): Boolean {
        return "qwerty".equals(prefs.getString("keyboard_layout", "qwerty"), ignoreCase = true)
    }

    fun openKeyboard() {
        if (currentKeyboardMode !in 5..8) {
            typedText = ""
        }
        keyboardIndex = 0
        try {
            Settings.System.putInt(activity.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 0)
        } catch (ignored: Exception) {}

        when (currentKeyboardMode) {
            1 -> tvKeyboardSsid.text = "🔍 " + localize("Search Podcast")
            2 -> tvKeyboardSsid.text = localize("Last.fm Username")
            3 -> tvKeyboardSsid.text = localize("Last.fm Password") + ": " + pendingLastFmUsername
            4 -> tvKeyboardSsid.text = "🔍 " + localize("Search")
            5 -> tvKeyboardSsid.text = localize("Server URL")
            6 -> tvKeyboardSsid.text = localize("Username")
            7 -> tvKeyboardSsid.text = localize("Password") + ": " + pendingJellyfinUsername
            8 -> tvKeyboardSsid.text = localize("API Token")
            else -> tvKeyboardSsid.text = localize("Target") + ": " + targetWifiSsid
        }

        if (isQwertyKeyboard()) {
            layoutKeyboardOriginal.visibility = View.GONE
            keyboardQwertyView?.visibility = View.VISIBLE
            var actionLabel = localize("Connect")
            when (currentKeyboardMode) {
                1, 4 -> actionLabel = localize("Search")
                2, 6 -> actionLabel = localize("Next")
                3, 7, 8 -> actionLabel = localize("Login")
                5 -> actionLabel = localize("Save")
            }
            keyboardQwertyView?.setSubmitActionLabel(actionLabel)
            keyboardQwertyView?.resetState()
        } else {
            layoutKeyboardOriginal.visibility = View.VISIBLE
            keyboardQwertyView?.visibility = View.GONE
        }

        updateKeyboardUI()
    }

    private fun updateKeyboardUI() {
        val len = KEYBOARD_CHARS.size
        val idxPprev = (keyboardIndex - 2 + len) % len
        val idxPrev = (keyboardIndex - 1 + len) % len
        val idxNext = (keyboardIndex + 1) % len
        val idxNnext = (keyboardIndex + 2) % len
        tvKeyPprev.text = KEYBOARD_CHARS[idxPprev]
        tvKeyPrev.text = KEYBOARD_CHARS[idxPrev]
        tvKeyCurrent.text = KEYBOARD_CHARS[keyboardIndex]
        tvKeyNext.text = KEYBOARD_CHARS[idxNext]
        tvKeyNnext.text = KEYBOARD_CHARS[idxNnext]

        if (isTargetWifiOpen && currentKeyboardMode == 0) {
            tvKeyboardInput.text = localize("Open Network (Direct Connect)")
            tvKeyboardInput.setTextColor(0x77FFFFFF)
            keyboardIndex = len - 1
            tvKeyCurrent.text = KEYBOARD_CHARS[keyboardIndex]
        } else {
            if (typedText.isEmpty()) {
                tvKeyboardInput.setTextColor(0x77FFFFFF)
                when (currentKeyboardMode) {
                    1 -> tvKeyboardInput.text = localize("Enter keyword...")
                    2 -> tvKeyboardInput.text = localize("Enter Last.fm username...")
                    3 -> tvKeyboardInput.text = localize("Enter Last.fm password...")
                    4 -> tvKeyboardInput.text = localize("Enter search term...")
                    5 -> tvKeyboardInput.text = "http://..."
                    6 -> tvKeyboardInput.text = localize("Username")
                    7 -> tvKeyboardInput.text = localize("Password")
                    8 -> tvKeyboardInput.text = localize("API Token")
                    else -> tvKeyboardInput.text = localize("Enter Password...")
                }
            } else {
                tvKeyboardInput.setTextColor(0xFFFFFFFF.toInt())
                tvKeyboardInput.text = typedText
            }
        }
    }

    private fun handleKeyboardInput() {
        val selectedChar = KEYBOARD_CHARS[keyboardIndex]
        clickFeedback()

        when (selectedChar) {
            "[DEL]" -> {
                if (typedText.isNotEmpty()) {
                    typedText = typedText.substring(0, typedText.length - 1)
                }
            }
            "[SPACE]" -> {
                typedText += " "
            }
            "[CONN]" -> {
                submitKeyboard()
                return
            }
            else -> {
                typedText += selectedChar
            }
        }
        updateKeyboardUI()
    }

    fun submitKeyboard() {
        when (currentKeyboardMode) {
            1 -> {
                if (typedText.trim().isEmpty()) {
                    Toast.makeText(activity, localize("Please enter a keyword."), Toast.LENGTH_SHORT).show()
                    return
                }
                val keyword = typedText.trim()
                onSubmitSearchPodcast(keyword)
            }
            4 -> {
                if (typedText.trim().isEmpty()) {
                    Toast.makeText(activity, localize("Please enter a keyword."), Toast.LENGTH_SHORT).show()
                    return
                }
                val keyword = typedText.trim()
                currentKeyboardMode = 0
                onSubmitSearchLibrary(keyword)
            }
            2 -> {
                if (typedText.trim().isEmpty()) {
                    Toast.makeText(activity, localize("Please enter a username."), Toast.LENGTH_SHORT).show()
                    return
                }
                pendingLastFmUsername = typedText.trim()
                currentKeyboardMode = 3
                openKeyboard()
                onSubmitLastFmUsername(pendingLastFmUsername)
            }
            3 -> {
                if (typedText.isEmpty()) {
                    Toast.makeText(activity, localize("Please enter a password."), Toast.LENGTH_SHORT).show()
                    return
                }
                val password = typedText
                currentKeyboardMode = 0
                onSubmitLastFmPassword(password)
            }
            5 -> {
                if (typedText.trim().isEmpty()) {
                    Toast.makeText(activity, localize("Please enter a keyword."), Toast.LENGTH_SHORT).show()
                    return
                }
                val url = typedText.trim()
                currentKeyboardMode = 0
                onSubmitJellyfinServerUrl(url)
            }
            6 -> {
                if (typedText.trim().isEmpty()) {
                    Toast.makeText(activity, localize("Please enter a username."), Toast.LENGTH_SHORT).show()
                    return
                }
                pendingJellyfinUsername = typedText.trim()
                currentKeyboardMode = 7
                openKeyboard()
                onSubmitJellyfinUsername(pendingJellyfinUsername)
            }
            7 -> {
                val password = typedText
                if (password.isEmpty()) {
                    Toast.makeText(activity, localize("Please enter a password."), Toast.LENGTH_SHORT).show()
                    return
                }
                currentKeyboardMode = 0
                onSubmitJellyfinPassword(password)
            }
            8 -> {
                if (typedText.trim().isEmpty()) {
                    Toast.makeText(activity, localize("Please enter a keyword."), Toast.LENGTH_SHORT).show()
                    return
                }
                val token = typedText.trim()
                currentKeyboardMode = 0
                onSubmitJellyfinToken(token)
            }
            else -> {
                val password = typedText
                onConnectWifi(targetWifiSsid, password, isTargetWifiOpen)
            }
        }
    }

    fun exitKeyboard() {
        handler.removeCallbacks(longPressBackRunnable)
        val mode = currentKeyboardMode
        currentKeyboardMode = 0
        onExitKeyboard(mode)
    }

    override fun onWheel(delta: Int, event: KeyEvent): Boolean {
        if (isQwertyKeyboard()) {
            if (delta > 0) {
                keyboardQwertyView?.moveFocus(1, 0, true)
            } else if (delta < 0) {
                keyboardQwertyView?.moveFocus(-1, 0, true)
            }
            clickFeedback()
            return true
        }

        // Linear keyboard
        val len = KEYBOARD_CHARS.size
        keyboardIndex = if (delta > 0) {
            (keyboardIndex + 1) % len
        } else {
            (keyboardIndex - 1 + len) % len
        }
        updateKeyboardUI()
        clickFeedback()
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action
        if (isQwertyKeyboard()) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == 4) {
                if (action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) {
                        isBackLongPressConsumed = false
                        handler.removeCallbacks(longPressBackRunnable)
                        handler.postDelayed(longPressBackRunnable, 450L)
                    } else if (!isBackLongPressConsumed && (event.eventTime - event.downTime) >= 450L) {
                        handler.removeCallbacks(longPressBackRunnable)
                        isBackLongPressConsumed = true
                        InputRouter.getInstance().suppressBackUntilRelease(350L)
                        clickFeedback()
                        exitKeyboard()
                    }
                    return true
                } else if (action == KeyEvent.ACTION_UP) {
                    handler.removeCallbacks(longPressBackRunnable)
                    if (isBackLongPressConsumed) {
                        isBackLongPressConsumed = false
                        return true
                    }
                    keyboardQwertyView?.moveFocus(0, -1, false)
                    clickFeedback()
                    return true
                }
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == 19) {
                if (action == KeyEvent.ACTION_DOWN) {
                    keyboardQwertyView?.moveFocus(0, -1, false)
                    clickFeedback()
                }
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == 85
                || keyCode == KeyEvent.KEYCODE_MEDIA_STOP || keyCode == 86
                || keyCode == 126 || keyCode == 127
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == 20) {
                if (action == KeyEvent.ACTION_DOWN) {
                    keyboardQwertyView?.moveFocus(0, 1, false)
                    clickFeedback()
                }
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == 88) {
                if (action == KeyEvent.ACTION_DOWN) {
                    keyboardQwertyView?.moveFocus(-1, 0, false)
                    clickFeedback()
                }
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == 87) {
                if (action == KeyEvent.ACTION_DOWN) {
                    keyboardQwertyView?.moveFocus(1, 0, false)
                    clickFeedback()
                }
                return true
            }
        } else {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == 4) {
                if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    InputRouter.getInstance().suppressBackUntilRelease(300L)
                    clickFeedback()
                    exitKeyboard()
                }
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == 85
                || keyCode == KeyEvent.KEYCODE_MEDIA_STOP || keyCode == 86
                || keyCode == 126 || keyCode == 127
                || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == 88
                || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == 87) {
                return true
            }
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == 4) {
            if (isQwertyKeyboard()) {
                if (event.repeatCount == 0) {
                    isBackLongPressConsumed = false
                    handler.removeCallbacks(longPressBackRunnable)
                    handler.postDelayed(longPressBackRunnable, 450L)
                } else if (!isBackLongPressConsumed && (event.eventTime - event.downTime) >= 450L) {
                    handler.removeCallbacks(longPressBackRunnable)
                    isBackLongPressConsumed = true
                    InputRouter.getInstance().suppressBackUntilRelease(350L)
                    clickFeedback()
                    exitKeyboard()
                }
                return true
            } else {
                if (event.repeatCount == 0) {
                    InputRouter.getInstance().suppressBackUntilRelease(300L)
                    clickFeedback()
                    exitKeyboard()
                }
                return true
            }
        }

        if (isQwertyKeyboard()) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == 19) {
                keyboardQwertyView?.moveFocus(0, -1, false)
                clickFeedback()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == 85
                || keyCode == KeyEvent.KEYCODE_MEDIA_STOP || keyCode == 86
                || keyCode == 126 || keyCode == 127
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == 20) {
                keyboardQwertyView?.moveFocus(0, 1, false)
                clickFeedback()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == 88) {
                keyboardQwertyView?.moveFocus(-1, 0, false)
                clickFeedback()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == 87) {
                keyboardQwertyView?.moveFocus(1, 0, false)
                clickFeedback()
                return true
            }
            if (keyCode == 21) { // Wheel CCW
                keyboardQwertyView?.moveFocus(-1, 0, true)
                clickFeedback()
                return true
            }
            if (keyCode == 22) { // Wheel CW
                keyboardQwertyView?.moveFocus(1, 0, true)
                clickFeedback()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.repeatCount == 0) {
                    event.startTracking()
                }
                return true
            }
            return true
        }

        // Linear keyboard
        if (keyCode == 21) {
            keyboardIndex = (keyboardIndex - 1 + KEYBOARD_CHARS.size) % KEYBOARD_CHARS.size
            updateKeyboardUI()
            clickFeedback()
            return true
        }
        if (keyCode == 22) {
            keyboardIndex = (keyboardIndex + 1) % KEYBOARD_CHARS.size
            updateKeyboardUI()
            clickFeedback()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.repeatCount == 0) {
                event.startTracking()
            }
            return true
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == 4) {
            handler.removeCallbacks(longPressBackRunnable)
            if (isQwertyKeyboard()) {
                if (isBackLongPressConsumed) {
                    isBackLongPressConsumed = false
                    return true
                }
                keyboardQwertyView?.moveFocus(0, -1, false)
                clickFeedback()
                return true
            } else {
                return true
            }
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (isLongPressConsumed) {
                isLongPressConsumed = false
                return true
            }
            if ((event.flags and KeyEvent.FLAG_CANCELED_LONG_PRESS) == 0) {
                if (isQwertyKeyboard()) {
                    keyboardQwertyView?.performCenterClick()
                } else {
                    handleKeyboardInput()
                }
            }
            return true
        }

        return false
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == 4) {
            if (isQwertyKeyboard()) {
                handler.removeCallbacks(longPressBackRunnable)
                isBackLongPressConsumed = true
                InputRouter.getInstance().suppressBackUntilRelease(350L)
                clickFeedback()
                exitKeyboard()
                return true
            }
            return true
        }

        if (isQwertyKeyboard()) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                isLongPressConsumed = true
                keyboardQwertyView?.performCenterLongPress(typedText)
                return true
            }
        }
        return false
    }

    override fun onBackPressed(): Boolean {
        handler.removeCallbacks(longPressBackRunnable)
        isBackLongPressConsumed = false
        InputRouter.getInstance().suppressBackUntilRelease(300L)
        clickFeedback()
        exitKeyboard()
        return true
    }
}
