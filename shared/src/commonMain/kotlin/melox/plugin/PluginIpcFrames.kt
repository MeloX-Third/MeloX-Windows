package melox.plugin

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Length-delimited UTF-8 frames; reject allocation and encoding abuse before JSON parsing. */
internal object PluginIpcFrames {
    const val MAX_BYTES = 128 * 1024
    // A 128 KiB script may expand sixfold when JSON-escaped, plus bounded args/account.
    const val MAX_START_BYTES = 1024 * 1024

    fun read(input: InputStream, maxBytes: Int = MAX_BYTES): String {
        require(maxBytes in 1..8 * 1024 * 1024)
        val stream = DataInputStream(input)
        val length = stream.readInt()
        require(length in 1..maxBytes) { "INVALID_PLUGIN_IPC" }
        val bytes = ByteArray(length)
        stream.readFully(bytes)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        PluginJson.check(text, maxBytes)
        return text
    }

    fun write(output: OutputStream, text: String, maxBytes: Int = MAX_BYTES) {
        require(maxBytes in 1..8 * 1024 * 1024)
        PluginJson.check(text, maxBytes)
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty()) { "INVALID_PLUGIN_IPC" }
        DataOutputStream(output).apply { writeInt(bytes.size); write(bytes); flush() }
    }
}
