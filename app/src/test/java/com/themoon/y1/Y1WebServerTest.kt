package com.themoon.y1

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import java.net.Socket
import java.nio.file.Files

class Y1WebServerTest {

    private fun request(server: Y1WebServer, request: String): String {
        Socket("127.0.0.1", server.getListeningPort()).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
            socket.shutdownOutput()
            val buffer = ByteArray(1024)
            val response = StringBuilder()
            var count: Int
            while (socket.getInputStream().read(buffer).also { count = it } != -1) {
                response.append(String(buffer, 0, count, Charsets.UTF_8))
            }
            return response.toString()
        }
    }

    @Test
    fun uploadTraversalFailuresAndShutdown() {
        val root = Files.createTempDirectory("inni-server-test").toFile()
        val server = Y1WebServer(null, root, 0)
        server.start()
        try {
            val deadline = System.currentTimeMillis() + 3000
            while (server.getListeningPort() <= 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertTrue(server.getListeningPort() > 0)
            assertTrue(request(server, "POST /api/upload?name=song.mp3 HTTP/1.1\r\nContent-Length: 3\r\n\r\nabc").startsWith("HTTP/1.1 200"))
            val song = File(root, "song.mp3")
            assertEquals("abc", Files.readAllBytes(song.toPath()).toString(Charsets.UTF_8))
            assertTrue(request(server, "POST /api/save?path=song.mp3 HTTP/1.1\r\nContent-Length: 5\r\n\r\nx").startsWith("HTTP/1.1 400"))
            assertEquals("abc", Files.readAllBytes(song.toPath()).toString(Charsets.UTF_8))
            assertTrue(request(server, "GET /api/file?path=..%2Foutside HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 400"))
            assertTrue(request(server, "POST /api/delete?path= HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 400"))
            assertTrue(root.isDirectory)
            assertTrue(
                request(server, "POST /api/delete?path=song.mp3 HTTP/1.1\r\nHost: localhost\r\nOrigin: https://other.example\r\n\r\n")
                    .startsWith("HTTP/1.1 400")
            )
            assertTrue(song.exists())
            assertTrue(request(server, "POST /api/rename?old=song.mp3&new=renamed.mp3 HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 200"))
            assertTrue(request(server, "GET /api/file?path=renamed.mp3 HTTP/1.1\r\n\r\n").endsWith("abc"))
            assertTrue(request(server, "GET /missing HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 404"))
        } finally {
            server.stopServer()
            server.join(3000)
            assertFalse(server.isAlive)
            root.listFiles()?.forEach { Files.delete(it.toPath()) }
            Files.delete(root.toPath())
        }
    }
}
