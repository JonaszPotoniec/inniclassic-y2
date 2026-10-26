package com.themoon.y1.controllers.connectivity

/**
 * Signal strength of a Wi-Fi network as 0..3 filled arcs (like macOS), from the RSSI of a scan result in dBm.
 * Same steps as WifiManager.calculateSignalLevel(rssi, 4) of Android 4.4: -100 dBm or less = 0, -55 dBm or more = 3.
 */
object WifiSignal {
    const val MAX_LEVEL = 3
    private const val MIN_RSSI = -100
    private const val MAX_RSSI = -55

    fun level(rssi: Int): Int {
        // Some drivers give an unsigned byte (Android 4.4 also corrects this); 0 means "no value".
        val dbm = if (rssi > 0) rssi - 256 else rssi
        if (dbm == 0 || dbm <= MIN_RSSI) return 0
        if (dbm >= MAX_RSSI) return MAX_LEVEL
        return (dbm - MIN_RSSI) * MAX_LEVEL / (MAX_RSSI - MIN_RSSI)
    }
}
