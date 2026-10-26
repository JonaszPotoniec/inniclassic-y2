package com.themoon.y1.controllers.settings

import android.content.Context
import android.content.res.Resources
import android.net.ConnectivityManager
import android.os.SystemClock
import android.provider.Settings
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Calendar
import java.util.TimeZone

/**
 * Settings → Date & Time: set the clock now, from the internet (SNTP) or from one changed value (Year...Minute).
 *
 * The app is not a system app, so it cannot call AlarmManager.setTime itself (SET_TIME is signature|system).
 * It calls it through su: `service call alarm 2` is IAlarmManager.setTime(long) on API 19. The service sets the
 * system clock and the hardware clock (/dev/alarm) and sends ACTION_TIME_CHANGED, so all clocks show the new time.
 * The time is UTC milliseconds, so the time zone has no effect on it.
 *
 * Android's own NetworkTimeUpdateService (auto_time) asks NTP only when the network changes and once a day, and
 * gives no result. So "Sync Time Now" asks the server itself and shows the result.
 */
object DateTimeSync {
    const val NTP_PORT = 123
    const val TIMEOUT_MS = 5000
    private const val PACKET_SIZE = 48
    /** Seconds from 1900-01-01 (NTP) to 1970-01-01 (Unix). */
    private const val NTP_TO_UNIX_SECONDS = 2208988800L
    private const val FALLBACK_SERVER = "pool.ntp.org"

    /** The NTP servers to ask, in order: the Android setting, the ROM's server, then pool.ntp.org. */
    fun servers(context: Context): List<String> {
        val out = ArrayList<String>()
        try {
            Settings.Global.getString(context.contentResolver, "ntp_server")?.let { out.add(it) }
        } catch (_: Exception) {}
        try {
            val id = Resources.getSystem().getIdentifier("config_ntpServer", "string", "android")
            if (id != 0) out.add(Resources.getSystem().getString(id))
        } catch (_: Exception) {}
        out.add(FALLBACK_SERVER)
        return out.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    fun isWifiConnected(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI)?.isConnected == true
    } catch (_: Exception) {
        false
    }

    /** The network time, and the elapsedRealtime at which it was valid. */
    class NetworkTime(val utcMs: Long, val atElapsedMs: Long) {
        /** The network time now. */
        fun now(): Long = utcMs + (SystemClock.elapsedRealtime() - atElapsedMs)
    }

    /** Asks [host] for the time (blocking; not on the main thread). Null if no valid answer. */
    fun query(host: String): NetworkTime? {
        var socket: DatagramSocket? = null
        try {
            val address = InetAddress.getByName(host)
            socket = DatagramSocket()
            socket.soTimeout = TIMEOUT_MS
            val t1 = System.currentTimeMillis()
            val e1 = SystemClock.elapsedRealtime()
            val request = request(t1)
            socket.send(DatagramPacket(request, request.size, address, NTP_PORT))
            val buffer = ByteArray(PACKET_SIZE)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            val e4 = SystemClock.elapsedRealtime()
            // The local time of the answer from the monotonic clock: a clock change during the query has no effect
            val t4 = t1 + (e4 - e1)
            val utc = networkTime(buffer, response.length, t1, t4) ?: return null
            return NetworkTime(utc, e4)
        } catch (_: Exception) {
            return null
        } finally {
            socket?.close()
        }
    }

    /** An SNTP client request (RFC 4330): version 3, mode 3 (client), with [transmitMs] as the transmit time. */
    fun request(transmitMs: Long): ByteArray {
        val b = ByteArray(PACKET_SIZE)
        b[0] = ((3 shl 3) or 3).toByte()
        writeTimestamp(b, 40, transmitMs)
        return b
    }

    /**
     * The network time at [t4] from a server answer, or null if the answer is not valid (not a server packet,
     * stratum 0 = "kiss of death", alarm leap indicator, no transmit time, or not the answer to our request [t1]).
     * [t1] = local send time, [t4] = local receive time (ms).
     */
    fun networkTime(b: ByteArray, length: Int, t1: Long, t4: Long): Long? {
        if (length < PACKET_SIZE) return null
        val leap = (b[0].toInt() shr 6) and 3
        val mode = b[0].toInt() and 7
        val stratum = b[1].toInt() and 0xFF
        if (leap == 3 || (mode != 4 && mode != 5) || stratum == 0 || stratum > 15) return null
        val originate = readTimestamp(b, 24)
        val receive = readTimestamp(b, 32)
        val transmit = readTimestamp(b, 40)
        if (transmit == 0L || receive == 0L || Math.abs(originate - t1) > 1) return null
        // Clock offset = ((T2 - T1) + (T3 - T4)) / 2
        val offset = ((receive - t1) + (transmit - t4)) / 2
        return t4 + offset
    }

    /** An NTP timestamp (seconds since 1900 and a 32-bit fraction) at [offset], in Unix ms. */
    fun readTimestamp(b: ByteArray, offset: Int): Long {
        val seconds = read32(b, offset)
        val fraction = read32(b, offset + 4)
        if (seconds == 0L && fraction == 0L) return 0L
        return (seconds - NTP_TO_UNIX_SECONDS) * 1000 + fraction * 1000 / 0x100000000L
    }

    fun writeTimestamp(b: ByteArray, offset: Int, unixMs: Long) {
        val seconds = unixMs / 1000 + NTP_TO_UNIX_SECONDS
        val fraction = (unixMs % 1000) * 0x100000000L / 1000
        write32(b, offset, seconds)
        write32(b, offset + 4, fraction)
    }

    private fun read32(b: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (b[offset + i].toLong() and 0xFF)
        return v
    }

    private fun write32(b: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 4) b[offset + i] = (value shr (24 - 8 * i)).toByte()
    }

    /** The arguments of `service call alarm 2` for [utcMs]: a long is two i32 in the parcel, low word first. */
    fun setTimeArgs(utcMs: Long): String = "i32 ${utcMs.toInt()} i32 ${(utcMs ushr 32).toInt()}"

    /**
     * Sets the system clock (and the hardware clock) to [utcMs] through su (blocking). With [manual], automatic
     * time (auto_time) goes off first, as the old Apply button did, so the network time does not replace it.
     * Returns null when it worked, else an error message key.
     */
    fun setSystemTime(utcMs: Long, manual: Boolean): String? {
        val command = (if (manual) "settings put global auto_time 0; " else "") +
            "service call alarm 2 ${setTimeArgs(utcMs + if (manual) 0 else SET_DELAY_MS)}"
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            // A good reply is "Result: Parcel(00000000    '....')": no exception.
            if (out.contains("Parcel(00000000 ")) null else "Failed: Root access required."
        } catch (_: Exception) {
            "Failed: Root access required."
        }
    }

    /** About the time from the start of su to the clock change: added to the network time. */
    private const val SET_DELAY_MS = 150L

    /**
     * The time [nowMs] with one value changed: [field] is "Year", "Month" (1..12), "Day", "Hour" (0..23) or "Minute".
     * The day stays in the month (31 → 30 in April). A new hour or minute sets the seconds to 0.
     */
    fun withField(nowMs: Long, zone: TimeZone, field: String, value: Int): Long {
        val c = Calendar.getInstance(zone)
        c.timeInMillis = nowMs
        val day = c.get(Calendar.DAY_OF_MONTH)
        when (field) {
            "Year", "Month" -> {
                c.set(Calendar.DAY_OF_MONTH, 1)
                if (field == "Year") c.set(Calendar.YEAR, value) else c.set(Calendar.MONTH, value - 1)
                c.set(Calendar.DAY_OF_MONTH, Math.min(day, c.getActualMaximum(Calendar.DAY_OF_MONTH)))
            }
            "Day" -> c.set(Calendar.DAY_OF_MONTH, Math.min(value, c.getActualMaximum(Calendar.DAY_OF_MONTH)))
            "Hour", "Minute" -> {
                c.set(if (field == "Hour") Calendar.HOUR_OF_DAY else Calendar.MINUTE, value)
                c.set(Calendar.SECOND, 0)
                c.set(Calendar.MILLISECOND, 0)
            }
            else -> return nowMs
        }
        return c.timeInMillis
    }
}
