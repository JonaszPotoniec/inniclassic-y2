package com.themoon.y1.controllers.connectivity

import com.themoon.y1.navigation.RowFocus
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import com.themoon.y1.core.extensions.onOff
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.views.ListItemViewFactory
import com.themoon.y1.views.RowGlyphs
import com.themoon.y1.views.TablerIcons
import java.lang.reflect.Method

/**
 * Controller for Bluetooth discovery, pairing, unpairing, and A2DP audio connections.
 */
class BluetoothController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val createSettingRow: (title: String, value: String) -> LinearLayout,
    private val createTablerListButton: (iconPaths: Array<String>?, text: String, rightIconPaths: Array<String>?, iconColor: Int, textColor: Int) -> View,
    private val clickFeedback: () -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    private val layoutBluetoothMode: LinearLayout = rootView.findViewById(R.id.layout_bluetooth_mode)
    private val btnScanBt: Button = rootView.findViewById(R.id.btn_scan_bt)
    private val containerBtItems: LinearLayout = rootView.findViewById(R.id.container_bt_items)

    private val foundBtDevices = mutableSetOf<String>()
    private var lastBtToggleTime: Long = 0

    // Target device tracking
    var targetDeviceForAudio: BluetoothDevice? = null
        private set
    var lastManualDisconnectAddress: String? = null
        private set
    var lastManualDisconnectTime: Long = 0
        private set
    var isBtConnectingState = false

    // Bluetooth proxy reference
    private var globalA2dp: BluetoothProfile? = null

    private fun updateScanButtonFocus(hasFocus: Boolean) {
        if (hasFocus) {
            btnScanBt.background = ListItemViewFactory.createFocusedButtonBackground(activity)
            btnScanBt.setTextColor(ThemeManager.getListButtonFocusedTextColor())
        } else {
            btnScanBt.background = ListItemViewFactory.createButtonBackground(activity, ThemeManager.getListButtonNormalBg())
            btnScanBt.setTextColor(ThemeManager.getTextColorPrimary())
        }
        btnScanBt.typeface = ThemeManager.getCustomFontBold()
    }

    init {
        btnScanBt.isSoundEffectsEnabled = false
        updateScanButtonFocus(btnScanBt.hasFocus())
        btnScanBt.setOnFocusChangeListener { _, hasFocus ->
            updateScanButtonFocus(hasFocus)
        }
        btnScanBt.setOnClickListener {
            clickFeedback()
            startBluetoothScan()
        }
    }

    override fun onEnter(state: ScreenState) {
        layoutBluetoothMode.visibility = View.VISIBLE
        updateScanButtonFocus(btnScanBt.hasFocus())
        startBluetoothScan()
    }

    override fun onExit() {
        layoutBluetoothMode.visibility = View.GONE
        cancelDiscovery()
    }

    override fun onRefreshUI() {
        updateScanButtonFocus(btnScanBt.hasFocus())
        startBluetoothScan()
    }

    fun setGlobalA2dpProxy(proxy: BluetoothProfile?) {
        globalA2dp = proxy
    }

    fun getGlobalA2dpProxy(): BluetoothProfile? = globalA2dp

    @SuppressLint("MissingPermission")
    fun cancelDiscovery() {
        try {
            val ba = BluetoothAdapter.getDefaultAdapter()
            if (ba != null && ba.isDiscovering) {
                ba.cancelDiscovery()
            }
        } catch (ignored: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun startBluetoothScan() {
        var currentFocusIndex = 0
        for (i in 0 until containerBtItems.childCount) {
            if (containerBtItems.getChildAt(i).hasFocus()) {
                currentFocusIndex = i
                break
            }
        }
        val targetFocusIndex = currentFocusIndex

        val ba = BluetoothAdapter.getDefaultAdapter()
        var isOn = false
        var isChanging = false

        if (ba != null) {
            val state = ba.state
            if (state == BluetoothAdapter.STATE_ON) {
                isOn = true
            } else if (state == BluetoothAdapter.STATE_TURNING_ON || state == BluetoothAdapter.STATE_TURNING_OFF) {
                isChanging = true
            }
        }
        val statusText = if (isChanging) localize("Wait...") else onOff(isOn, localize)

        containerBtItems.removeAllViews()

        // Power toggle button
        val btnToggle = createSettingRow(localize("Bluetooth Power"), statusText)
        btnToggle.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastBtToggleTime < 1500) {
                Toast.makeText(activity, localize("Please wait a moment..."), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lastBtToggleTime = now
            clickFeedback()

            if (ba != null) {
                if (ba.isEnabled) {
                    targetDeviceForAudio = null
                    ba.disable()
                } else {
                    (btnToggle.getChildAt(1) as? TextView)?.text = localize("Wait...")
                    Thread {
                        try {
                            Runtime.getRuntime().exec(arrayOf("su", "-c", "setprop ctl.start mtkbt")).waitFor()
                            Thread.sleep(1500)
                        } catch (ignored: Exception) {}
                        activity.runOnUiThread {
                            ba.enable()
                        }
                    }.start()
                    return@setOnClickListener
                }
                (btnToggle.getChildAt(1) as? TextView)?.text = localize("Wait...")
            }
        }
        containerBtItems.addView(btnToggle)

        if (!isOn) {
            btnScanBt.text = localize("Bluetooth is off")
            (btnScanBt.parent as? ViewGroup)?.removeView(btnScanBt)
            containerBtItems.addView(btnScanBt)
            restoreBluetoothFocus(targetFocusIndex)
            return
        }

        // Paired devices header
        val tvPaired = TextView(activity).apply {
            text = "━ " + localize("PAIRED DEVICES") + " ━"
            setTextColor(ThemeManager.getTextColorSecondary())
            textSize = 14f
            typeface = ThemeManager.getCustomFontBold()
            setPadding(10, 30, 10, 5)
        }
        containerBtItems.addView(tvPaired)

        try {
            val pairedDevices = ba.bondedDevices
            if (pairedDevices != null && pairedDevices.isNotEmpty()) {
                for (device in pairedDevices) {
                    addPairedBluetoothItemToUI(device)
                }
            } else {
                val tvEmpty = TextView(activity).apply {
                    text = localize("No paired devices.")
                    setTextColor(ThemeManager.getTextColorSecondary())
                    setPadding(10, 10, 10, 10)
                }
                containerBtItems.addView(tvEmpty)
            }
        } catch (ignored: Exception) {}

        // Available devices header
        val tvAvailable = TextView(activity).apply {
            text = "━ " + localize("AVAILABLE DEVICES") + " ━"
            setTextColor(ThemeManager.getTextColorSecondary())
            textSize = 14f
            typeface = ThemeManager.getCustomFontBold()
            setPadding(10, 30, 10, 5)
        }
        containerBtItems.addView(tvAvailable)

        btnScanBt.text = localize("Scanning...")
        foundBtDevices.clear()

        (btnScanBt.parent as? ViewGroup)?.removeView(btnScanBt)
        containerBtItems.addView(btnScanBt)

        if (ba.isDiscovering) {
            ba.cancelDiscovery()
            Handler(Looper.getMainLooper()).postDelayed({ ba.startDiscovery() }, 500)
        } else {
            ba.startDiscovery()
        }

        restoreBluetoothFocus(targetFocusIndex)
    }

    @SuppressLint("MissingPermission")
    private fun addPairedBluetoothItemToUI(device: BluetoothDevice) {
        val name = if (!device.name.isNullOrEmpty()) device.name else "Unknown (${device.address})"

        var proxyState: Int? = null
        if (globalA2dp != null) {
            try {
                proxyState = globalA2dp!!.javaClass.getMethod("getConnectionState", BluetoothDevice::class.java)
                    .invoke(globalA2dp, device) as Int
            } catch (ignored: Exception) {}
        }
        // Also the devices from the A2DP query at start and the broadcasts (the status bar uses the same state):
        // after an app restart globalA2dp is null until Bluetooth is turned on or a device is connected here.
        val isConnected = com.themoon.y1.managers.BluetoothAudioState.shared.listShowsConnected(device.address, proxyState)

        var devIcon = TablerIcons.BLUETOOTH
        try {
            val btClass = device.bluetoothClass
            if (btClass != null && (btClass.doesClassMatch(BluetoothClass.PROFILE_A2DP)
                        || btClass.doesClassMatch(BluetoothClass.PROFILE_HEADSET))) {
                devIcon = TablerIcons.HEADPHONES
            }
        } catch (ignored: Exception) {}

        // As in the Wi-Fi list: the tick column on the left (tick on the connected device only), the name,
        // then the device type on the right.
        val btnDevice = createDeviceRow(name, devIcon, isConnected)

        val isConnectedDevice = isConnected
        val btnConnect = if (isConnectedDevice) {
            createTablerListButton(TablerIcons.UNLINK, localize("Disconnect Audio"), null, 0, 0).apply {
                setOnClickListener {
                    clickFeedback()
                    btnDevice.requestFocus()
                    disconnectBluetoothAudio(device)
                }
            }
        } else {
            createTablerListButton(TablerIcons.HEADPHONES, localize("Connect Audio"), null, 0, 0).apply {
                setOnClickListener {
                    clickFeedback()
                    connectBluetoothAudio(device)
                }
            }
        }
        btnConnect.visibility = View.GONE

        val btnUnpair = createTablerListButton(TablerIcons.TRASH, localize("Delete Device"), null, 0xFFFF5555.toInt(), 0xFFFF5555.toInt()).apply {
            visibility = View.GONE
            setOnClickListener {
                clickFeedback()
                try {
                    if (isConnectedDevice) {
                        disconnectBluetoothAudio(device)
                    }
                    val result = device.javaClass.getMethod("removeBond").invoke(device)
                    val removed = (result as? Boolean) == true
                    if (removed) {
                        Toast.makeText(activity, localize("Removing device..."), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(activity, localize("Failed to remove device. Try restarting Bluetooth."), Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(activity, localize("Error: ") + e.message, Toast.LENGTH_LONG).show()
                }
            }
        }

        btnDevice.setOnClickListener {
            clickFeedback()
            if (btnConnect.visibility == View.GONE) {
                btnConnect.visibility = View.VISIBLE
                btnUnpair.visibility = View.VISIBLE
                btnConnect.post { btnConnect.requestFocus() }
            } else {
                btnConnect.visibility = View.GONE
                btnUnpair.visibility = View.GONE
            }
        }

        containerBtItems.addView(btnDevice)

        val d = activity.resources.displayMetrics.density
        val indentPx = (32 * d).toInt()
        (btnConnect.layoutParams as? ViewGroup.MarginLayoutParams)?.leftMargin = indentPx
        (btnUnpair.layoutParams as? ViewGroup.MarginLayoutParams)?.leftMargin = indentPx
        containerBtItems.addView(btnConnect)
        containerBtItems.addView(btnUnpair)
    }

    @SuppressLint("MissingPermission")
    fun onDeviceDiscovered(device: BluetoothDevice) {
        val deviceAddress = device.address
        if (foundBtDevices.contains(deviceAddress)) return
        foundBtDevices.add(deviceAddress)

        if (device.bondState == BluetoothDevice.BOND_BONDED) return

        val deviceName = device.name
        val displayName = if (!deviceName.isNullOrBlank()) deviceName else "Unknown ($deviceAddress)"

        var devIcon = TablerIcons.BLUETOOTH
        try {
            val btClass = device.bluetoothClass
            if (btClass != null && (btClass.doesClassMatch(BluetoothClass.PROFILE_A2DP)
                        || btClass.doesClassMatch(BluetoothClass.PROFILE_HEADSET))) {
                devIcon = TablerIcons.HEADPHONES
            }
        } catch (ignored: Exception) {}

        val btnDevice = createDeviceRow(displayName, devIcon, false)
        btnDevice.setOnClickListener {
            clickFeedback()
            connectBluetoothAudio(device)
        }

        val insertIndex = Math.max(0, containerBtItems.childCount - 1)
        containerBtItems.addView(btnDevice, insertIndex)
    }

    private fun createDeviceRow(name: String, devIcon: Array<String>, isConnected: Boolean): View =
        ListItemViewFactory.createChoiceRowWithRightIcons(activity, name, isConnected, RowGlyphs.deviceSlots(
            if (devIcon === TablerIcons.HEADPHONES) RowGlyphs.Kind.HEADPHONES else RowGlyphs.Kind.BLUETOOTH))

    fun onDiscoveryFinished() {
        btnScanBt.text = localize("Scan Complete (Retry)")
    }

    @SuppressLint("MissingPermission")
    fun connectBluetoothAudio(targetDevice: BluetoothDevice?) {
        if (targetDevice == null) return
        lastManualDisconnectAddress = null
        lastManualDisconnectTime = 0
        targetDeviceForAudio = targetDevice

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter != null && adapter.isDiscovering) {
            adapter.cancelDiscovery()
        }

        if (targetDevice.bondState != BluetoothDevice.BOND_BONDED) {
            Toast.makeText(activity, localize("Pairing with ") + targetDevice.name + "...", Toast.LENGTH_SHORT).show()
            try {
                targetDevice.javaClass.getMethod("createBond").invoke(targetDevice)
            } catch (ignored: Exception) {}
            return
        }

        Toast.makeText(activity, localize("Connecting Audio..."), Toast.LENGTH_SHORT).show()

        if (globalA2dp != null) {
            executeA2dpConnect(targetDevice)
        } else {
            adapter?.getProfileProxy(activity, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.A2DP) {
                        globalA2dp = proxy
                        activity.runOnUiThread {
                            executeA2dpConnect(targetDevice)
                        }
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.A2DP) {
                        globalA2dp = null
                    }
                }
            }, BluetoothProfile.A2DP)
        }
    }

    fun executeA2dpConnect(targetDevice: BluetoothDevice) {
        val a2dp = globalA2dp ?: return
        lastManualDisconnectAddress = null
        lastManualDisconnectTime = 0
        try {
            try {
                val setPriorityMethod = a2dp.javaClass.getMethod("setPriority", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                setPriorityMethod.invoke(a2dp, targetDevice, 100) // PRIORITY_ON
            } catch (ignored: Exception) {}

            var connectedDevices: List<BluetoothDevice>? = null
            try {
                connectedDevices = a2dp.connectedDevices
            } catch (e: Exception) {
                e.printStackTrace()
            }

            connectedDevices?.forEach { connected ->
                if (connected.address != targetDevice.address) {
                    try {
                        val disconnectMethod = a2dp.javaClass.getDeclaredMethod("disconnect", BluetoothDevice::class.java)
                        disconnectMethod.isAccessible = true
                        disconnectMethod.invoke(a2dp, connected)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            var connectMethod: Method? = null
            try {
                connectMethod = a2dp.javaClass.getDeclaredMethod("connect", BluetoothDevice::class.java)
            } catch (e: NoSuchMethodException) {
                for (m in a2dp.javaClass.methods) {
                    if (m.name == "connect" && m.parameterTypes.size == 1) {
                        connectMethod = m
                        break
                    }
                }
            }

            if (connectMethod == null) {
                Toast.makeText(activity, localize("Audio connection error: connect method not found"), Toast.LENGTH_LONG).show()
                return
            }

            connectMethod.isAccessible = true
            val result = connectMethod.invoke(a2dp, targetDevice) as? Boolean ?: false
            if (!result) {
                Toast.makeText(activity, localize("Audio connection rejected by system."), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, localize("Audio connection initiated."), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            var errorStr = e.javaClass.simpleName
            if (e is java.lang.reflect.InvocationTargetException) {
                val cause = e.cause
                if (cause != null) {
                    errorStr += " (Cause: " + cause.javaClass.simpleName + " - " + cause.message + ")"
                }
            } else {
                errorStr += " - " + e.message
            }
            Toast.makeText(activity, localize("Audio error: ") + errorStr, Toast.LENGTH_LONG).show()
        }
    }

    fun disconnectBluetoothAudio(targetDevice: BluetoothDevice?) {
        if (targetDevice == null) return
        targetDeviceForAudio = null
        lastManualDisconnectAddress = targetDevice.address
        lastManualDisconnectTime = System.currentTimeMillis()
        Toast.makeText(activity, localize("Disconnecting Audio..."), Toast.LENGTH_SHORT).show()

        if (globalA2dp != null) {
            executeA2dpDisconnect(targetDevice)
        } else {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            adapter?.getProfileProxy(activity, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.A2DP) {
                        globalA2dp = proxy
                        activity.runOnUiThread {
                            executeA2dpDisconnect(targetDevice)
                        }
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.A2DP) {
                        globalA2dp = null
                    }
                }
            }, BluetoothProfile.A2DP)
        }
    }

    fun executeA2dpDisconnect(targetDevice: BluetoothDevice) {
        val a2dp = globalA2dp ?: return
        try {
            try {
                val setPriorityMethod = a2dp.javaClass.getMethod("setPriority", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                setPriorityMethod.invoke(a2dp, targetDevice, 0) // PRIORITY_OFF
            } catch (ignored: Exception) {}

            var disconnectMethod: Method? = null
            try {
                disconnectMethod = a2dp.javaClass.getDeclaredMethod("disconnect", BluetoothDevice::class.java)
            } catch (e: NoSuchMethodException) {
                for (m in a2dp.javaClass.methods) {
                    if (m.name == "disconnect" && m.parameterTypes.size == 1) {
                        disconnectMethod = m
                        break
                    }
                }
            }

            if (disconnectMethod != null) {
                disconnectMethod.isAccessible = true
                disconnectMethod.invoke(a2dp, targetDevice)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        Handler(Looper.getMainLooper()).postDelayed({
            startBluetoothScan()
        }, 500)
    }

    fun clearTargetDeviceForAudio() {
        targetDeviceForAudio = null
    }

    fun clearLastManualDisconnectAddress() {
        lastManualDisconnectAddress = null
    }

    private fun restoreBluetoothFocus(targetFocusIndex: Int) {
        containerBtItems.postDelayed({
            if (containerBtItems.childCount > 0) {
                if (targetFocusIndex in 0 until containerBtItems.childCount) {
                    val target = containerBtItems.getChildAt(targetFocusIndex)
                    if (target.isFocusable && target.visibility == View.VISIBLE) {
                        target.requestFocus()
                        return@postDelayed
                    }
                }
                for (i in 0 until containerBtItems.childCount) {
                    val v = containerBtItems.getChildAt(i)
                    if (v.isFocusable && v.visibility == View.VISIBLE) {
                        v.requestFocus()
                        return@postDelayed
                    }
                }
            }
        }, 50)
    }

    override fun onWheel(delta: Int, event: KeyEvent): Boolean {
        // The scan button is above the list: down from it selects the first row, up from the first row selects it
        val scanAbove = btnScanBt.visibility == View.VISIBLE
        if (scanAbove && activity.currentFocus == btnScanBt && delta < 0) return false
        val toScan = scanAbove && delta < 0 && containerBtItems.hasFocus()
        if (RowFocus.step(containerBtItems, delta, !toScan && RowFocus.isLoopOn(activity)) == null) {
            if (!toScan) return false
            btnScanBt.requestFocus()
        }
        clickFeedback()
        return true
    }
}
