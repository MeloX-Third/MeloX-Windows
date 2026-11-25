package melox.lyrics

import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import kotlin.test.*

class QrcDecompressionLimitTest {
    private fun inflate(payload: ByteArray): ByteArray {
        val compressed = ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { it.write(payload) }
        }.toByteArray()
        val method = QQMusicMeiQrcDecoder::class.java.getDeclaredMethod("decompressZlib", ByteArray::class.java)
        method.isAccessible = true
        return method.invoke(QQMusicMeiQrcDecoder, compressed) as ByteArray
    }

    @Test fun normalLyricsAndExactLimitDecodeButOversizedInflationFails() {
        val normal = "[1000,1000]你好(1000,1000)".toByteArray()
        assertContentEquals(normal, inflate(normal))
        val maximum = ByteArray(4 * 1024 * 1024) { 65 }
        assertContentEquals(maximum, inflate(maximum))
        val failure = assertFailsWith<java.lang.reflect.InvocationTargetException> { inflate(maximum + byteArrayOf(65)) }
        assertIs<IllegalArgumentException>(failure.cause)
    }
}
