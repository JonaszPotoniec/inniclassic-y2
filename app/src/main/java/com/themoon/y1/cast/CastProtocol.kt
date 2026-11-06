package com.themoon.y1.cast

import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.Locale

/** Message formats of SSDP, mDNS, UPnP (SOAP, DIDL-Lite) and Cast v2. No I/O: the unit tests use them directly. */
object CastProtocol {

    // --- SSDP ---

    const val SSDP_SEARCH =
        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

    /** LOCATION and SERVER of an SSDP answer, or null. */
    fun parseSsdpResponse(text: String): Pair<String, String>? {
        val location = header(text, "location") ?: return null
        return location to header(text, "server").orEmpty()
    }

    private fun header(text: String, name: String): String? =
        Regex("(?im)^$name:\\s*(.+?)\\s*$").find(text)?.groupValues?.get(1)

    /** The player that a UPnP device description names, or null if it has no AVTransport. */
    fun parseDescription(xml: String, location: String, server: String): CastDevice? {
        val base = tag(xml, "URLBase") ?: location
        var avTransport: String? = null
        var rendering: String? = null
        for (m in Regex("(?s)<service>(.*?)</service>").findAll(xml)) {
            val service = m.groupValues[1]
            val control = tag(service, "controlURL") ?: continue
            val type = tag(service, "serviceType").orEmpty()
            // A Sonos description lists the MediaServer part first: take the MediaRenderer control URLs
            if (control.contains("MediaServer")) continue
            if (type.contains(":AVTransport:") && avTransport == null) avTransport = resolve(base, control)
            if (type.contains(":RenderingControl:") && rendering == null) rendering = resolve(base, control)
        }
        if (avTransport == null) return null
        val url = URL(location)
        val isSonos = server.contains("Sonos", ignoreCase = true) ||
            tag(xml, "manufacturer").orEmpty().contains("Sonos", ignoreCase = true)
        val udn = tag(xml, "UDN")?.removePrefix("uuid:")
        val name = (if (isSonos) tag(xml, "roomName") else null) ?: tag(xml, "friendlyName") ?: url.host
        return CastDevice(
            name = unescapeXml(name), kind = if (isSonos) CastDevice.Kind.SONOS else CastDevice.Kind.DLNA,
            host = url.host, port = if (url.port > 0) url.port else 80,
            avTransportUrl = avTransport, renderingUrl = rendering,
            rinconId = if (isSonos && udn != null && udn.startsWith("RINCON_")) udn else null
        )
    }

    private fun resolve(base: String, path: String): String = URL(URL(base), path).toString()

    /** The text of the first <name> element (any namespace prefix), or null. */
    fun tag(xml: String, name: String): String? =
        Regex("(?s)<(?:[A-Za-z0-9]+:)?$name(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9]+:)?$name>").find(xml)?.groupValues?.get(1)?.trim()

    // --- UPnP (SOAP and DIDL-Lite) ---

    fun soapEnvelope(service: String, action: String, args: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action " +
            "xmlns:u=\"urn:schemas-upnp-org:service:$service:1\"><InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"

    /** DIDL-Lite track info. The Sonos app shows title, artist and album; its cover must be an HTTPS URL. */
    fun didl(media: CastMedia): String {
        val sb = StringBuilder()
        sb.append("<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
            .append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" ")
            .append("xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\" ")
            .append("xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">")
            .append("<item id=\"1\" parentID=\"0\" restricted=\"1\">")
            .append("<dc:title>").append(escapeXml(media.title)).append("</dc:title>")
        if (media.artist.isNotEmpty()) {
            sb.append("<dc:creator>").append(escapeXml(media.artist)).append("</dc:creator>")
                .append("<upnp:artist>").append(escapeXml(media.artist)).append("</upnp:artist>")
        }
        if (media.album.isNotEmpty()) sb.append("<upnp:album>").append(escapeXml(media.album)).append("</upnp:album>")
        media.coverUrl?.let { sb.append("<upnp:albumArtURI>").append(escapeXml(it)).append("</upnp:albumArtURI>") }
        sb.append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
            .append("<res protocolInfo=\"http-get:*:").append(media.mimeType).append(":*\"")
        if (media.durationMs > 0) sb.append(" duration=\"").append(formatTime(media.durationMs, millis = true)).append('"')
        if (media.sizeBytes > 0) sb.append(" size=\"").append(media.sizeBytes).append('"')
        sb.append('>').append(escapeXml(media.url)).append("</res></item></DIDL-Lite>")
        return sb.toString()
    }

    /** H:MM:SS (UPnP time), with .mmm when [millis]. */
    fun formatTime(ms: Long, millis: Boolean = false): String {
        val t = ms.coerceAtLeast(0L)
        val s = t / 1000
        val base = String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        return if (millis) base + String.format(Locale.US, ".%03d", t % 1000) else base
    }

    /** Parses H:MM:SS or H:MM:SS.fff (UPnP RelTime); null for NOT_IMPLEMENTED and other text. */
    fun parseTime(text: String?): Long? {
        val m = Regex("^(\\d+):(\\d{1,2}):(\\d{1,2})(?:\\.(\\d+))?$").find(text?.trim() ?: return null) ?: return null
        val (h, min, s, frac) = m.destructured
        val fracMs = if (frac.isEmpty()) 0L else (frac + "00").substring(0, 3).toLong()
        return ((h.toLong() * 60 + min.toLong()) * 60 + s.toLong()) * 1000 + fracMs
    }

    fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    fun unescapeXml(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    // --- mDNS (Cast devices: _googlecast._tcp) ---

    const val CAST_SERVICE = "_googlecast._tcp.local"

    /** One PTR question. Sent from a normal port: the devices answer to that port (RFC 6762, legacy unicast). */
    fun mdnsQuery(name: String = CAST_SERVICE): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0)) // id 0, flags 0, 1 question
        for (label in name.split('.')) {
            val b = label.toByteArray(Charsets.UTF_8)
            out.write(b.size)
            out.write(b)
        }
        out.write(byteArrayOf(0, 0, 12, 0, 1)) // end of name, type PTR, class IN
        return out.toByteArray()
    }

    /** A Cast device in an mDNS answer: the friendly name (TXT "fn") and the port (SRV); the host is the sender. */
    data class MdnsCast(val instance: String, val friendlyName: String?, val port: Int)

    fun parseMdnsCast(data: ByteArray, length: Int = data.size): List<MdnsCast> {
        val records = try { parseDnsRecords(data, length) } catch (_: Exception) { return emptyList() }
        val instances = records.filter { it.type == 12 && it.name.equals(CAST_SERVICE, true) }.map { it.ptr ?: "" }
            .filter { it.isNotEmpty() }
        return instances.distinct().map { inst ->
            val srv = records.firstOrNull { it.type == 33 && it.name.equals(inst, true) }
            val txt = records.firstOrNull { it.type == 16 && it.name.equals(inst, true) }
            MdnsCast(inst, txt?.txt?.get("fn"), srv?.port ?: 8009)
        }
    }

    private class DnsRecord(val name: String, val type: Int) {
        var ptr: String? = null
        var port: Int = 0
        var txt: Map<String, String> = emptyMap()
    }

    private fun parseDnsRecords(d: ByteArray, len: Int): List<DnsRecord> {
        fun u16(i: Int) = ((d[i].toInt() and 0xff) shl 8) or (d[i + 1].toInt() and 0xff)
        val qd = u16(4)
        val rr = u16(6) + u16(8) + u16(10)
        var pos = 12
        repeat(qd) { pos = readName(d, len, pos).second + 4 }
        val out = ArrayList<DnsRecord>()
        repeat(rr) {
            val (name, after) = readName(d, len, pos)
            val type = u16(after)
            val rdLen = u16(after + 8)
            val rd = after + 10
            if (rd + rdLen > len) return out
            val r = DnsRecord(name, type)
            when (type) {
                12 -> r.ptr = readName(d, len, rd).first
                33 -> r.port = u16(rd + 4)
                16 -> {
                    val map = HashMap<String, String>()
                    var i = rd
                    while (i < rd + rdLen) {
                        val n = d[i].toInt() and 0xff
                        val s = String(d, i + 1, n, Charsets.UTF_8)
                        val eq = s.indexOf('=')
                        if (eq > 0) map[s.substring(0, eq)] = s.substring(eq + 1)
                        i += 1 + n
                    }
                    r.txt = map
                }
            }
            out.add(r)
            pos = rd + rdLen
        }
        return out
    }

    /** A DNS name at [start] (with compression pointers) and the position after it. */
    private fun readName(d: ByteArray, len: Int, start: Int): Pair<String, Int> {
        val labels = ArrayList<String>()
        var pos = start
        var end = -1
        var jumps = 0
        while (pos < len) {
            val n = d[pos].toInt() and 0xff
            when {
                n == 0 -> { if (end < 0) end = pos + 1; break }
                n and 0xc0 == 0xc0 -> {
                    if (end < 0) end = pos + 2
                    pos = ((n and 0x3f) shl 8) or (d[pos + 1].toInt() and 0xff)
                    if (++jumps > 20) break
                }
                else -> { labels.add(String(d, pos + 1, n, Charsets.UTF_8)); pos += 1 + n }
            }
        }
        return labels.joinToString(".") to end
    }

    // --- Cast v2 (CastMessage protobuf, length-prefixed) ---

    fun castFrame(source: String, destination: String, namespace: String, payload: String): ByteArray {
        val body = ByteArrayOutputStream()
        fun key(field: Int, wire: Int) = writeVarint(body, ((field shl 3) or wire).toLong())
        fun str(field: Int, s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            key(field, 2); writeVarint(body, b.size.toLong()); body.write(b)
        }
        key(1, 0); writeVarint(body, 0) // protocol_version CASTV2_1_0
        str(2, source)
        str(3, destination)
        str(4, namespace)
        key(5, 0); writeVarint(body, 0) // payload_type STRING
        str(6, payload)
        val b = body.toByteArray()
        val n = b.size
        return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + b
    }

    /** Fields of one CastMessage body (without the 4-byte length): source, destination, namespace, payload. */
    data class CastMessage(val source: String, val destination: String, val namespace: String, val payload: String)

    fun parseCastMessage(b: ByteArray): CastMessage {
        val strings = HashMap<Int, String>()
        var i = 0
        fun readVarint(): Long {
            var v = 0L
            var shift = 0
            while (true) {
                val x = b[i++].toInt() and 0xff
                v = v or ((x and 0x7f).toLong() shl shift)
                shift += 7
                if (x < 0x80) return v
            }
        }
        while (i < b.size) {
            val key = readVarint()
            val field = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> readVarint()
                2 -> {
                    val n = readVarint().toInt()
                    strings[field] = String(b, i, n, Charsets.UTF_8)
                    i += n
                }
                else -> throw IllegalArgumentException("wire type ${key and 7}")
            }
        }
        return CastMessage(strings[2].orEmpty(), strings[3].orEmpty(), strings[4].orEmpty(), strings[6].orEmpty())
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if (v and 0x7fL.inv() == 0L) { out.write(v.toInt()); return }
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
    }

    // --- HTTP Range ---

    /** First and last byte of a "bytes=a-b", "bytes=a-" or "bytes=-n" range, or null for no range or a bad one. */
    fun parseRange(header: String?, size: Long): LongRange? {
        val m = Regex("^bytes=(\\d*)-(\\d*)").find(header?.trim() ?: return null) ?: return null
        val (a, b) = m.destructured
        if (size <= 0 || (a.isEmpty() && b.isEmpty())) return null
        val first: Long
        val last: Long
        if (a.isEmpty()) {
            first = (size - b.toLong()).coerceAtLeast(0L)
            last = size - 1
        } else {
            first = a.toLong()
            last = if (b.isEmpty()) size - 1 else minOf(b.toLong(), size - 1)
        }
        return if (first > last || first >= size) null else first..last
    }

    fun mimeType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase(Locale.US)) {
        "mp3" -> "audio/mpeg"
        "m4a", "m4b", "aac", "mp4" -> "audio/mp4"
        "flac" -> "audio/flac"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }
}
