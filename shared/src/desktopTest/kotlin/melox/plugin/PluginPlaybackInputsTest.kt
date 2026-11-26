package melox.plugin

import java.io.ByteArrayInputStream
import kotlin.test.*

class PluginPlaybackInputsTest {
    @Test fun rejectedMediaRefreshesOnceAndRevocationRejectsLateStream() {
        for (status in listOf(401, 403, 410)) {
            var opens = 0
            var refreshes = 0
            PluginPlaybackInputs.openWithRefresh({ true }, {
                opens++; throw PluginMediaHttpException(status)
            }, { refreshes++; ByteArrayInputStream(byteArrayOf(42)) }).use { assertEquals(42, it.read()) }
            assertEquals(1, opens)
            assertEquals(1, refreshes)
            assertFailsWith<PluginMediaHttpException> {
                PluginPlaybackInputs.openWithRefresh({ true }, { throw PluginMediaHttpException(status) }, {
                    refreshes++; throw PluginMediaHttpException(status)
                })
            }
            assertEquals(2, refreshes)
        }
        var refreshes = 0
        assertFailsWith<PluginMediaHttpException> {
            PluginPlaybackInputs.openWithRefresh({ true }, { throw PluginMediaHttpException(500) }, {
                refreshes++; ByteArrayInputStream(byteArrayOf())
            })
        }
        assertEquals(0, refreshes)
        var current = true
        var closed = false
        assertFailsWith<IllegalStateException> {
            PluginPlaybackInputs.openWithRefresh({ current }, { throw PluginMediaHttpException(403) }, {
                current = false
                object : ByteArrayInputStream(byteArrayOf()) { override fun close() { closed = true } }
            })
        }
        assertTrue(closed)
        assertFailsWith<IllegalStateException> {
            PluginPlaybackInputs.openWithRefresh({ false }, { error("must not open") }, null)
        }
    }
    @Test fun rejectedStatusCallbackReportsOnlyRetryableAuthAndGoneResponses() {
        val reported = mutableListOf<Int>()
        for (status in listOf(401, 403, 410, 500)) {
            runCatching {
                PluginPlaybackInputs.openWithRefresh({ true }, { throw PluginMediaHttpException(status) }, {
                    ByteArrayInputStream(byteArrayOf())
                }) { reported += it }
            }
        }
        assertEquals(listOf(401, 403, 410), reported)
    }

    @Test fun opaqueInputReopensAndRejectsRevocationAndExpiry() {
        var current = true
        var opens = 0
        val uri = PluginPlaybackInputs.register(null, { current }) {
            opens++
            ByteArrayInputStream(byteArrayOf(1, 2, 3))
        }
        assertTrue(PluginPlaybackInputs.isPrivate(uri))
        repeat(2) { assertContentEquals(byteArrayOf(1, 2, 3), PluginPlaybackInputs.open(uri).use { it.readBytes() }) }
        assertEquals(2, opens)
        PluginPlaybackInputs.open(uri).use { stream ->
            current = false
            assertFailsWith<IllegalStateException> { stream.read() }
        }
        assertFailsWith<IllegalStateException> { PluginPlaybackInputs.open(uri) }
        val expired = PluginPlaybackInputs.register(0, { true }) { error("Must not open") }
        assertFailsWith<IllegalStateException> { PluginPlaybackInputs.open(expired) }
    }

    @Test fun decoderCommandsExposeOnlyPipeForPrivateInput() {
        val uri = PluginPlaybackInputs.register(null, { true }) { ByteArrayInputStream(byteArrayOf()) }
        val args = melox.player.AudioPlayer.decoderArgsFor(uri, mapOf("Authorization" to "secret"), 1000)
        assertTrue("pipe:0" in args)
        assertFalse(args.any { it.contains(uri) || it.contains("secret") })
        assertEquals("pipe", args[args.indexOf("-protocol_whitelist") + 1])
        assertTrue(args.indexOf("-ss") > args.indexOf("-i"))
    }

    @Test fun brokerSocketFeedsRealDecoderThroughPrivatePipe() {
        val frames = 22_050
        val data = java.nio.ByteBuffer.allocate(44 + frames * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(2).putInt(44_100).putInt(176_400).putShort(4).putShort(16)
            .put("data".toByteArray()).putInt(frames * 4)
        repeat(frames) { data.putShort(1000).putShort(1000) }
        val manifest = PluginManifest(requireNotNull(melox.music.model.SourceId.parse("plugin:test.decoder.socket")),
            "Socket", "1.0.0", 1, "index.js", emptySet(), setOf("api.example.com"))
        mockwebserver3.MockWebServer().use { server ->
            server.start()
            server.enqueue(mockwebserver3.MockResponse.Builder().body(okio.Buffer().write(data.array()))
                .addHeader("Content-Type", "audio/wav").build())
            PluginHttpBroker(manifest, internalTransport = okhttp3.Interceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/audio")).build())
            }, internalDns = okhttp3.Dns { listOf(java.net.InetAddress.getLoopbackAddress()) }).use { broker ->
                val uri = PluginPlaybackInputs.register(null, { true }) {
                    broker.openMedia("https://api.example.com/audio", mapOf("X-Media" to "private"))
                }
                melox.player.FfmpegPcmDecoder(uri).use { decoder ->
                    val pcm = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = decoder.read(buffer)
                        if (count <= 0) break
                        pcm.write(buffer, 0, count)
                    }
                    decoder.verifyExit()
                    assertContentEquals(data.array().copyOfRange(44, data.capacity()), pcm.toByteArray())
                }
                assertEquals("private", assertNotNull(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)).headers["X-Media"])
            }
        }
    }
}
