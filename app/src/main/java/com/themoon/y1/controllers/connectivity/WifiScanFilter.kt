package com.themoon.y1.controllers.connectivity

/**
 * The MediaTek Wi-Fi driver adds a fake scan result when the Wi-Fi NVRAM has no valid MAC address:
 * SSID "NVRAM WARNING: Err = 0x10", BSSID 00:00:00:00:00:00, level 0. It is not a network, so the Wi-Fi list leaves it out.
 */
object WifiScanFilter {
    private const val EMPTY_BSSID = "00:00:00:00:00:00"
    private const val DRIVER_WARNING_PREFIX = "NVRAM WARNING"

    fun isDriverWarning(bssid: String?, ssid: String?): Boolean =
        bssid == EMPTY_BSSID || ssid?.startsWith(DRIVER_WARNING_PREFIX) == true
}
