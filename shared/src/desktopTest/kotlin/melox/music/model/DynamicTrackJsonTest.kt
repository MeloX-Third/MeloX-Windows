package melox.music.model

import melox.music.provider.MeloXLegacyUiBridge
import org.json.JSONObject
import kotlin.test.*

class DynamicTrackJsonTest {
    @Test fun dynamicTrackArtistAndAlbumIdentitiesSurviveJsonReload() {
        val source = SourceId.parse("plugin:example.catalog")!!
        val track = MusicTrack(MusicResourceId(source, "歌曲/100%:😀"), "Title",
            listOf(MusicArtistRef(MusicResourceId(source, "artist"), "Artist")),
            MusicAlbumRef(MusicResourceId(source, "album"), "Album", "https://example.test/art.jpg"),
            durationMs = 123456L, bestQuality = AudioQualityTier.Lossless, bestBitrate = 999999)
        val restored = MeloXTrackJson.decode(JSONObject(MeloXTrackJson.encode(track).toString()))
        assertEquals(track, restored)
        assertEquals(track, MeloXLegacyUiBridge.track(track).providerTrack)
        val other = track.copy(id = MusicResourceId(SourceId.parse("plugin:other.catalog")!!, track.id.value))
        assertNotEquals(MeloXLegacyUiBridge.track(track).id, MeloXLegacyUiBridge.track(other).id)
    }

    @Test fun validUnavailableSourceIsPreservedWhileInvalidNamespaceIsRejected() {
        val json = JSONObject("""{"source":"future_provider","id":"123","title":"Future"}""")
        val track = assertNotNull(MeloXTrackJson.decode(json))
        assertEquals("future_provider", track.id.sourceId.value)
        assertNull(track.id.sourceId.builtIn)
        assertEquals("future_provider", MeloXTrackJson.encode(track).getString("source"))
        assertNull(MeloXTrackJson.decode(json.put("source", "plugin:a/b")))
    }
}
