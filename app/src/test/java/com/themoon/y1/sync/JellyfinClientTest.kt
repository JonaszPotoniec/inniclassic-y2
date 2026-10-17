package com.themoon.y1.sync

import com.themoon.y1.InniClassicApplication
import com.themoon.y1.sync.jellyfin.JellyfinClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class JellyfinClientTest {

    @Test
    fun testNormalizeUrl() {
        assertEquals("http://192.168.1.100:8096", JellyfinClient.normalizeUrl("192.168.1.100:8096"))
        assertEquals("http://192.168.1.100:8096", JellyfinClient.normalizeUrl("http://192.168.1.100:8096/"))
        assertEquals("https://jellyfin.example.com", JellyfinClient.normalizeUrl("https://jellyfin.example.com///"))
        assertEquals("", JellyfinClient.normalizeUrl(null))
        assertEquals("", JellyfinClient.normalizeUrl("   "))
    }

    @Test
    fun testClientInitializationWithTls() {
        InniClassicApplication.installConscrypt()
        val client = JellyfinClient("https://192.168.1.100:8920", "TestDevice")
        assertNotNull(client)
    }
}
