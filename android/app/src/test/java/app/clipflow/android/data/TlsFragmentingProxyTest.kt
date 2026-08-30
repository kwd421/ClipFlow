package app.clipflow.android.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsFragmentingProxyTest {
    @Test
    fun fragmentsClientHelloInsideSniAndPreservesHandshakeBytes() {
        val host = "video.example.com"
        val body = byteArrayOf(1, 0, 0, 32, 3, 3, 7, 8) +
            host.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(9, 10, 11, 12)
        val record = byteArrayOf(
            22,
            3,
            1,
            ((body.size ushr 8) and 0xff).toByte(),
            (body.size and 0xff).toByte(),
        ) + body

        val fragments = TlsFragmentingProxy.fragmentClientHelloRecord(record, host)

        assertNotNull(fragments)
        val firstBody = fragments!!.first.copyOfRange(5, fragments.first.size)
        val secondBody = fragments.second.copyOfRange(5, fragments.second.size)
        assertArrayEquals(body, firstBody + secondBody)
        assertFalse(firstBody.toString(Charsets.ISO_8859_1).contains(host))
        assertFalse(secondBody.toString(Charsets.ISO_8859_1).contains(host))
        assertTrue(firstBody.size > 5)
        assertTrue(secondBody.isNotEmpty())
    }

    @Test
    fun ignoresNonHandshakeRecords() {
        val record = byteArrayOf(23, 3, 3, 0, 1, 0)

        assertTrue(TlsFragmentingProxy.fragmentClientHelloRecord(record, "example.com") == null)
    }

    @Test
    fun tcpSplitPreservesTheOriginalTlsRecord() {
        val host = "video.example.com"
        val body = byteArrayOf(1, 0, 0, 32, 3, 3, 7, 8) +
            host.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(9, 10, 11, 12)
        val record = byteArrayOf(
            22,
            3,
            1,
            ((body.size ushr 8) and 0xff).toByte(),
            (body.size and 0xff).toByte(),
        ) + body

        val parts = TlsFragmentingProxy.splitClientHelloTcp(record, host)

        assertNotNull(parts)
        assertArrayEquals(record, parts!!.first + parts.second)
        assertFalse(parts.first.toString(Charsets.ISO_8859_1).contains(host))
        assertFalse(parts.second.toString(Charsets.ISO_8859_1).contains(host))
    }

    @Test
    fun recognizesAbruptTlsTerminationOnly() {
        assertTrue(
            TlsFragmentingProxy.isConnectionTermination(
                IllegalStateException("[SSL: UNEXPECTED_EOF_WHILE_READING]"),
            ),
        )
        assertTrue(
            TlsFragmentingProxy.isConnectionTermination(
                IllegalStateException("Remote end closed connection without response"),
            ),
        )
        assertTrue(TlsFragmentingProxy.isConnectionTermination(IllegalStateException("TransportError('timed out')")))
        assertFalse(TlsFragmentingProxy.isConnectionTermination(IllegalStateException("HTTP Error 403")))
    }
}
