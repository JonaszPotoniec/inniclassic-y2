package com.themoon.y1.controllers.connectivity

import android.app.Activity
import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.core.extensions.onOff
import com.themoon.y1.lists.ListScreen
import com.themoon.y1.lists.ListSpec
import com.themoon.y1.lists.RowLook
import com.themoon.y1.lists.RowSpec
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.views.ListItemViewFactory
import com.themoon.y1.views.Notice
import com.themoon.y1.views.RowGlyphs

/**
 * Controller for Wi-Fi scanning, power toggle, and AP selection.
 */
class WifiController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val clickFeedback: () -> Unit,
    private val onConnectToSavedNetwork: (ssid: String, netId: Int) -> Unit,
    private val onOpenWifiKeyboard: (ssid: String, isOpen: Boolean) -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    private val layoutWifiMode: LinearLayout = rootView.findViewById(R.id.layout_wifi_mode)
    private val btnScanWifi: Button = rootView.findViewById(R.id.btn_scan_wifi)
    private val containerWifiItems: LinearLayout = rootView.findViewById(R.id.container_wifi_items)

    private val screen = ListScreen(containerWifiItems, clickFeedback = clickFeedback)

    /** The results of the last scan. */
    private var scan: List<ScanResult> = emptyList()

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
        val wm = wifiManager()
        val isOn = wm != null && wm.isWifiEnabled
        btnScanWifi.text = localize(if (isOn) "Scanning..." else "Wi-Fi is off")
        // The networks of the last scan stay until the new results come: Back can select the network row again
        if (!isOn) scan = emptyList()
        screen.show(spec())
        if (isOn) wm?.startScan()
    }

    fun updateWifiUI(results: List<ScanResult>?) {
        if (results != null) {
            btnScanWifi.text = localize("Scan Complete (Retry)")
            scan = results
        }
        screen.update(spec())
    }

    private fun wifiManager(): WifiManager? =
        activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    /** Wi-Fi Power, the scan row, then the networks: the connected network first, each network once. */
    private fun spec(): ListSpec {
        val wm = wifiManager()
        val state = wm?.wifiState
        val isOn = state == WifiManager.WIFI_STATE_ENABLED
        val isChanging = state == WifiManager.WIFI_STATE_ENABLING || state == WifiManager.WIFI_STATE_DISABLING
        val rows = ArrayList<RowSpec>()
        rows += RowSpec(localize("Wi-Fi Power"), RowLook.VALUE, value = if (isChanging) localize("Wait...") else onOff(isOn, localize),
            id = WIFI_POWER_ROW_ID, key = POWER_KEY, onClick = { togglePower(wm) })
        rows += RowSpec(btnScanWifi.text.toString(), RowLook.CUSTOM, view = btnScanWifi, key = SCAN_KEY, onClick = { startWifiScan() })
        if (!isOn) return ListSpec(localize("Wi-Fi"), rows)
        val connected = wm?.connectionInfo?.ssid?.replace("\"", "").orEmpty()
        val networks = scan.filterNot { WifiScanFilter.isDriverWarning(it.BSSID, it.SSID) }
        val ssids = LinkedHashSet<String>()
        if (connected.isNotEmpty() && networks.any { it.SSID == connected }) ssids += connected
        for (n in networks) if (!n.SSID.isNullOrEmpty()) ssids += n.SSID
        for (ssid in ssids) {
            val result = networks.first { it.SSID == ssid }
            rows += networkRow(ssid, result.capabilities, ssid == connected, strongestRssi(scan, ssid))
        }
        return ListSpec(localize("Wi-Fi"), rows)
    }

    private fun togglePower(wm: WifiManager?) {
        wm ?: return
        val on = !wm.isWifiEnabled
        Notice.show(activity, localize(if (on) "Turning Wi-Fi on…" else "Turning Wi-Fi off…"))
        wm.isWifiEnabled = on
        screen.valueView(POWER_KEY)?.text = localize("Wait...")
    }

    // A network can have more than one access point (scan result): show the strongest signal.
    private fun strongestRssi(results: List<ScanResult>, ssid: String): Int =
        results.filter { it.SSID == ssid }.maxOf { it.level }

    private fun networkRow(ssid: String, capabilities: String, isConnected: Boolean, rssi: Int): RowSpec {
        val isOpen = !capabilities.contains("WPA") && !capabilities.contains("WEP")
        // Like macOS: the tick column on the left (tick on the connected network only), then the name,
        // then the lock (secured networks) and the signal strength on the right.
        return RowSpec(ssid, RowLook.CHOICE, checked = isConnected, glyphs = RowGlyphs.wifiSlots(!isOpen, WifiSignal.level(rssi)),
            onClick = { if (!isConnected) connect(ssid, isOpen) })
    }

    private fun connect(ssid: String, isOpen: Boolean) {
        val manager = wifiManager()
        val savedNetId = try {
            manager?.configuredNetworks?.firstOrNull { it.SSID == "\"$ssid\"" }?.networkId ?: -1
        } catch (ignored: Exception) {
            -1
        }
        if (savedNetId != -1) {
            Notice.show(activity, localize("Connecting to saved network..."))
            manager?.disconnect()
            manager?.enableNetwork(savedNetId, true)
            manager?.reconnect()
            onConnectToSavedNetwork(ssid, savedNetId)
        } else {
            onOpenWifiKeyboard(ssid, isOpen)
        }
    }

    override fun onWheel(delta: Int, event: KeyEvent): Boolean = screen.wheel(delta)

    override fun title(): String? = screen.title

    companion object {
        private val WIFI_POWER_ROW_ID = R.id.wifi_power_row
        private const val POWER_KEY = "power"
        private const val SCAN_KEY = "scan"
    }
}
