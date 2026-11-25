package melox.plugin

import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import melox.music.model.SourceId
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.Interceptor
import kotlin.test.*

class PluginMediaSocketTest {
    @Test fun socketBodyStreamsAndClosingBrokerInterruptsInflightRead() {
        val manifest = PluginManifest(requireNotNull(SourceId.parse("plugin:example.socket")), "Socket", "1.0.0", 1,
            "index.js", emptySet(), setOf("api.example.com"))
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("progressive-audio").addHeader("Content-Type", "audio/mpeg").build())
            // Send headers, then hold the body so cancellation must interrupt an actual socket read.
            server.enqueue(MockResponse.Builder().body("delayed-audio").bodyDelay(30, TimeUnit.SECONDS)
                .addHeader("Content-Type", "audio/mpeg").build())
            val executor = Executors.newSingleThreadExecutor()
            try {
                PluginHttpBroker(manifest, internalTransport = Interceptor { chain ->
                    chain.proceed(chain.request().newBuilder().url(server.url("/audio")).build())
                }, internalDns = Dns { listOf(InetAddress.getLoopbackAddress()) }).use { broker ->
                    assertEquals("progressive-audio", broker.openMedia("https://api.example.com/audio").use { it.readBytes().toString(Charsets.UTF_8) })
                    broker.openMedia("https://api.example.com/audio").use { stream ->
                        val started = java.util.concurrent.CountDownLatch(1)
                        val reader = java.util.concurrent.atomic.AtomicReference<Thread>()
                        val result = executor.submit<Boolean> {
                            reader.set(Thread.currentThread())
                            started.countDown()
                            try { stream.read(); false } catch (_: Exception) { true }
                        }
                        assertTrue(started.await(2, TimeUnit.SECONDS))
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                        var blockedOnSocket = false
                        while (System.nanoTime() < deadline && !result.isDone) {
                            blockedOnSocket = reader.get().stackTrace.any {
                                it.className == "sun.nio.ch.SocketDispatcher" && it.methodName.startsWith("read") ||
                                    it.className == "sun.nio.ch.NioSocketImpl" && it.methodName == "park"
                            }
                            if (blockedOnSocket) break
                            Thread.yield()
                        }
                        assertTrue(blockedOnSocket, "reader never entered a blocking socket read")
                        broker.close()
                        assertTrue(result.get(3, TimeUnit.SECONDS), "cancelled read returned body bytes")
                    }
                }
                assertEquals(2, server.requestCount)
            } finally { executor.shutdownNow() }
        }
    }
}
