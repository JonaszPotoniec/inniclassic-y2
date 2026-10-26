package com.themoon.y1.controllers.connectivity

import android.app.Activity
import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.views.ListItemViewFactory
import com.themoon.y1.views.TablerIcons

/**
 * Controller for Wi-Fi scanning, power toggle, and AP selection.
 */
class WifiController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val createSettingRow: (title: String, value: String) -> LinearLayout,
    private val createTablerListButton: (iconPaths: Array<String>?, text: String, rightIconPaths: Array<String>?, iconColor: Int, textColor: Int) -> View,
    private val clickFeedback: () -> Unit,
    private val onConnectToSavedNetwork: (ssid: String, netId: Int) -> Unit,
    private val onOpenWifiKeyboard: (ssid: String, isOpen: Boolean) -> Unit,
    private val onNavigateBack: () -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    private val layoutWifiMode: LinearLayout = rootView.findViewById(R.id.layout_wifi_mode)
    private val btnScanWifi: Button = rootView.findViewById(R.id.btn_scan_wifi)
    private val containerWifiItems: LinearLayout = rootView.findViewById(R.id.container_wifi_items)

    private val foundWifiNetworks = mutableSetOf<String>()

    private fun updateScanButtonFocus(hasFocus: Boolean) {
        if (hasFocus) {
            btnScanWifi.background = ListItemViewFactory.createFocusedButtonBackground(activity)
            btnScanWifi.setTextColor(ThemeManager.getListButtonFocusedTextColor())
        } else {
            btnScanWifi.background = ListItemViewFactory.createButtonBackground(activity, ThemeManager.getListButtonNormalBg())
            btnScanWifi.setTextColor(ThemeManager.getTextColorPrimary())
        }
        btnScanWifi.typeface = ThemeManager.getCustomFontBold()
    }

    init {
        btnScanWifi.isSoundEffectsEnabled = false
        updateScanButtonFocus(btnScanWifi.hasFocus())
        btnScanWifi.setOnFocusChangeListener { _, hasFocus ->
            updateScanButtonFocus(hasFocus)
        }
        btnScanWifi.setOnClickListener {
            clickFeedback()
            startWifiScan()
        }
    }

    override fun onEnter(state: ScreenState) {
        layoutWifiMode.visibility = View.VISIBLE
        updateScanButtonFocus(btnScanWifi.hasFocus())
        startWifiScan()
    }

    override fun onExit() {
        layoutWifiMode.visibility = View.GONE
    }

    override fun onRefreshUI() {
        updateScanButtonFocus(btnScanWifi.hasFocus())
        startWifiScan()
    }

    fun startWifiScan() {
        val wm = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val isOn = wm != null && wm.isWifiEnabled
        updateWifiUI(null)

        if (isOn) {
            btnScanWifi.text = localize("Scanning...")
            foundWifiNetworks.clear()
            if (containerWifiItems.childCount > 0) {
                containerWifiItems.getChildAt(0).requestFocus()
            }
            wm?.startScan()
        } else {
            btnScanWifi.text = localize("Wi-Fi is OFF")
            if (containerWifiItems.childCount > 0) {
                containerWifiItems.getChildAt(0).requestFocus()
            }
        }
    }

    fun updateWifiUI(results: List<ScanResult>?) {
        val wm = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        var isOn = false
        var statusText = "OFF"

        if (wm != null) {
            val state = wm.wifiState
            if (state == WifiManager.WIFI_STATE_ENABLED) {
                isOn = true
                statusText = "ON"
            } else if (state == WifiManager.WIFI_STATE_ENABLING || state == WifiManager.WIFI_STATE_DISABLING) {
                statusText = "Wait..."
            }
        }

        val existingToggle = containerWifiItems.findViewById<View>(WIFI_POWER_ROW_ID)
        if (existingToggle == null) {
            val btnToggle = createSettingRow(localize("Wi-Fi Power"), localize(statusText))
            btnToggle.id = WIFI_POWER_ROW_ID
            btnToggle.setOnClickListener {
                clickFeedback()
                if (wm != null) {
                    val isCurrentlyOn = wm.isWifiEnabled
                    if (isCurrentlyOn) {
                        Toast.makeText(activity, localize("Turning Wi-Fi OFF..."), Toast.LENGTH_SHORT).show()
                        wm.isWifiEnabled = false
                    } else {
                        Toast.makeText(activity, localize("Turning Wi-Fi ON..."), Toast.LENGTH_SHORT).show()
                        wm.isWifiEnabled = true
                    }
                    val tvRight = btnToggle.getChildAt(1) as? TextView
                    tvRight?.text = localize("Wait...")
                    if (!btnToggle.hasFocus()) {
                        tvRight?.setTextColor(ThemeManager.getTextColorPrimary())
                    }
                }
            }
            containerWifiItems.addView(btnToggle, 0)

            (btnScanWifi.parent as? ViewGroup)?.removeView(btnScanWifi)
            containerWifiItems.addView(btnScanWifi)
        } else {
            val btnToggle = existingToggle as LinearLayout
            val tvRight = btnToggle.getChildAt(1) as? TextView
            tvRight?.text = localize(statusText)
            if (!btnToggle.hasFocus()) {
                if (statusText == "ON") {
                    tvRight?.setTextColor(ThemeManager.getTextColorPrimary())
                } else if (statusText == "OFF") {
                    tvRight?.setTextColor(ThemeManager.getTextColorSecondary())
                } else {
                    tvRight?.setTextColor(ThemeManager.getTextColorPrimary())
                }
            }
            for (i in containerWifiItems.childCount - 1 downTo 1) {
                val v = containerWifiItems.getChildAt(i)
                if (v != btnScanWifi) {
                    containerWifiItems.removeViewAt(i)
                }
            }
        }

        if (!isOn) return

        if (results != null) {
            btnScanWifi.text = localize("Scan Complete (Retry)")
            foundWifiNetworks.clear()
            val wifiInfo = wm?.connectionInfo
            var connectedSSID = ""
            if (wifiInfo != null && wifiInfo.ssid != null) {
                connectedSSID = wifiInfo.ssid.replace("\"", "")
            }
            val networks = results.filterNot { WifiScanFilter.isDriverWarning(it.BSSID, it.SSID) }

            // 1. Current connected Wi-Fi at the top
            for (result in networks) {
                val ssid = result.SSID
                if (!ssid.isNullOrEmpty() && !foundWifiNetworks.contains(ssid)) {
                    if (ssid == connectedSSID) {
                        foundWifiNetworks.add(ssid)
                        addWifiItemToUI(ssid, result.capabilities, true)
                    }
                }
            }

            // 2. Remaining available Wi-Fi networks
            for (result in networks) {
                val ssid = result.SSID
                if (!ssid.isNullOrEmpty() && !foundWifiNetworks.contains(ssid)) {
                    foundWifiNetworks.add(ssid)
                    addWifiItemToUI(ssid, result.capabilities, false)
                }
            }
        }
    }

    private fun addWifiItemToUI(ssid: String, capabilities: String, isConnected: Boolean) {
        val isOpen = !capabilities.contains("WPA") && !capabilities.contains("WEP")
        val leftIcon = if (isConnected) TablerIcons.CHECK else TablerIcons.WIFI
        val rightIcon = if (isOpen) null else TablerIcons.LOCK

        // The check mark shows the connected network; the text keeps the theme colour (no green).
        val btnWifi = if (isConnected) {
            createTablerListButton(leftIcon, ssid, rightIcon, ThemeManager.getTextColorPrimary(), ThemeManager.getTextColorPrimary())
        } else {
            createTablerListButton(leftIcon, ssid, rightIcon, ThemeManager.getTextColorSecondary(), ThemeManager.getTextColorPrimary())
        }

        btnWifi.setOnClickListener {
            clickFeedback()
            if (isConnected) return@setOnClickListener

            val manager = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            var isSaved = false
            var savedNetId = -1
            try {
                val configuredNetworks = manager?.configuredNetworks
                if (configuredNetworks != null) {
                    for (conf in configuredNetworks) {
                        if (conf.SSID != null && conf.SSID == "\"$ssid\"") {
                            isSaved = true
                            savedNetId = conf.networkId
                            break
                        }
                    }
                }
            } catch (ignored: Exception) {}

            if (isSaved && savedNetId != -1) {
                Toast.makeText(activity, localize("Connecting to saved network..."), Toast.LENGTH_SHORT).show()
                manager?.disconnect()
                manager?.enableNetwork(savedNetId, true)
                manager?.reconnect()
                onConnectToSavedNetwork(ssid, savedNetId)
            } else {
                onOpenWifiKeyboard(ssid, isOpen)
            }
        }

        containerWifiItems.addView(btnWifi)
    }

    override fun onWheel(delta: Int, event: KeyEvent): Boolean {
        if (containerWifiItems.childCount == 0) return false
        var currentIndex = -1
        for (i in 0 until containerWifiItems.childCount) {
            if (containerWifiItems.getChildAt(i).hasFocus()) {
                currentIndex = i
                break
            }
        }
        if (delta < 0) {
            if (currentIndex > 0) {
                for (i in currentIndex - 1 downTo 0) {
                    val child = containerWifiItems.getChildAt(i)
                    if (child != null && child.visibility == View.VISIBLE && child.isFocusable) {
                        child.requestFocus()
                        clickFeedback()
                        return true
                    }
                }
            }
        } else {
            if (currentIndex in 0 until containerWifiItems.childCount - 1) {
                for (i in currentIndex + 1 until containerWifiItems.childCount) {
                    val child = containerWifiItems.getChildAt(i)
                    if (child != null && child.visibility == View.VISIBLE && child.isFocusable) {
                        child.requestFocus()
                        clickFeedback()
                        return true
                    }
                }
            }
        }
        if (currentIndex == -1 && containerWifiItems.childCount > 0) {
            for (i in 0 until containerWifiItems.childCount) {
                val child = containerWifiItems.getChildAt(i)
                if (child != null && child.visibility == View.VISIBLE && child.isFocusable) {
                    child.requestFocus()
                    clickFeedback()
                    return true
                }
            }
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == 21 || keyCode == 22 || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            val delta = if (keyCode == 22 || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
            return onWheel(delta, event)
        }
        return false
    }

    override fun onBackPressed(): Boolean {
        onNavigateBack()
        return true
    }

    companion object {
        private val WIFI_POWER_ROW_ID = R.id.wifi_power_row
    }
}
