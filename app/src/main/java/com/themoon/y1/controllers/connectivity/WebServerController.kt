package com.themoon.y1.controllers.connectivity

import android.app.Activity
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.themoon.y1.R
import com.themoon.y1.StoragePaths
import com.themoon.y1.ThemeManager
import com.themoon.y1.Y1WebServer
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.navigation.ScreenState

/**
 * Controller for the PC Upload HTTP Web Server screen:
 * Toggles HTTP server, shows local Wi-Fi IP address on port 8080,
 * manages WakeLocks and WifiLocks, and triggers library scan on server shutdown.
 */
class WebServerController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val onServerStateChanged: (isRunning: Boolean) -> Unit,
    private val onScanMediaRequested: () -> Unit,
    private val clickFeedback: () -> Unit,
    private val onNavigateBack: () -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    private val layoutWebServer: LinearLayout = rootView.findViewById(R.id.layout_webserver_mode)
    private val tvServerStatus: TextView? = rootView.findViewById(R.id.tv_server_status)
    private val tvServerIp: TextView? = rootView.findViewById(R.id.tv_server_ip)
    private val btnServerToggle: Button? = rootView.findViewById(R.id.btn_server_toggle)

    var isServerRunning = false
        private set

    private var webServer: Y1WebServer? = null
    private var serverWakeLock: PowerManager.WakeLock? = null
    private var serverWifiLock: WifiManager.WifiLock? = null

    override fun onEnter(state: ScreenState) {
        layoutWebServer.visibility = View.VISIBLE
        updateUI()
        btnServerToggle?.requestFocus()
    }

    override fun onExit() {
        layoutWebServer.visibility = View.GONE
    }

    override fun onThemeChanged() {
        updateUI()
    }

    fun toggleServer() {
        if (isServerRunning) {
            webServer?.stopServer()
            webServer = null
            isServerRunning = false

            try {
                if (serverWakeLock?.isHeld == true) {
                    serverWakeLock?.release()
                }
                serverWakeLock = null

                if (serverWifiLock?.isHeld == true) {
                    serverWifiLock?.release()
                }
                serverWifiLock = null

                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } catch (ignored: Exception) {}

            onScanMediaRequested()
            onServerStateChanged(false)
        } else {
            val wm = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm == null || !wm.isWifiEnabled) {
                Toast.makeText(activity, localize("Turn on Wi-Fi first"), Toast.LENGTH_SHORT).show()
                return
            }

            webServer = Y1WebServer(activity.applicationContext, StoragePaths.getWebServerRoot())
            try {
                webServer?.start()
                isServerRunning = true

                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                val pm = activity.getSystemService(Context.POWER_SERVICE) as? PowerManager
                serverWakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Y1:WebServerLock")
                serverWakeLock?.acquire()

                serverWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "Y1:WebServerWifiLock")
                serverWifiLock?.acquire()

                onServerStateChanged(true)
            } catch (e: Exception) {
                Toast.makeText(activity, "${localize("Server failed:")} ${e.message}", Toast.LENGTH_SHORT).show()
                isServerRunning = false
            }
        }
        updateUI()
    }

    fun updateUI() {
        if (isServerRunning) {
            tvServerStatus?.text = localize("SERVER RUNNING")
            tvServerStatus?.setTextColor(ThemeManager.getTextColorPrimary())
            val ip = webServer?.getLocalIpAddress() ?: "127.0.0.1"
            tvServerIp?.text = "http://$ip:8080"
            tvServerIp?.setTextColor(ThemeManager.getTextColorPrimary())
            btnServerToggle?.text = localize("STOP SERVER")
        } else {
            tvServerStatus?.text = localize("SERVER STOPPED")
            tvServerStatus?.setTextColor(ThemeManager.getTextColorSecondary())
            tvServerIp?.text = "http://---.---.---.---:8080"
            tvServerIp?.setTextColor(ThemeManager.getTextColorSecondary())
            btnServerToggle?.text = localize("START SERVER")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            clickFeedback()
            toggleServer()
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            clickFeedback()
            onNavigateBack()
            return true
        }

        return false
    }

    override fun onBackPressed(): Boolean {
        onNavigateBack()
        return true
    }
}
