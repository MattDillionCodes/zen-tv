package com.zen.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress

class UploadServerTest {
    private val later = System.currentTimeMillis() + 60_000
    private fun stream(text: String) = ByteArrayInputStream(text.toByteArray())

    @Test
    fun readsCrlfLinesAndEndOfStream() {
        val input = stream("GET /?t=abc HTTP/1.1\r\nHost: tv\r\n")
        assertEquals("GET /?t=abc HTTP/1.1", UploadServer.readLine(input, later))
        assertEquals("Host: tv", UploadServer.readLine(input, later))
        assertNull(UploadServer.readLine(input, later))
    }

    @Test(expected = IOException::class)
    fun refusesEndlessLines() {
        UploadServer.readLine(stream("a".repeat(10_000)), later)
    }

    @Test(expected = IOException::class)
    fun givesUpAfterTheDeadline() {
        UploadServer.readLine(stream("GET / HTTP/1.1\r\n"), deadline = System.currentTimeMillis() - 1)
    }

    @Test
    fun parsesHeadersCaseInsensitively() {
        val headers = UploadServer.readHeaders(stream("Content-Length: 42\r\nX-Thing:  y \r\n\r\nbody"), later)!!
        assertEquals("42", headers["content-length"])
        assertEquals("y", headers["x-thing"])
    }

    @Test
    fun capsHeaderCountAndSize() {
        val tooMany = (1..80).joinToString("") { "X-$it: a\r\n" } + "\r\n"
        assertNull(UploadServer.readHeaders(stream(tooMany), later))
        val tooBig = (1..10).joinToString("") { "X-$it: ${"a".repeat(2_000)}\r\n" } + "\r\n"
        assertNull(UploadServer.readHeaders(stream(tooBig), later))
    }

    @Test
    fun stopsReadingAtTheHeaderLimitEvenIfTheClientKeepsSending() {
        var served = 0
        val endless = object : InputStream() {          // a client that never stops sending headers
            val line = "X: a\r\n".toByteArray()
            override fun read(): Int = line[served++ % line.size].toInt()
        }
        assertNull(UploadServer.readHeaders(endless, later))
        assertTrue("read $served bytes", served < 1_000)
    }

    @Test
    fun onlyLocalNetworkClientsAreServed() {
        listOf("192.168.1.20", "10.0.2.2", "172.16.4.1", "127.0.0.1", "fe80::1", "fd12:3456::1").forEach {
            assertTrue(it, UploadServer.isLocal(InetAddress.getByName(it)))
        }
        listOf("8.8.8.8", "2001:4860:4860::8888", "100.64.0.1").forEach {
            assertFalse(it, UploadServer.isLocal(InetAddress.getByName(it)))
        }
    }

    @Test
    fun rejectsFilesThatAreNotJpegs() {
        val png = File.createTempFile("zen", ".bin").apply { writeBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())) }
        val text = File.createTempFile("zen", ".bin").apply { writeText("hello") }
        try {
            assertEquals("That wasn't a JPEG photo", UploadServer.checkPhoto(png))
            assertEquals("That wasn't a JPEG photo", UploadServer.checkPhoto(text))
        } finally {
            png.delete(); text.delete()
        }
    }
}
