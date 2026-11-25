package melox.download

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.*

class ProviderDownloadIndexCodecTest {
    @Test fun fullDynamicTrackRetainsMetadataAndRejectsConflictingIndexIdentity() {
        val source = melox.music.model.SourceId.parse("plugin:example.catalog")!!
        val track = melox.music.model.MusicTrack(melox.music.model.MusicResourceId(source, "123"), "Track",
            listOf(melox.music.model.MusicArtistRef(name = "Artist")), bestQuality = melox.music.model.AudioQualityTier.Lossless)
        val row = JSONObject().put("source", source.value).put("id", "123")
            .put("track", melox.music.model.MeloXTrackJson.encode(track))
        assertEquals(track, ProviderDownloadIndexCodec.decodeTrack(JSONObject(row.toString())))
        assertNull(ProviderDownloadIndexCodec.decodeTrack(JSONObject(row.toString()).put("source", "netease")))
        assertNull(ProviderDownloadIndexCodec.decodeTrack(JSONObject(row.toString()).put("id", "other")))
        assertNull(ProviderDownloadIndexCodec.decodeTrack(JSONObject(row.toString()).put("track", "invalid")))
    }

    @Test fun updatesPreserveUnknownSourcesFieldsAndNonObjectRows() {
        val previous = JSONArray("""[{"source":"qq_music","id":"same","extension":{"future":true}},
            {"source":"plugin:future","id":"same","payload":42},"unknown-row",null]""")
        val updates = JSONArray("""[{"source":"qq_music","id":"same","title":"Updated"},
            {"source":"netease","id":"new","title":"New"}]""")
        val result = ProviderDownloadIndexCodec.merge(previous, updates, emptySet())
        assertEquals(5, result.length())
        assertTrue(result.getJSONObject(0).getJSONObject("extension").getBoolean("future"))
        assertEquals("Updated", result.getJSONObject(0).getString("title"))
        assertEquals(42, result.getJSONObject(1).getInt("payload"))
        assertEquals("unknown-row", result.getString(2))
        assertTrue(result.isNull(3))
        assertEquals("new", result.getJSONObject(4).getString("id"))
        assertFalse(previous.getJSONObject(0).has("title"))
    }

    @Test fun explicitRemovalWinsOverStaleUpdatesAndKeepsSameIdFromOtherSource() {
        val previous = JSONArray("""[{"source":"qq_music","id":"1"},{"source":"netease","id":"1"}]""")
        val result = ProviderDownloadIndexCodec.merge(previous, previous, setOf("qq_music:1"))
        assertEquals(1, result.length())
        assertEquals("netease:1", ProviderDownloadIndexCodec.identity(result.getJSONObject(0)))
        assertNull(ProviderDownloadIndexCodec.identity(JSONObject().put("source", 1).put("id", "1")))
    }
}
