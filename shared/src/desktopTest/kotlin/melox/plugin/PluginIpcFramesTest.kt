package melox.plugin

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.CharacterCodingException
import kotlin.test.*

class PluginIpcFramesTest {
    @Test fun consecutiveFramesPreserveUnicodeAndBoundaries() {
        val output = ByteArrayOutputStream()
        val messages = listOf("""{"value":"中文😀\\n"}""", """{"ok":true}""")
        messages.forEach { PluginIpcFrames.write(output, it) }
        val input = ByteArrayInputStream(output.toByteArray())
        messages.forEach { assertEquals(it, PluginIpcFrames.read(input)) }
        assertFailsWith<EOFException> { PluginIpcFrames.read(input) }
    }

    @Test fun invalidLengthsRejectBeforeReadingPayload() {
        for (length in listOf(Int.MIN_VALUE, -1, 0, PluginIpcFrames.MAX_BYTES + 1, Int.MAX_VALUE)) {
            val output = ByteArrayOutputStream()
            DataOutputStream(output).writeInt(length)
            assertFailsWith<IllegalArgumentException> { PluginIpcFrames.read(ByteArrayInputStream(output.toByteArray())) }
        }
    }

    @Test fun truncatedAndMalformedPayloadsFailClosed() {
        fun frame(length: Int, bytes: ByteArray) = ByteArrayOutputStream().apply {
            DataOutputStream(this).apply { writeInt(length); write(bytes) }
        }.toByteArray().inputStream()
        assertFailsWith<EOFException> { PluginIpcFrames.read(frame(10, byteArrayOf(1))) }
        assertFailsWith<CharacterCodingException> { PluginIpcFrames.read(frame(2, byteArrayOf(0xc3.toByte(), 0x28))) }
        assertFailsWith<IllegalArgumentException> { PluginIpcFrames.read(frame(2, "{{".toByteArray())) }
        val output = ByteArrayOutputStream()
        assertFailsWith<IllegalArgumentException> { PluginIpcFrames.write(output, "[]", 1) }
        assertEquals(0, output.size())
    }
}
