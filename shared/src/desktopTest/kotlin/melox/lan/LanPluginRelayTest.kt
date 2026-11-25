package melox.lan

import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import melox.playback.DesktopPlaybackRequest
import melox.plugin.PluginPlaybackInputs
import kotlin.test.*

class LanPluginRelayTest {
    @Test fun privateMediaIsRelayedWithoutLeakingInputAndRejectsRevokedSource() {
        val loopback = InetAddress.getLoopbackAddress()
        val track = LanTrack("plugin:example.relay", "audio", "Track", "Artist")
        val bytes = ByteArray(65536) { (it % 127).toByte() }
        var current = true
        val closed = AtomicInteger()
        val input = PluginPlaybackInputs.register(null, { current }) {
            object : ByteArrayInputStream(bytes) {
                override fun close() { closed.incrementAndGet(); super.close() }
            }
        }
        LanAudioRelay(loopback, loopback, listOf(track), { DesktopPlaybackRequest(input) },
            LanRelayPolicy(contentType = "audio/wav", dlnaHeaders = true)).use { relay ->
            val url = relay.resourceUrl(track.id)
            assertFalse(url.contains(input))
            val client = okhttp3.OkHttpClient()
            try {
                client.newCall(okhttp3.Request.Builder().url(url).header("Range", "bytes=20-30").build()).execute().use { response ->
                    assertEquals(200, response.code)
                    assertEquals("none", response.header("Accept-Ranges"))
                    assertEquals("DLNA.ORG_OP=00;DLNA.ORG_CI=0", response.header("contentFeatures.dlna.org"))
                    assertContentEquals(bytes, response.body.bytes())
                }
                client.newCall(okhttp3.Request.Builder().url(url).head().build()).execute().use { response ->
                    assertEquals(200, response.code)
                    assertEquals(0, response.body.bytes().size)
                }
                assertEquals(2, closed.get())
                current = false
                client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                    assertEquals(502, response.code)
                    assertEquals(0, response.body.bytes().size)
                }
                assertEquals(2, closed.get())
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }
}
