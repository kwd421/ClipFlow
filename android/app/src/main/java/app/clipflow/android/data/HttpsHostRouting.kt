package app.clipflow.android.data

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** HTTPS Host routing for endpoints whose original SNI is terminated in transit. */
internal object HttpsHostRouting {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private val tlsHostCache = ConcurrentHashMap<String, String>()

    fun connect(
        originalUrl: String,
        configure: (HttpsURLConnection) -> Unit,
    ): HttpsURLConnection {
        val original = URL(originalUrl)
        require(original.protocol.equals("https", true)) { "HTTPS URL만 지원합니다." }
        val port = original.port.takeIf { it > 0 } ?: 443
        val hostHeader = if (port == 443) original.host else "${original.host}:$port"
        var lastError: Throwable? = null

        for (address in InetAddress.getAllByName(original.host)) {
            val ip = requireNotNull(address.hostAddress).substringBefore('%')
            try {
                val tlsHost = tlsHostCache.getOrPut("$ip:$port") {
                    discoverTlsHost(address, port, original.host)
                }
                val routedUrl = URL(original.protocol, ip, port, original.file)
                val connection = routedUrl.openConnection() as HttpsURLConnection
                connection.instanceFollowRedirects = false
                connection.sslSocketFactory = RoutedSslSocketFactory(address, tlsHost)
                connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
                    HttpsURLConnection.getDefaultHostnameVerifier().verify(tlsHost, session)
                }
                connection.setRequestProperty("Host", hostHeader)
                configure(connection)
                connection.connect()
                connection.responseCode
                return connection
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("대체 HTTPS 경로를 만들지 못했습니다.")
    }

    fun verifyDefaultCertificate(address: InetAddress, port: Int, expectedHost: String) {
        openWithoutSni(address, port).use { ssl ->
            ssl.startHandshake()
            check(HttpsURLConnection.getDefaultHostnameVerifier().verify(expectedHost, ssl.session)) {
                "SNI 없는 서버 인증서가 원래 호스트와 일치하지 않습니다."
            }
        }
    }

    private fun discoverTlsHost(address: InetAddress, port: Int, originalHost: String): String {
        return openWithoutSni(address, port).use {
            it.startHandshake()
            val certificate = it.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: error("서버 인증서를 확인하지 못했습니다.")
            val names = certificate.subjectAlternativeNames.orEmpty()
                .mapNotNull { entry ->
                    if (entry.size >= 2 && entry[0] == 2) entry[1] as? String else null
                }
            chooseTlsHost(names, originalHost)
                ?: error("대체 가능한 인증서 DNS 이름을 찾지 못했습니다.")
        }
    }

    private fun openWithoutSni(address: InetAddress, port: Int): SSLSocket {
        val plain = Socket().apply {
            connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
        }
        return ((SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(plain, address.hostAddress, port, true) as SSLSocket).apply {
            soTimeout = CONNECT_TIMEOUT_MS
            sslParameters = sslParameters.apply { serverNames = emptyList() }
        }
    }

    internal fun chooseTlsHost(names: List<String>, originalHost: String): String? {
        names.firstOrNull { '*' !in it && !it.equals(originalHost, true) }?.let { return it }
        return names.firstNotNullOfOrNull { name ->
            if (name.startsWith("*.") && name.length > 2) "clipflow.${name.removePrefix("*.")}" else null
        }
    }

    private class RoutedSslSocketFactory(
        private val address: InetAddress,
        private val tlsHost: String,
    ) : SSLSocketFactory() {
        private val delegate = SSLSocketFactory.getDefault() as SSLSocketFactory

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(): Socket = configure(delegate.createSocket() as SSLSocket)

        override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            configure(delegate.createSocket(socket, tlsHost, port, autoClose) as SSLSocket)

        override fun createSocket(host: String, port: Int): Socket = connected(port)

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int,
        ): Socket = connected(port, localHost, localPort)

        override fun createSocket(host: InetAddress, port: Int): Socket = connected(port)

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int,
        ): Socket = connected(port, localAddress, localPort)

        private fun connected(port: Int, localAddress: InetAddress? = null, localPort: Int = 0): Socket {
            val plain = Socket()
            if (localAddress != null) plain.bind(InetSocketAddress(localAddress, localPort))
            plain.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            return configure(delegate.createSocket(plain, tlsHost, port, true) as SSLSocket)
        }

        private fun configure(socket: SSLSocket): SSLSocket = socket.apply {
            sslParameters = sslParameters.apply {
                serverNames = listOf(SNIHostName(tlsHost))
            }
        }
    }
}
