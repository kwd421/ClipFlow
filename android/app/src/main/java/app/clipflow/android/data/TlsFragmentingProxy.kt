package app.clipflow.android.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.IDN
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/**
 * Loopback CONNECT proxy that splits the TLS ClientHello around the SNI value.
 *
 * Without another VPN it can split the handshake across TLS records. When a VPN
 * is active it preserves the original TLS record and only splits the TCP writes;
 * some filtering VPNs crash when a ClientHello spans multiple TLS records.
 */
internal object TlsFragmentingProxy {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val HANDSHAKE_TIMEOUT_MS = 15_000
    private const val SPLIT_DELAY_MS = 20L
    private const val MAX_HEADER_BYTES = 64 * 1024
    private const val TLS_HANDSHAKE = 22
    @Volatile
    private var server: ServerSocket? = null
    @Volatile
    private var appContext: Context? = null

    @Synchronized
    fun endpoint(context: Context): String {
        appContext = context.applicationContext
        server?.takeUnless { it.isClosed }?.let { return "http://127.0.0.1:${it.localPort}" }
        val created = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress("127.0.0.1", 0), 32)
        }
        server = created
        thread(name = "clipflow-tls-proxy", isDaemon = true) { acceptLoop(created) }
        return "http://127.0.0.1:${created.localPort}"
    }

    fun isAppUsingVpn(context: Context): Boolean = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(true)

    fun isConnectionTermination(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty().lowercase()
            if (
                "unexpected_eof" in message ||
                "eof occurred in violation" in message ||
                "connection_closed" in message ||
                "connection closed" in message ||
                "connection reset" in message ||
                "timed out" in message ||
                "ssl_error_syscall" in message ||
                "remote end closed connection" in message ||
                "remote host terminated the handshake" in message
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: continue
            thread(name = "clipflow-tls-proxy-client", isDaemon = true) { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val header = readHttpHeader(client.getInputStream())
            val requestLine = header.toString(StandardCharsets.ISO_8859_1)
                .lineSequence()
                .firstOrNull()
                .orEmpty()
            val parts = requestLine.split(' ', limit = 3)
            if (parts.size < 2 || !parts[0].equals("CONNECT", ignoreCase = true)) {
                client.getOutputStream().write(
                    "HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\n\r\n"
                        .toByteArray(StandardCharsets.ISO_8859_1),
                )
                return
            }

            val (host, port) = parseConnectTarget(parts[1])
            val vpnActive = appContext?.let(::isAppUsingVpn) != false
            upstream = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            }
            client.getOutputStream().apply {
                write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                flush()
            }

            val firstRecord = readTlsRecord(client.getInputStream())
            val upstreamOut = upstream.getOutputStream()
            val fragments = if (vpnActive) {
                splitClientHelloTcp(firstRecord, host)
            } else {
                fragmentClientHelloRecord(firstRecord, host)
            }
            if (fragments == null) {
                upstreamOut.write(firstRecord)
            } else {
                upstreamOut.write(fragments.first)
                upstreamOut.flush()
                Thread.sleep(SPLIT_DELAY_MS)
                upstreamOut.write(fragments.second)
            }
            upstreamOut.flush()
            client.soTimeout = 0

            val remote = upstream
            val reverse = thread(name = "clipflow-tls-proxy-reverse", isDaemon = true) {
                relay(remote.getInputStream(), client)
            }
            relay(client.getInputStream(), remote)
            reverse.join(1_000)
        } catch (_: Throwable) {
        } finally {
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
    }

    private fun relay(input: InputStream, outputSocket: Socket) {
        val output = outputSocket.getOutputStream()
        val buffer = ByteArray(64 * 1024)
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                output.flush()
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { outputSocket.shutdownOutput() }
        }
    }

    private fun readHttpHeader(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        var matched = 0
        while (output.size() < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) error("프록시 요청이 중간에 종료되었습니다.")
            output.write(value)
            matched = when {
                matched == 0 && value == '\r'.code -> 1
                matched == 1 && value == '\n'.code -> 2
                matched == 2 && value == '\r'.code -> 3
                matched == 3 && value == '\n'.code -> return output.toByteArray()
                value == '\r'.code -> 1
                else -> 0
            }
        }
        error("프록시 요청 헤더가 너무 큽니다.")
    }

    private fun readTlsRecord(input: InputStream): ByteArray {
        val header = input.readExactly(5)
        val length = ((header[3].toInt() and 0xff) shl 8) or (header[4].toInt() and 0xff)
        if (length <= 0 || length > 0xffff) error("유효하지 않은 TLS 레코드입니다.")
        return header + input.readExactly(length)
    }

    private fun parseConnectTarget(target: String): Pair<String, Int> {
        if (target.startsWith('[')) {
            val end = target.indexOf(']')
            require(end > 1) { "유효하지 않은 CONNECT 주소입니다." }
            val port = target.substring(end + 1).removePrefix(":").toIntOrNull() ?: 443
            return target.substring(1, end) to port
        }
        val separator = target.lastIndexOf(':')
        if (separator <= 0) return target to 443
        return target.substring(0, separator) to (target.substring(separator + 1).toIntOrNull() ?: 443)
    }

    internal fun fragmentClientHelloRecord(record: ByteArray, host: String): Pair<ByteArray, ByteArray>? {
        val splitAt = clientHelloSniSplitOffset(record, host) ?: return null
        val body = record.copyOfRange(5, record.size)
        val bodySplitAt = splitAt - 5
        val version = record.copyOfRange(1, 3)
        return tlsRecord(version, body.copyOfRange(0, bodySplitAt)) to
            tlsRecord(version, body.copyOfRange(bodySplitAt, body.size))
    }

    internal fun splitClientHelloTcp(record: ByteArray, host: String): Pair<ByteArray, ByteArray>? {
        val splitAt = clientHelloSniSplitOffset(record, host) ?: return null
        return record.copyOfRange(0, splitAt) to record.copyOfRange(splitAt, record.size)
    }

    private fun clientHelloSniSplitOffset(record: ByteArray, host: String): Int? {
        if (record.size < 7 || (record[0].toInt() and 0xff) != TLS_HANDSHAKE) return null
        val declaredLength = ((record[3].toInt() and 0xff) shl 8) or (record[4].toInt() and 0xff)
        if (declaredLength != record.size - 5) return null
        val body = record.copyOfRange(5, record.size)
        val hostBytes = IDN.toASCII(host).lowercase().toByteArray(StandardCharsets.US_ASCII)
        val hostOffset = body.indexOf(hostBytes)
        if (hostOffset < 0) return null
        val bodySplitAt = hostOffset + (hostBytes.size / 2).coerceAtLeast(1)
        if (bodySplitAt <= 0 || bodySplitAt >= body.size) return null
        return 5 + bodySplitAt
    }

    private fun tlsRecord(version: ByteArray, body: ByteArray): ByteArray = byteArrayOf(
        TLS_HANDSHAKE.toByte(),
        version[0],
        version[1],
        ((body.size ushr 8) and 0xff).toByte(),
        (body.size and 0xff).toByte(),
    ) + body

    private fun InputStream.readExactly(size: Int): ByteArray {
        val output = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(output, offset, size - offset)
            if (count < 0) error("연결이 중간에 종료되었습니다.")
            offset += count
        }
        return output
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > size) return -1
        for (start in 0..size - needle.size) {
            var matches = true
            for (offset in needle.indices) {
                val left = this[start + offset].toInt().toChar().lowercaseChar()
                val right = needle[offset].toInt().toChar().lowercaseChar()
                if (left != right) {
                    matches = false
                    break
                }
            }
            if (matches) return start
        }
        return -1
    }
}
