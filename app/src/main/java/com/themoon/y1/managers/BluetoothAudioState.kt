package com.themoon.y1.managers

/**
 * The Bluetooth audio (A2DP) devices that are connected, for the status bar icon and the Bluetooth list.
 * With one or more connected devices the status icon is the rune with two dots ([isConnected]), like the Android
 * icon, and the list shows a tick on each connected device ([listShowsConnected]).
 *
 * MainActivity gives it the A2DP connection broadcasts and, at start and when Bluetooth comes on, the list of
 * connected devices from the A2DP proxy. So it is correct also after an app restart, when the app did not make the
 * connection itself. Each method returns true when [isConnected] changed, so the caller draws the icon again only
 * then. No Android dependencies, for the unit tests. One instance: [shared].
 */
class BluetoothAudioState {
    private val connected = LinkedHashSet<String>()

    var adapterOn = false
        private set

    val isConnected: Boolean get() = adapterOn && connected.isNotEmpty()

    /** The addresses of the connected devices (a copy). */
    val connectedAddresses: Set<String> get() = if (adapterOn) LinkedHashSet(connected) else emptySet()

    fun isDeviceConnected(address: String?): Boolean = adapterOn && address != null && address in connected

    /**
     * The decision for a row of the Bluetooth list: connected if this state has the device, or if the A2DP proxy of
     * the list says so ([proxyState] = BluetoothA2dp.getConnectionState, null when the list has no proxy).
     */
    fun listShowsConnected(address: String?, proxyState: Int?): Boolean =
        isDeviceConnected(address) || proxyState == STATE_CONNECTED

    /** Bluetooth on or off. Off (or turning on) forgets all devices: the A2DP query gives them again. */
    fun onAdapterState(on: Boolean): Boolean = change {
        adapterOn = on
        connected.clear()
    }

    /**
     * android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED for the device at [address].
     * Only [STATE_CONNECTED] counts as connected: connecting is not yet connected, and disconnecting stops the audio.
     */
    fun onA2dpState(address: String?, state: Int): Boolean = change {
        val key = address ?: UNKNOWN_DEVICE
        if (state == STATE_CONNECTED) {
            // A broadcast is only sent when Bluetooth is on.
            adapterOn = true
            connected.add(key)
        } else {
            connected.remove(key)
        }
    }

    /** The connected devices from BluetoothA2dp.getConnectedDevices(). */
    fun onConnectedDevices(addresses: Collection<String>): Boolean = change {
        connected.clear()
        connected.addAll(addresses)
        if (addresses.isNotEmpty()) adapterOn = true
    }

    private inline fun change(block: () -> Unit): Boolean {
        val before = isConnected
        block()
        return isConnected != before
    }

    companion object {
        /** BluetoothProfile.STATE_CONNECTED. */
        const val STATE_CONNECTED = 2
        private const val UNKNOWN_DEVICE = "?"

        /** The state of the app (one MainActivity): the status bar and the Bluetooth list use it. */
        @JvmStatic
        val shared = BluetoothAudioState()
    }
}
