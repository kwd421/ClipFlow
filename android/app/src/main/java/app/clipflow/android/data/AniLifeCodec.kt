package app.clipflow.android.data

import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AniLife media API crypto:
 * 1) body is LZ-String compressToUTF16
 * 2) decompressed payload is zipson array of 5 strings
 * 3) AES-GCM(ciphertext||tag, iv) + HMAC-SHA256 verify (300s window)
 */
internal object AniLifeCodec {
    // Hex material is split+base64-obfuscated in the Nuxt client (Ke / ze arrays).
    private val AES_KEY: ByteArray = hexToBytes(
        "5f1d6b5cf2b6e5a236aa6352c5e688bc86c257a9b61b183c9926613a90356a48",
    )
    private val HMAC_KEY: ByteArray = hexToBytes(
        "3f8d7b6a4c2e1d9f8a7b6c5d4e3f2a1b0c9d8e7f6a5b4c3d2e1f0a9b8c7d6e5f",
    )
    private const val MAX_AGE_MS = 300_000L

    fun decryptMediaBody(compressedUtf16: String): JSONObject {
        val decompressed = LzString.decompressFromUTF16(compressedUtf16)
            ?: error("AniLife 응답 압축 해제에 실패했습니다.")
        val parts = ZipsonStringArray.parse(decompressed)
        if (parts.size < 5) error("AniLife 암호화 페이로드 형식이 올바르지 않습니다.")

        val cipher = b64(parts[0])
        val iv = b64(parts[1])
        val tag = b64(parts[2])
        val tsRaw = String(b64(parts[3]), StandardCharsets.UTF_8)
        val sigHex = parts[4]
        val ts = tsRaw.toLongOrNull() ?: error("AniLife 타임스탬프가 올바르지 않습니다.")
        if (System.currentTimeMillis() - ts > MAX_AGE_MS) {
            error("AniLife 미디어 토큰이 만료되었습니다. 다시 분석해 주세요.")
        }

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(HMAC_KEY, "HmacSHA256"))
        mac.update(cipher)
        mac.update(iv)
        mac.update(tag)
        mac.update(tsRaw.toByteArray(StandardCharsets.UTF_8))
        val expected = mac.doFinal().joinToString("") { "%02x".format(it) }
        if (!expected.equals(sigHex, ignoreCase = true)) {
            error("AniLife 응답 서명 검증에 실패했습니다.")
        }

        val gcm = Cipher.getInstance("AES/GCM/NoPadding")
        gcm.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY, "AES"), GCMParameterSpec(128, iv))
        val plain = gcm.doFinal(cipher + tag)
        return JSONObject(String(plain, StandardCharsets.UTF_8))
    }

    fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun b64(value: String): ByteArray = Base64.decode(value, Base64.DEFAULT)

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            out[i / 2] = ((hex[i].digitToInt(16) shl 4) + hex[i + 1].digitToInt(16)).toByte()
            i += 2
        }
        return out
    }
}

/** Minimal zipson parser for `|¨s0¨¨s1¨...¨sN¨÷` envelopes. */
internal object ZipsonStringArray {
    private const val ARRAY_START = '|'
    private const val ARRAY_END = '÷'
    private const val STRING = '¨'
    private const val ESCAPE = '\\'

    fun parse(data: String): List<String> {
        if (data.isEmpty() || data[0] != ARRAY_START) error("zipson array expected")
        val out = ArrayList<String>(5)
        var i = 1
        while (i < data.length) {
            val c = data[i]
            if (c == ARRAY_END) break
            if (c != STRING) error("zipson string token expected at $i")
            i += 1
            val start = i
            var escaped = false
            while (i < data.length) {
                val ch = data[i]
                if (escaped) {
                    escaped = false
                    i += 1
                    continue
                }
                if (ch == ESCAPE) {
                    escaped = true
                    i += 1
                    continue
                }
                if (ch == STRING) break
                i += 1
            }
            if (i >= data.length) error("unterminated zipson string")
            out += unescape(data.substring(start, i))
            i += 1
        }
        return out
    }

    private fun unescape(value: String): String {
        if (!value.contains(ESCAPE)) return value
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == ESCAPE && i + 1 < value.length) {
                sb.append(value[i + 1])
                i += 2
            } else {
                sb.append(ch)
                i += 1
            }
        }
        return sb.toString()
    }
}

/** LZ-String decompressFromUTF16 (pieroxy/lz-string). */
internal object LzString {
    fun decompressFromUTF16(compressed: String?): String? {
        if (compressed == null) return ""
        if (compressed.isEmpty()) return null
        val length = compressed.length
        val resetValue = 16384
        fun getNextValue(index: Int): Int = compressed[index].code - 32

        val dictionary = HashMap<Int, String>()
        var enlargeIn = 4
        var dictSize = 4
        var numBits = 3
        val result = StringBuilder()
        var dataVal = getNextValue(0)
        var dataPosition = resetValue
        var dataIndex = 1
        for (i in 0 until 3) dictionary[i] = i.toChar().toString()

        fun readBits(nbits: Int): Int {
            var bits = 0
            var power = 1
            val maxpower = 1 shl nbits
            while (power != maxpower) {
                val resb = dataVal and dataPosition
                dataPosition = dataPosition shr 1
                if (dataPosition == 0) {
                    dataPosition = resetValue
                    dataVal = getNextValue(dataIndex++)
                }
                if (resb > 0) bits = bits or power
                power = power shl 1
            }
            return bits
        }

        val first = when (val next = readBits(2)) {
            0 -> readBits(8).toChar().toString()
            1 -> readBits(16).toChar().toString()
            2 -> return ""
            else -> return null
        }
        dictionary[3] = first
        var w = first
        result.append(first)

        while (true) {
            if (dataIndex > length) return ""
            var cCode = readBits(numBits)
            when (cCode) {
                0 -> {
                    dictionary[dictSize] = readBits(8).toChar().toString()
                    cCode = dictSize
                    dictSize++
                    enlargeIn--
                }
                1 -> {
                    dictionary[dictSize] = readBits(16).toChar().toString()
                    cCode = dictSize
                    dictSize++
                    enlargeIn--
                }
                2 -> return result.toString()
            }
            if (enlargeIn == 0) {
                enlargeIn = 1 shl numBits
                numBits++
            }
            val entry = when {
                dictionary.containsKey(cCode) -> dictionary[cCode]!!
                cCode == dictSize -> w + w[0]
                else -> return null
            }
            result.append(entry)
            dictionary[dictSize] = w + entry[0]
            dictSize++
            enlargeIn--
            w = entry
            if (enlargeIn == 0) {
                enlargeIn = 1 shl numBits
                numBits++
            }
        }
    }
}
