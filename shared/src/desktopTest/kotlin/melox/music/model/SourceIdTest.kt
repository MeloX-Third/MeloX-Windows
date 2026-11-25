package melox.music.model

import kotlin.test.*

class SourceIdTest {
    @Test fun resourceIdentityIncludesDynamicSourceAndPreservesBuiltInConstruction() {
        val builtIn = MusicResourceId(MusicSource.QQMusic, "same")
        assertEquals(MusicSource.QQMusic, builtIn.source)
        assertEquals(SourceId.fromBuiltIn(MusicSource.QQMusic), builtIn.sourceId)
        assertEquals(builtIn, MusicResourceId(builtIn.sourceId, "same"))
        val plugin = MusicResourceId(SourceId.parse("plugin:example.catalog")!!, "same")
        assertNotEquals(builtIn, plugin)
        assertNotEquals(plugin, MusicResourceId(SourceId.parse("plugin:other.catalog")!!, "same"))
        assertEquals(plugin, plugin.copy(value = "same"))
        assertFailsWith<IllegalStateException> { plugin.source }
        assertFailsWith<IllegalArgumentException> { MusicResourceId(plugin.sourceId, " ") }
    }

    @Test fun unknownSourcesNeverAcquireBuiltInIdentity() {
        MusicSource.entries.forEach {
            assertEquals(it.storageValue, SourceId.fromBuiltIn(it).value)
            assertEquals(it, SourceId.parse(it.storageValue)?.builtIn)
        }
        assertNull(SourceId.parse("plugin:example.catalog")?.builtIn)
        assertNull(SourceId.parse("future_provider")?.builtIn)
        assertNotEquals(SourceId.parse("plugin:example.catalog"), SourceId.parse("plugin:other.catalog"))
        listOf("plugin:Example.catalog", "plugin:example", "plugin:a..b", "plugin:a.b/evil", "").forEach {
            assertNull(SourceId.parse(it), it)
        }
    }

    @Test fun referencesRejectAmbiguousEscapesAndMalformedUtf8() {
        val source = SourceId.parse("plugin:example.catalog")!!
        listOf("同一首歌/100%:a", "%2F", "a/b", "a:b", "😀").forEach {
            assertEquals(source to it, SourceResourceUri.decode(SourceResourceUri.encode(source, it)))
        }
        val uri = SourceResourceUri.encode(source, "a/b")
        listOf("a/b", "%61%2Fb", "%C0%AF", "%", "a%2fb").forEach {
            assertNull(SourceResourceUri.decode(uri.replace("a%2Fb", it)), it)
        }
        assertNull(SourceResourceUri.decode(uri + "?query=1"))
    }

    @Test fun limitsCountUtf8BytesAndRejectUnpairedSurrogates() {
        val source = SourceId.parse("plugin:example.catalog")!!
        val limit = "😀".repeat(1024)
        assertEquals(source to limit, SourceResourceUri.decode(SourceResourceUri.encode(source, limit)))
        assertFailsWith<IllegalArgumentException> { SourceResourceUri.encode(source, limit + "a") }
        assertFailsWith<IllegalArgumentException> { SourceResourceUri.encode(source, "\uD800") }
        assertNull(SourceResourceUri.decode("melox://source/v1/plugin%3Aexample.catalog/" + "a".repeat(4097)))
    }
}
