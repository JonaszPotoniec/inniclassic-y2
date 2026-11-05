package com.themoon.y1.controllers.connectivity

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.graphics.Path
import android.graphics.RectF
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import com.themoon.y1.managers.BluetoothDiscoverableLogic
import com.themoon.y1.managers.AudioOutputLossManager
import com.themoon.y1.managers.BluetoothDiscoverableManager
import com.themoon.y1.managers.BluetoothPairingAgent
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
import com.themoon.y1.views.TablerIcons
import java.lang.reflect.Method

/**
 * Controller for Bluetooth discovery, pairing, unpairing, and A2DP audio connections.
 */
class BluetoothController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val clickFeedback: () -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    private val layoutBluetoothMode: LinearLayout = rootView.findViewById(R.id.layout_bluetooth_mode)
    private val btnScanBt: Button = rootView.findViewById(R.id.btn_scan_bt)
    private val containerBtItems: LinearLayout = rootView.findViewById(R.id.container_bt_items)

    private val screen = ListScreen(containerBtItems, clickFeedback = clickFeedback)

    /** The devices that the scan found and that are not paired, by address. */
    private val found = LinkedHashMap<String, BluetoothDevice>()

    /** The paired devices that show their actions (Connect Audio, Forget Device). */
    private val expanded = HashSet<String>()
    private var lastBtToggleTime: Long = 0

    // Target device tracking
    var targetDeviceForAudio: BluetoothDevice? = null
        private set
    var lastManualDisconnectAddress: String? = null
        private set
    var lastManualDisconnectTime: Long = 0
        private set
    var isBtConnectingState = false

    /** Device that the Y2 asked to pair with (Connect on an available device); null after its bond. */
    private var bondRequestedAddress: String? = null

    /** True once for the device that the Y2 asked to pair with: then the Y2 connects its audio. */
    fun takeY2BondRequest(device: BluetoothDevice): Boolean {
        if (bondRequestedAddress != device.address) return false
        bondRequestedAddress = null
        return true
    }

    // Bluetooth proxy reference
    private var globalA2dp: BluetoothProfile? = null

    // Discoverable row: On/Off value (a switch with Toggle Style = Switch) and the time left on its left.
    // Both update each second while the screen shows.
    private var discoverableValue: TextView? = null
    private var discoverableTime: TextView? = null
    private var discoverableShown: Boolean? = null
    private val tickHandler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateDiscoverableValue()
            tickHandler.postDelayed(this, 1000)
        }
    }

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
        BluetoothDiscoverableManager.init(activity)
        AudioOutputLossManager.init(activity)
    }

    override fun onEnter(state: ScreenState) {
        layoutBluetoothMode.visibility = View.VISIBLE
        updateScanButtonFocus(btnScanBt.hasFocus())
        expanded.clear()
        startBluetoothScan(opens = true)
        BluetoothDiscoverableManager.listener = { updateDiscoverableValue() }
        tickHandler.removeCallbacks(tick)
        tickHandler.post(tick)
    }

    override fun onExit() {
        layoutBluetoothMode.visibility = View.GONE
        cancelDiscovery()
        tickHandler.removeCallbacks(tick)
        BluetoothDiscoverableManager.listener = null
        screen.clear()
    }

    override fun onRefreshUI() {
        updateScanButtonFocus(btnScanBt.hasFocus())
        startBluetoothScan()
    }

    override fun onWheel(delta: Int, event: KeyEvent): Boolean = screen.wheel(delta)

    override fun title(): String? = screen.title

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

    /**
     * Make the device list again (the selected row stays) and start a device scan. [opens]: the screen opens, so
     * its first row is selected. A scan that the user did not ask for does not start while a pairing runs or a
     * car connects after a bond (see [BluetoothPairingAgent.scanAllowed]).
     */
    @SuppressLint("MissingPermission")
    fun startBluetoothScan(userAction: Boolean = false, opens: Boolean = false) {
        val ba = BluetoothAdapter.getDefaultAdapter()
        val isOn = ba != null && ba.state == BluetoothAdapter.STATE_ON
        found.clear()
        val scan = isOn && (userAction || BluetoothPairingAgent.scanAllowed())
        btnScanBt.text = localize(if (!isOn) "Bluetooth is off" else if (scan) "Scanning..." else "Scan Complete (Retry)")
        render(opens)
        if (!isOn || ba == null) return
        if (!scan) {
            if (ba.isDiscovering) ba.cancelDiscovery()
        } else if (ba.isDiscovering) {
            ba.cancelDiscovery()
            Handler(Looper.getMainLooper()).postDelayed({
                if (userAction || BluetoothPairingAgent.scanAllowed()) ba.startDiscovery()
            }, 500)
        } else {
            ba.startDiscovery()
        }
    }

    /**
     * Bluetooth Power, Discoverable, the scan row (above the device lists, as in the Wi-Fi screen: easy to reach in
     * a long list), the paired devices (each with its actions under it when it is open), the available devices.
     */
    @SuppressLint("MissingPermission")
    private fun spec(): ListSpec {
        val ba = BluetoothAdapter.getDefaultAdapter()
        val state = ba?.state
        val isOn = state == BluetoothAdapter.STATE_ON
        val isChanging = state == BluetoothAdapter.STATE_TURNING_ON || state == BluetoothAdapter.STATE_TURNING_OFF
        val rows = ArrayList<RowSpec>()
        rows += RowSpec(localize("Bluetooth Power"), RowLook.VALUE, value = if (isChanging) localize("Wait...") else onOff(isOn, localize),
            key = POWER_KEY, onClick = { togglePower(ba) })
        val scanRow = RowSpec(btnScanBt.text.toString(), RowLook.CUSTOM, view = btnScanBt, key = SCAN_KEY,
            onClick = { startBluetoothScan(userAction = true) })
        if (!isOn || ba == null) return ListSpec(localize("Bluetooth"), rows + scanRow)

        rows += RowSpec(localize("Discoverable"), RowLook.CUSTOM, view = discoverableRow(), key = DISCOVERABLE_KEY, onClick = {
            val on = !BluetoothDiscoverableManager.isDiscoverable()
            if (!BluetoothDiscoverableManager.setDiscoverable(on)) {
                Notice.error(activity, localize("Cannot change Discoverable mode."))
            }
            updateDiscoverableValue()
        })
        rows += scanRow
        rows += RowSpec(localize("PAIRED DEVICES"), RowLook.HEADER, key = "paired")
        try {
            val paired = ba.bondedDevices
            if (paired.isNullOrEmpty()) rows += RowSpec(localize("No paired devices."), RowLook.INFO)
            else for (device in paired) rows += pairedRows(device)
        } catch (ignored: Exception) {}
        rows += RowSpec(localize("AVAILABLE DEVICES"), RowLook.HEADER, key = "available")
        for (device in found.values) {
            val name = if (!device.name.isNullOrBlank()) device.name else "Unknown (${device.address})"
            rows += RowSpec(name, RowLook.CHOICE, glyphs = deviceGlyphs(device), key = device.address,
                onClick = { connectBluetoothAudio(device) })
        }
        return ListSpec(localize("Bluetooth"), rows)
    }

    /** Shows the rows of [spec]: [opens] = the screen opens (first row), else the selected row stays. */
    private fun render(opens: Boolean = false) {
        if (opens) screen.show(spec()) else screen.update(spec())
        updateDiscoverableValue()
    }

    @SuppressLint("MissingPermission")
    private fun togglePower(ba: BluetoothAdapter?) {
        val now = System.currentTimeMillis()
        if (now - lastBtToggleTime < 1500) {
            Notice.show(activity, localize("Please wait a moment..."))
            return
        }
        lastBtToggleTime = now
        ba ?: return
        screen.valueView(POWER_KEY)?.text = localize("Wait...")
        if (ba.isEnabled) {
            targetDeviceForAudio = null
            ba.disable()
        } else {
            Thread {
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "setprop ctl.start mtkbt")).waitFor()
                    Thread.sleep(1500)
                } catch (ignored: Exception) {}
                activity.runOnUiThread {
                    ba.enable()
                }
            }.start()
        }
    }

    /** Discoverable row (On for a limited time, so a car can find the Y2): the time left on the left of the value. */
    private fun discoverableRow(): View {
        val discoverable = BluetoothDiscoverableManager.isDiscoverable()
        val row = ListItemViewFactory.createSettingRow(activity, localize("Discoverable"), onOff(discoverable, localize))
        val value = row.getChildAt(1) as? TextView
        // The time left goes on the left of the value (of the switch), in the same text style.
        val time = TextView(activity).apply {
            if (value != null) {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, value.textSize)
                typeface = value.typeface
                setTextColor(value.currentTextColor)
            }
            includeFontPadding = false
            val padRight = (8 * activity.resources.displayMetrics.density).toInt()
            val rowHeight = row.layoutParams?.height ?: 0
            if (rowHeight > 0) {
                // Put the centre of the digits on the centre line of the row, like the row title and the switch,
                // in all themes: full row height, gravity TOP, and a top padding from the font (cap height of "H").
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
                gravity = Gravity.TOP or Gravity.END
                val path = Path()
                paint.getTextPath("H", 0, 1, 0f, 0f, path)
                val bounds = RectF()
                path.computeBounds(bounds, true)
                val top = BluetoothDiscoverableLogic.capCentredPaddingTop(rowHeight, paint.fontMetricsInt.ascent, -bounds.top)
                setPadding(0, top, padRight, 0)
            } else {
                setPadding(0, 0, padRight, 0)
            }
            visibility = View.GONE
        }
        row.addView(time, 1)
        // The row changes the value colour when it gets or loses the focus: give the same colour to the time.
        val rowFocusListener = row.onFocusChangeListener
        row.setOnFocusChangeListener { v, hasFocus ->
            rowFocusListener?.onFocusChange(v, hasFocus)
            if (value != null) time.setTextColor(value.currentTextColor)
        }
        discoverableValue = value
        discoverableTime = time
        discoverableShown = discoverable
        return row
    }

    private fun updateDiscoverableValue() {
        val value = discoverableValue ?: return
        val time = discoverableTime ?: return
        if (value.parent?.parent != containerBtItems) return
        val discoverable = BluetoothDiscoverableManager.isDiscoverable()
        if (discoverable != discoverableShown) {
            value.text = onOff(discoverable, localize)
            discoverableShown = discoverable
        }
        val left = BluetoothDiscoverableLogic.timeLeftText(discoverable, BluetoothDiscoverableManager.remainingMs())
        time.visibility = if (left == null) View.GONE else View.VISIBLE
        if (left != null && time.text.toString() != left) time.text = left
    }

    /**
     * A paired device (as in the Wi-Fi list: the tick column on the left, with the tick on the connected device only,
     * the name, then the device type on the right), and under it its actions, which show when the device is open.
     */
    @SuppressLint("MissingPermission")
    private fun pairedRows(device: BluetoothDevice): List<RowSpec> {
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

        val address = device.address
        val open = address in expanded
        val connect = actionRow(if (isConnected) TablerIcons.UNLINK else TablerIcons.HEADPHONES,
            localize(if (isConnected) "Disconnect Audio" else "Connect Audio"), 0, open)
        val forget = actionRow(TablerIcons.TRASH, localize("Forget Device"), 0xFFFF5555.toInt(), open)

        return listOf(
            RowSpec(name, RowLook.CHOICE, checked = isConnected, glyphs = deviceGlyphs(device), key = address, onClick = {
                val opens = connect.visibility == View.GONE
                if (opens) expanded += address else expanded -= address
                connect.visibility = if (opens) View.VISIBLE else View.GONE
                forget.visibility = connect.visibility
                if (opens) connect.post { connect.requestFocus() }
            }),
            RowSpec(localize("Connect Audio"), RowLook.CUSTOM, view = connect, key = "connect:$address", onClick = {
                if (isConnected) {
                    screen.select(address)
                    disconnectBluetoothAudio(device)
                } else {
                    connectBluetoothAudio(device)
                }
            }),
            RowSpec(localize("Forget Device"), RowLook.CUSTOM, view = forget, key = "forget:$address", onClick = {
                try {
                    if (isConnected) {
                        disconnectBluetoothAudio(device)
                    }
                    val result = device.javaClass.getMethod("removeBond").invoke(device)
                    val removed = (result as? Boolean) == true
                    if (removed) {
                        Notice.show(activity, localize("Removing device..."))
                    } else {
                        Notice.error(activity, localize("Failed to remove device. Try restarting Bluetooth."))
                    }
                } catch (e: Exception) {
                    Notice.error(activity, localize("Error: ") + e.message)
                }
            })
        )
    }

    /** An action under a paired device: indented, with an icon; it shows when the device is open. */
    private fun actionRow(icon: Array<String>, text: String, color: Int, shows: Boolean): View =
        ListItemViewFactory.createTablerListButtonWithRightIcon(activity, icon, text, null, color, color).apply {
            (layoutParams as? ViewGroup.MarginLayoutParams)?.leftMargin = (32 * activity.resources.displayMetrics.density).toInt()
            visibility = if (shows) View.VISIBLE else View.GONE
        }

    private fun deviceGlyphs(device: BluetoothDevice): List<RowGlyphs.Slot> {
        var kind = RowGlyphs.Kind.BLUETOOTH
        try {
            val btClass = device.bluetoothClass
            if (btClass != null && (btClass.doesClassMatch(BluetoothClass.PROFILE_A2DP)
                        || btClass.doesClassMatch(BluetoothClass.PROFILE_HEADSET))) {
                kind = RowGlyphs.Kind.HEADPHONES
            }
        } catch (ignored: Exception) {}
        return RowGlyphs.deviceSlots(kind)
    }

    /** A device that the scan found and that is not paired: one more row in the available devices. */
    fun onDeviceDiscovered(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED || found.containsKey(device.address)) return
        found[device.address] = device
        render()
    }

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
            Notice.show(activity, localize("Pairing with ") + targetDevice.name + "...")
            bondRequestedAddress = targetDevice.address
            try {
                targetDevice.javaClass.getMethod("createBond").invoke(targetDevice)
            } catch (ignored: Exception) {}
            return
        }

        Notice.show(activity, localize("Connecting Audio..."))

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
                Notice.error(activity, localize("Audio connection error: connect method not found"))
                return
            }

            connectMethod.isAccessible = true
            val result = connectMethod.invoke(a2dp, targetDevice) as? Boolean ?: false
            if (!result) {
                Notice.error(activity, localize("Audio connection rejected by system."))
            } else {
                Notice.show(activity, localize("Audio connection initiated."))
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
            Notice.error(activity, localize("Audio error: ") + errorStr)
        }
    }

    fun disconnectBluetoothAudio(targetDevice: BluetoothDevice?) {
        if (targetDevice == null) return
        targetDeviceForAudio = null
        lastManualDisconnectAddress = targetDevice.address
        lastManualDisconnectTime = System.currentTimeMillis()
        Notice.show(activity, localize("Disconnecting Audio..."))

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

    companion object {
        private const val POWER_KEY = "power"
        private const val DISCOVERABLE_KEY = "discoverable"
        private const val SCAN_KEY = "scan"
    }
}
