package com.themoon.y1.managers

import android.util.Log
import java.util.regex.Pattern

/**
 * Y2-only USB Mass Storage control via MountService binder calls as root.
 * IMountService (KitKat) txn codes, verified on Y2:
 * 3 = isUsbMassStorageConnected(), 4 = setUsbMassStorageEnabled(boolean), 5 = isUsbMassStorageEnabled().
 */
object UsbMassStorageController {
    private const val TAG = "Y1Ums"
    fun isConnected(): Boolean {
        return parseBool(call("service call mount 3"))
    }
    fun isEnabled(): Boolean {
        return parseBool(call("service call mount 5"))
    }

    /** Returns the resulting enabled state (polls up to ~6 s for vold to settle). */

    fun setEnabled(enable: Boolean): Boolean {
        call("service call mount 4 i32 " + (if (enable) 1 else 0))
        for (i in 0 until 30) {
            val now = isEnabled()
            if (now == enable) return now
            try {
                Thread.sleep(200)
            } catch (e: InterruptedException) {
                break
            }
        }
        return isEnabled()
    }

    private fun call(cmd: String): String {
        val out = ExternalSdMountMonitor.runSuTimed(cmd)
        Log.i(TAG, "$cmd -> $out")
        return out
    }

    /** "Result: Parcel(00000000 00000001   '........')" -> second word == 1 */

    fun parseBool(out: String?): Boolean {
        if (out == null) return false
        val m = Pattern.compile("Parcel\\(\\s*[0-9a-fA-F]{8}\\s+([0-9a-fA-F]{8})").matcher(out)
        return m.find() && Integer.parseInt(m.group(1), 16) != 0
    }
}
