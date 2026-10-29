package com.zen.tv

import android.content.Context
import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

/**
 * Tiny LAN HTTP server: the phone opens the QR link, picks a photo, and the page POSTs an already
 * cropped 1920x1080 JPEG to /upload.
 *
 * It runs only while the control panel is open (MainActivity starts and stops it), gets a fresh random
 * code every time it starts, and only talks to devices on the local network. Every request must carry
 * that code. Limits keep a misbehaving device from exhausting memory, threads or storage.
 */
class UploadServer(private val context: Context, private val onWallpaper: (File) -> Unit) {
    @Volatile var token: String = newToken()
        private set
    @Volatile var port: Int = 0
        private set
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var host: String? = null
    private var workers: ExecutorService? = null

    val running: Boolean get() = socket != null

    /** Starts listening with a new code. Returns false when there's no local network. */
    @Synchronized
    fun start(): Boolean {
        if (socket != null) return true
        val lan = lanAddress() ?: return false
        val server = (8080..8090).firstNotNullOfOrNull { p ->
            runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(p), BACKLOG) } }.getOrNull()
        } ?: return false
        token = newToken()
        host = lan
        port = server.localPort
        socket = server
        val pool = Executors.newFixedThreadPool(MAX_CLIENTS).also { workers = it }
        val slots = Semaphore(MAX_CLIENTS)
        thread(name = "zen-upload", isDaemon = true) {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    if (server.isClosed) break
                    Thread.sleep(200) // e.g. out of file descriptors: back off instead of spinning or dying
                    continue
                }
                // Only devices on this network, and only a few at a time; anything else is dropped unread.
                if (!isLocal(client.inetAddress) || !slots.tryAcquire()) {
                    runCatching { client.close() }
                    continue
                }
                try {
                    pool.execute {
                        try { handle(client) } catch (_: Exception) { } finally {
                            runCatching { client.close() }
                            slots.release()
                        }
                    }
                } catch (_: Throwable) {
                    runCatching { client.close() }
                    slots.release()
                }
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        socket?.close()
        socket = null
        workers?.shutdownNow()
        workers = null
    }

    /** The link the QR code shows, or null while the server isn't running. */
    fun url(): String? = if (socket == null) null else host?.let { "http://$it:$port/?t=$token" }

    private fun handle(client: Socket) {
        val c = client
        c.soTimeout = 10_000
        // Overall time limits, so a device dripping bytes can't hold one of the few slots for long.
        val headerDeadline = System.currentTimeMillis() + HEADER_BUDGET_MS
        val input = BufferedInputStream(c.getInputStream())
        val out = c.getOutputStream()

        val parts = (readLine(input, headerDeadline) ?: return).split(" ")
        if (parts.size != 3) return respond(out, 400, "text/plain", "Bad request")
        val (method, target) = parts
        val path = target.substringBefore('?')
        val code = target.substringAfter('?', "").split('&').firstOrNull { it.startsWith("t=") }?.removePrefix("t=") ?: ""
        // Check the code before reading anything else, so strangers can't make us buffer headers.
        if (!MessageDigest.isEqual(code.toByteArray(), token.toByteArray())) {
            return respond(out, 403, "text/plain", "This code has expired. Open the control panel on your TV and scan the QR code again.")
        }
        val headers = readHeaders(input, headerDeadline) ?: return respond(out, 431, "text/plain", "Request headers too large")

        when {
            method == "GET" && path == "/" -> {
                val page = context.assets.open("upload.html").bufferedReader().use { it.readText() }
                respond(out, 200, "text/html; charset=utf-8", page.replace("{{TOKEN}}", token))
            }
            method == "POST" && path == "/upload" ->
                receive(input, out, headers["content-length"]?.toLongOrNull(), System.currentTimeMillis() + BODY_BUDGET_MS)
            else -> respond(out, 404, "text/plain", "Not found")
        }
    }

    private fun receive(input: InputStream, out: OutputStream, length: Long?, deadline: Long) {
        if (length == null) return respond(out, 411, "text/plain", "Missing Content-Length")
        if (length <= 0 || length > MAX_UPLOAD_BYTES) {
            return respond(out, 413, "text/plain", "Photos must be under ${MAX_UPLOAD_BYTES / 1_000_000} MB")
        }
        val dir = wallpaperDir(context)
        if (dir.usableSpace < length + MIN_FREE_BYTES) return respond(out, 507, "text/plain", "The TV is low on storage")

        val tmp = File.createTempFile("upload", ".tmp", dir) // one per request, so two uploads can't collide
        try {
            var left = length
            tmp.outputStream().use { file ->
                val buf = ByteArray(64 * 1024)
                while (left > 0) {
                    if (System.currentTimeMillis() > deadline) throw IOException("upload too slow")
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    file.write(buf, 0, n)
                    left -= n
                }
            }
            if (left != 0L) return respond(out, 400, "text/plain", "The upload was cut off. Try again.")
            checkPhoto(tmp)?.let { problem -> return respond(out, 415, "text/plain", problem) }
            val dest = File(dir, "wp_${System.currentTimeMillis()}.jpg")
            if (!tmp.renameTo(dest)) return respond(out, 500, "text/plain", "Couldn't save the photo")
            keepNewestUploads(dir)
            respond(out, 200, "application/json", """{"ok":true}""")
            onWallpaper(dest)
        } finally {
            tmp.delete() // no-op after a successful rename
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        val status = STATUS[code] ?: "Error"
        out.write("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    companion object {
        private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
        private const val MAX_CLIENTS = 3
        private const val BACKLOG = 8
        private const val HEADER_BUDGET_MS = 10_000L
        private const val BODY_BUDGET_MS = 60_000L         // a 15 MB photo over slow Wi-Fi
        private const val MAX_HEADER_LINES = 50
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_UPLOAD_BYTES = 15_000_000L
        private const val MAX_PHOTO_SIDE = 4096          // the phone page sends 1920x1080; this is generous
        private const val MAX_UPLOADS = 20               // older uploads are deleted
        private const val MIN_FREE_BYTES = 200L * 1024 * 1024
        private val STATUS = mapOf(
            200 to "OK", 400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found", 411 to "Length Required",
            413 to "Payload Too Large", 415 to "Unsupported Media Type", 431 to "Request Header Fields Too Large",
            500 to "Internal Server Error", 507 to "Insufficient Storage",
        )

        fun wallpaperDir(context: Context): File = File(context.filesDir, "wallpapers").apply { mkdirs() }

        fun lanAddress(): String? = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress

        private fun newToken(): String = SecureRandom().let { rnd -> (1..8).map { ALPHABET[rnd.nextInt(ALPHABET.length)] }.joinToString("") }

        /** Private IPv4 ranges, link-local, unique-local IPv6 and loopback (adb port forwarding). */
        internal fun isLocal(address: InetAddress): Boolean = address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || (address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC)

        /** Null if [file] is a JPEG Zen can show; otherwise a message for the phone. */
        internal fun checkPhoto(file: File): String? {
            val magic = file.inputStream().use { s -> ByteArray(3).also { s.read(it) } }
            if (magic[0] != 0xFF.toByte() || magic[1] != 0xD8.toByte() || magic[2] != 0xFF.toByte()) return "That wasn't a JPEG photo"
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return "That photo couldn't be read"
            if (bounds.outWidth > MAX_PHOTO_SIDE || bounds.outHeight > MAX_PHOTO_SIDE) return "That photo is too large"
            return null
        }

        private fun keepNewestUploads(dir: File) {
            dir.listFiles { f -> f.name.startsWith("wp_") && f.name.endsWith(".jpg") }.orEmpty()
                .sortedByDescending { it.name }
                .drop(MAX_UPLOADS)
                .forEach { it.delete() }
        }

        /** One CRLF-terminated line, at most 8 KB, before [deadline]. Null at end of stream. */
        internal fun readLine(input: InputStream, deadline: Long): String? {
            val sb = StringBuilder()
            while (sb.length < 8192) {
                if (System.currentTimeMillis() > deadline) throw IOException("request too slow")
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
            }
            throw IOException("line too long")
        }

        /** Headers until the blank line, lower-cased names. Null if there are too many or they're too big. */
        internal fun readHeaders(input: InputStream, deadline: Long): Map<String, String>? {
            val headers = HashMap<String, String>()
            var bytes = 0
            repeat(MAX_HEADER_LINES) {
                val line = readLine(input, deadline) ?: return headers
                if (line.isEmpty()) return headers
                bytes += line.length
                if (bytes > MAX_HEADER_BYTES) return null
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            return null
        }
    }
}
