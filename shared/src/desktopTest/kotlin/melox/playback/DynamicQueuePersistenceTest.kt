package melox.playback

import melox.music.model.*
import melox.music.provider.MeloXLegacyUiBridge
import org.json.JSONObject
import kotlin.test.*

class DynamicQueuePersistenceTest {
    @Test fun fullDynamicTrackSurvivesQueueJsonReload() {
        val source = SourceId.parse("plugin:example.catalog")!!
        val track = MusicTrack(MusicResourceId(source, "123/歌曲"), "Title",
            listOf(MusicArtistRef(MusicResourceId(source, "artist"), "Artist")),
            bestQuality = AudioQualityTier.Lossless)
        val song = MeloXLegacyUiBridge.track(track)
        val json = MeloXPlaybackQueueStore.songToJson(song)
        assertEquals(source.value, json.getString("providerSource"))
        assertEquals(track, MeloXPlaybackQueueStore.songFromJson(JSONObject(json.toString()))?.providerTrack)
        assertNull(MeloXPlaybackQueueStore.songFromJson(JSONObject(json.toString()).put("providerId", "other")))
        assertNull(MeloXPlaybackQueueStore.songFromJson(JSONObject(json.toString()).put("providerSource", "netease")))
        assertNull(MeloXPlaybackQueueStore.songFromJson(JSONObject(json.toString()).put("providerTrack", "corrupt")))
    }

    @Test fun legacySourceIsParsedStrictlyAndDoesNotFallBackToNetease() {
        val row = JSONObject("""{"id":"-123","title":"Old","providerSource":"plugin:example.catalog","providerId":"123"}""")
        val track = assertNotNull(MeloXPlaybackQueueStore.songFromJson(row)?.providerTrack)
        assertEquals("plugin:example.catalog", track.id.sourceId.value)
        assertNull(track.id.sourceId.builtIn)
        assertNull(MeloXPlaybackQueueStore.songFromJson(row.put("providerSource", "invalid/source")))
        row.remove("providerSource")
        assertNull(MeloXPlaybackQueueStore.songFromJson(row))
        row.remove("providerId")
        assertNull(assertNotNull(MeloXPlaybackQueueStore.songFromJson(row)).providerTrack)
    }
}
