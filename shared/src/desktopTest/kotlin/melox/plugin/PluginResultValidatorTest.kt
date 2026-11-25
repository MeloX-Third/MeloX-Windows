package melox.plugin

import melox.music.model.*
import kotlin.test.*

class PluginResultValidatorTest {
    private val source = SourceId.parse("plugin:example.results")!!
    private val page = """{"page":1,"pageSize":10,"hasMore":false,"items":[{"id":"x/1%20","title":"Song","artists":[{"id":"artist","name":"Artist"}],"album":{"id":"album","name":"Album"},"durationMs":3000}]}"""

    @Test fun mapsOpaqueTrackAndRelatedIdentitiesWithoutInferringPlatformFields() {
        val track = PluginResultValidator.search(source, page).tracks.single()
        assertEquals(MusicResourceId(source, "x/1%20"), track.id)
        assertEquals(source, track.artists.single().id?.sourceId)
        assertEquals(source, track.album?.id?.sourceId)
        assertEquals(3000L, track.durationMs)
        assertFails { PluginResultValidator.search(source, page.replace("\"title\"", "\"songname\"")) }
        assertFails { PluginResultValidator.search(source, page.replace("3000", "1.5")) }
        assertFails { PluginResultValidator.search(SourceId.fromBuiltIn(MusicSource.Netease), page) }
    }

    @Test fun artworkEnvelopeRetainsSourceAndExactUrlButCannotGrantDomains() {
        val manifest = PluginManifest(source, "Results", "1.0.0", 1, "index.js", emptySet(), setOf("example.com"))
        val url = "https://example.com/a.png?token=a+b%20c&size=2"
        val wrapped = PluginArtwork.wrap(manifest, url)!!
        assertEquals(PluginArtwork.Request(source, url), PluginArtwork.parse(wrapped))
        assertNull(PluginArtwork.wrap(manifest, null))
        for (invalid in listOf("http://example.com/a.png", "https://other.com/a.png", "https://example.com:444/a.png", "file:///a.png", "https://user:pass@example.com/a.png")) {
            assertFails { PluginArtwork.wrap(manifest, invalid) }
        }
        assertFails { PluginArtwork.parse("$wrapped&source=netease") }
        assertFails { PluginArtwork.parse(wrapped.replace("source=plugin%3Aexample.results", "source=netease")) }
        assertFails { PluginArtwork.parse("$wrapped#fragment") }
    }
}
