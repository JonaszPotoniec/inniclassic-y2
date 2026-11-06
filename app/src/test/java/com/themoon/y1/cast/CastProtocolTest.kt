package com.themoon.y1.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class CastProtocolTest {

    private val sonosXml = """<?xml version="1.0"?><root><device><deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType>
        <friendlyName>192.168.1.21 - Sonos One</friendlyName><manufacturer>Sonos, Inc.</manufacturer><roomName>Living Room</roomName>
        <UDN>uuid:RINCON_F0F6C181A47C01400</UDN><serviceList><service><serviceType>urn:schemas-upnp-org:service:AlarmClock:1</serviceType>
        <controlURL>/AlarmClock/Control</controlURL></service></serviceList><deviceList>
        <device><deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType><serviceList><service>
        <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>/MediaServer/ContentDirectory/Control</controlURL>
        </service></serviceList></device>
        <device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><serviceList>
        <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/MediaRenderer/RenderingControl/Control</controlURL></service>
        <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/MediaRenderer/AVTransport/Control</controlURL></service>
        </serviceList></device></deviceList></device></root>"""

    private val tvXml = """<?xml version="1.0"?><root><device><friendlyName>Google TV(192.168.1.93)</friendlyName>
        <manufacturer>Microsoft Corporation</manufacturer><UDN>uuid:ff7d7ffd-7766-4b77-afb8-b7ed7be7bffa</UDN><serviceList>
        <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>AVTransport/ff7d/control</controlURL></service>
        <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>RenderingControl/ff7d/control</controlURL></service>
        </serviceList></device></root>"""

    @Test
    fun ssdpResponse() {
        val r = CastProtocol.parseSsdpResponse("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age = 1800\r\n" +
            "LOCATION: http://192.168.1.21:1400/xml/device_description.xml\r\nSERVER: Linux UPnP/1.0 Sonos/97.2-81220 (ZPS18)\r\n\r\n")
        assertEquals("http://192.168.1.21:1400/xml/device_description.xml", r!!.first)
        assertTrue(r.second.contains("Sonos"))
        assertNull(CastProtocol.parseSsdpResponse("HTTP/1.1 200 OK\r\n\r\n"))
    }

    @Test
    fun sonosDescription() {
        val d = CastProtocol.parseDescription(sonosXml, "http://192.168.1.21:1400/xml/device_description.xml", "Sonos/97.2")!!
        assertEquals(CastDevice.Kind.SONOS, d.kind)
        assertEquals("Living Room", d.name)
        assertEquals("http://192.168.1.21:1400/MediaRenderer/AVTransport/Control", d.avTransportUrl)
        assertEquals("http://192.168.1.21:1400/MediaRenderer/RenderingControl/Control", d.renderingUrl)
        assertEquals("RINCON_F0F6C181A47C01400", d.rinconId)
    }

    @Test
    fun dlnaDescriptionWithRelativeUrls() {
        val d = CastProtocol.parseDescription(tvXml, "http://192.168.1.93:16770", "UPnP/1.0, DLNADOC/1.50 Platinum/1.0.5.13")!!
        assertEquals(CastDevice.Kind.DLNA, d.kind)
        assertEquals("Google TV(192.168.1.93)", d.name)
        assertEquals("http://192.168.1.93:16770/AVTransport/ff7d/control", d.avTransportUrl)
        assertNull(d.rinconId)
    }

    @Test
    fun descriptionWithoutAvTransportIsNotAPlayer() {
        assertNull(CastProtocol.parseDescription("<root><device><friendlyName>NAS</friendlyName></device></root>", "http://1.2.3.4:80/d.xml", ""))
    }

    @Test
    fun didlEscapesAndHasDuration() {
        val m = CastMedia("http://192.168.1.50:4000/m/abc.mp3", "audio/mpeg", "Rock & Roll <Live>", "AC/DC", "", 322022L,
            13205478L, "https://example.org/c.jpg")
        val didl = CastProtocol.didl(m)
        assertTrue(didl.contains("<dc:title>Rock &amp; Roll &lt;Live&gt;</dc:title>"))
        assertTrue(didl.contains("<upnp:artist>AC/DC</upnp:artist>"))
        assertTrue(!didl.contains("<upnp:album>"))
        assertTrue(didl.contains("duration=\"0:05:22.022\""))
        assertTrue(didl.contains("<upnp:albumArtURI>https://example.org/c.jpg</upnp:albumArtURI>"))
    }

    @Test
    fun upnpTime() {
        assertEquals("0:05:22", CastProtocol.formatTime(322_999))
        assertEquals("1:00:00.500", CastProtocol.formatTime(3_600_500, millis = true))
        assertEquals(242_000L, CastProtocol.parseTime("0:04:02"))
        assertEquals(242_500L, CastProtocol.parseTime("00:04:02.5"))
        assertNull(CastProtocol.parseTime("NOT_IMPLEMENTED"))
    }

    @Test
    fun range() {
        assertEquals(0L..99L, CastProtocol.parseRange("bytes=0-99", 1000))
        assertEquals(311296L..13205477L, CastProtocol.parseRange("bytes=311296-", 13205478))
        assertEquals(500L..999L, CastProtocol.parseRange("bytes=500-", 1000))
        assertEquals(900L..999L, CastProtocol.parseRange("bytes=-100", 1000))
        assertEquals(10L..999L, CastProtocol.parseRange("bytes=10-5000", 1000))
        assertNull(CastProtocol.parseRange("bytes=2000-", 1000))
        assertNull(CastProtocol.parseRange(null, 1000))
    }

    @Test
    fun castFrameRoundTrip() {
        val payload = "{\"type\":\"LOAD\",\"text\":\"Ação ${"x".repeat(300)}\"}"
        val frame = CastProtocol.castFrame("sender-y2", "receiver-0", "urn:x-cast:com.google.cast.media", payload)
        val n = ((frame[0].toInt() and 0xff) shl 24) or ((frame[1].toInt() and 0xff) shl 16) or
            ((frame[2].toInt() and 0xff) shl 8) or (frame[3].toInt() and 0xff)
        assertEquals(frame.size - 4, n)
        val m = CastProtocol.parseCastMessage(frame.copyOfRange(4, frame.size))
        assertEquals("sender-y2", m.source)
        assertEquals("receiver-0", m.destination)
        assertEquals("urn:x-cast:com.google.cast.media", m.namespace)
        assertEquals(payload, m.payload)
    }

    @Test
    fun mdnsCastAnswer() {
        val inst = "Smart-TV-Pro-c6ad._googlecast._tcp.local"
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 1, 0, 1, 0, 0, 0, 2))
        writeName(out, CastProtocol.CAST_SERVICE); out.write(byteArrayOf(0, 12, 0, 1))
        // Answer: PTR
        val ptrData = ByteArrayOutputStream().also { writeName(it, inst) }.toByteArray()
        record(out, CastProtocol.CAST_SERVICE, 12, ptrData)
        // Additional: SRV (port 8009) and TXT (fn=Living Room TV)
        val srv = ByteArrayOutputStream().also { it.write(byteArrayOf(0, 0, 0, 0, 0x1f, 0x49)); writeName(it, "c6ad.local") }.toByteArray()
        record(out, inst, 33, srv)
        val txt = ByteArrayOutputStream()
        for (s in listOf("id=c6ad", "fn=Living Room TV", "md=Google TV")) { val b = s.toByteArray(); txt.write(b.size); txt.write(b) }
        record(out, inst, 16, txt.toByteArray())
        val found = CastProtocol.parseMdnsCast(out.toByteArray())
        assertEquals(1, found.size)
        assertEquals("Living Room TV", found[0].friendlyName)
        assertEquals(8009, found[0].port)
    }

    @Test
    fun mdnsQueryAsksForPtr() {
        val q = CastProtocol.mdnsQuery()
        assertEquals(1, q[5].toInt())
        assertEquals(12, q[q.size - 3].toInt())
        assertNotNull(q)
    }

    private fun writeName(out: ByteArrayOutputStream, name: String) {
        for (l in name.split('.')) { val b = l.toByteArray(); out.write(b.size); out.write(b) }
        out.write(0)
    }

    private fun record(out: ByteArrayOutputStream, name: String, type: Int, data: ByteArray) {
        writeName(out, name)
        out.write(byteArrayOf(0, type.toByte(), 0, 1, 0, 0, 0x11, 0x94.toByte(), (data.size shr 8).toByte(), data.size.toByte()))
        out.write(data)
    }
}
