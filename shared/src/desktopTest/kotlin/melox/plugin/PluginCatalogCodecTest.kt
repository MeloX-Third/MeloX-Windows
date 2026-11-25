package melox.plugin

import melox.music.model.*
import org.json.JSONObject
import org.json.JSONArray
import kotlin.test.*

class PluginCatalogCodecTest {
    private val source = SourceId.parse("plugin:example.codec")!!
    private val codec = PluginCatalogCodec(PluginManifest(source, "Codec", "1.0.0", 1, "index.js", emptySet(), setOf("example.com")))
    private fun page(item: JSONObject) = JSONObject().put("page", 2).put("pageSize", 10).put("total", 11)
        .put("hasMore", false).put("items", JSONArray().put(item))

    @Test fun catalogAndAccountModelsPreserveIdentityAndArtworkProvenance() {
        val item = JSONObject("""{"id":"opaque/1","title":"Title","artworkUrl":"https://example.com/image.png","trackCount":1,"playCount":4}""")
        val list = codec.page(page(item), 2, 10, codec::playlist)
        assertEquals(11L, list.total)
        val playlist = list.items.single()
        assertEquals(MusicResourceId(source, "opaque/1"), playlist.id)
        assertEquals(source, PluginArtwork.parse(playlist.artworkUrl!!).source)
        val album = codec.album(JSONObject(item.toString()).put("artists", JSONArray().put(JSONObject().put("id", "artist").put("name", "Artist"))))
        assertEquals(source, album.artists.single().id?.sourceId)
        val artist = codec.artist(JSONObject().put("id", "artist").put("name", "Artist"))
        assertEquals(source, artist.id.sourceId)
        assertEquals(source, codec.ranking(item).id.sourceId)
        val account = codec.account(JSONObject().put("id", "same-account").put("displayName", "User").put("avatarUrl", "https://example.com/avatar.png"))
        assertEquals(source, account.sourceId)
        assertEquals(source, PluginArtwork.parse(account.avatarUrl!!).source)
    }

    @Test fun paginationAndCollectionBoundsCannotBeCoerced() {
        val item = JSONObject().put("id", "p").put("title", "Playlist")
        val value = page(item)
        assertFails { codec.page(value, 1, 10, codec::playlist) }
        assertFails { codec.page(value, 2, 30, codec::playlist) }
        assertFails { codec.page(JSONObject(value.toString()).put("total", "11"), 2, 10, codec::playlist) }
        assertFails { codec.playlist(JSONObject(item.toString()).put("trackCount", Int.MAX_VALUE.toLong() + 1)) }
        assertFails { codec.list(JSONObject().put("items", JSONArray().put(item)), "items", 0, codec::playlist) }
        assertFails { codec.account(JSONObject().put("id", "u").put("displayName", "User").put("avatarUrl", "https://foreign.com/a.png")) }
    }
}
