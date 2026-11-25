package melox.download

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import melox.plugin.PluginHttpBroker
import okhttp3.Headers
import kotlin.test.*

class PluginDownloadTransferTest {
    private fun response(status: Int, text: String, etag: String = "\"v1\"", range: String? = null,
        input: InputStream = ByteArrayInputStream(text.toByteArray()), length: Long = text.length.toLong()) =
        PluginHttpBroker.MediaResponse(status, Headers.Builder().add("ETag", etag).apply {
            range?.let { add("Content-Range", it) }
        }.build(), length, input)

    private fun interrupt(file: java.io.File) {
        var sent = false
        val input = object : InputStream() {
            override fun read(): Int = error("bulk only")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (sent) throw IOException("interrupted")
                sent = true
                "abc".toByteArray().copyInto(bytes, offset)
                return 3
            }
        }
        assertFailsWith<IOException> {
            PluginDownloadTransfer.run(file, "scope", { true }) { _, _ -> response(200, "abcdef", input = input) }
        }
        assertEquals("abc", file.readText())
    }

    @Test fun exactRangeResumesAndIgnoredRangeReplaces() {
        val root = Files.createTempDirectory("plugin-transfer").toFile()
        try {
            val file = root.resolve("track.part")
            interrupt(file)
            PluginDownloadTransfer.run(file, "scope", { true }) { offset, etag ->
                assertEquals(3L, offset)
                assertEquals("\"v1\"", etag)
                response(206, "def", range = "bytes 3-5/6")
            }
            assertEquals("abcdef", file.readText())
            interrupt(file)
            PluginDownloadTransfer.run(file, "scope", { true }) { _, _ -> response(200, "replacement", "\"v2\"") }
            assertEquals("replacement", file.readText())
            assertFalse(root.resolve("track.part.resume.json").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun mismatchedRangeIsDiscardedAndChangedScopeStartsOver() {
        val root = Files.createTempDirectory("plugin-transfer-invalid").toFile()
        try {
            val file = root.resolve("track.part")
            interrupt(file)
            assertFailsWith<IllegalArgumentException> {
                PluginDownloadTransfer.run(file, "scope", { true }) { _, _ -> response(206, "def", range = "bytes 2-4/6") }
            }
            assertFalse(file.exists())
            interrupt(file)
            PluginDownloadTransfer.run(file, "other", { true }) { offset, etag ->
                assertNull(offset); assertNull(etag)
                response(200, "new")
            }
            assertEquals("new", file.readText())
        } finally { root.deleteRecursively() }
    }
}
