package com.themoon.y1.cast

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Finds the players on the Wi-Fi: Sonos and other DLNA players by SSDP, Google Cast devices (Chromecast,
 * Google TV) by mDNS. Both searches send from a normal UDP port, so the answers come back to it directly
 * (no multicast lock). A device with Cast and DLNA (a Google TV) shows once, as Cast: Cast shows the cover
 * and does not ask for a confirmation on the TV.
 */
object CastDiscovery {
    private const val TAG = "CastDiscovery"

    /** Blocks for about [timeoutMs]: call it from a background thread. */
    fun discover(timeoutMs: Int = 2500): List<CastDevice> {
        val upnp = ArrayList<CastDevice>()
        val cast = ArrayList<CastDevice>()
        val t1 = Thread { val r = searchSsdp(timeoutMs); synchronized(upnp) { upnp.addAll(r) } }
        val t2 = Thread { val r = searchMdns(timeoutMs); synchronized(cast) { cast.addAll(r) } }
        t1.start(); t2.start()
        t1.join(timeoutMs + 4000L); t2.join(timeoutMs + 1000L)
        val castHosts = synchronized(cast) { cast.map { it.host }.toSet() }
        val all = synchronized(cast) { cast.toList() } + synchronized(upnp) { upnp.filter { it.host !in castHosts } }
        return all.sortedBy { it.name.lowercase() }
    }

    private fun searchSsdp(timeoutMs: Int): List<CastDevice> {
        val found = LinkedHashMap<String, Pair<String, String>>() // location -> (location, server)
        try {
            udpSearch(CastProtocol.SSDP_SEARCH.toByteArray(), "239.255.255.250", 1900, 4096, timeoutMs) { p ->
                val r = CastProtocol.parseSsdpResponse(String(p.data, 0, p.length, Charsets.UTF_8)) ?: return@udpSearch
                found[r.first] = r
            }
        } catch (e: Exception) {
            Log.w(TAG, "SSDP: $e")
        }
        return found.values.mapNotNull { (location, server) ->
            try {
                CastProtocol.parseDescription(httpGet(location), location, server)
            } catch (e: Exception) {
                Log.w(TAG, "description $location: $e")
                null
            }
        }.distinctBy { it.id }
    }

    private fun searchMdns(timeoutMs: Int): List<CastDevice> {
        val found = LinkedHashMap<String, CastDevice>()
        try {
            udpSearch(CastProtocol.mdnsQuery(), "224.0.0.251", 5353, 9000, timeoutMs) { p ->
                val host = p.address.hostAddress ?: return@udpSearch
                for (c in CastProtocol.parseMdnsCast(p.data, p.length)) {
                    val name = c.friendlyName ?: c.instance.substringBefore('.')
                    val d = CastDevice(name, CastDevice.Kind.CAST, host, c.port)
                    found[d.id] = d
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "mDNS: $e")
        }
        return found.values.toList()
    }

    /**
     * Sends [message] two times to [group]:[port] from a normal UDP port, then gives each answer to [onAnswer]
     * for [timeoutMs].
     */
    private fun udpSearch(message: ByteArray, group: String, port: Int, bufferSize: Int, timeoutMs: Int, onAnswer: (DatagramPacket) -> Unit) {
        val socket = DatagramSocket()
        try {
            socket.soTimeout = 300
            val address = InetAddress.getByName(group)
            repeat(2) { socket.send(DatagramPacket(message, message.size, address, port)) }
            val end = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(bufferSize)
            while (System.currentTimeMillis() < end) {
                val p = DatagramPacket(buf, buf.size)
                try { socket.receive(p) } catch (_: SocketTimeoutException) { continue }
                onAnswer(p)
            }
        } finally {
            socket.close()
        }
    }

    fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 2000
        c.readTimeout = 3000
        try {
            return c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }
}
