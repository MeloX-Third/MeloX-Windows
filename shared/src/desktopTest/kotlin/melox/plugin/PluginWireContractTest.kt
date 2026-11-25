package melox.plugin

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.*

class PluginWireContractTest {
    private fun objectOf(raw: String) = JSONObject(raw)
    private fun cases(): Map<String, Any> {
        val artistRef = objectOf("""{"id":"artist","name":"Artist"}""")
        val track = objectOf("""{"id":"track","title":"Title","artists":[{"id":"artist","name":"Artist"}],"album":{"id":"album","name":"Album","artworkUrl":"https://example.com/a.png"},"artworkUrl":"https://example.com/t.png","durationMs":120000,"availability":"playable","bestQuality":"lossless","bestBitrate":1000000}""")
        val playlist = objectOf("""{"id":"playlist","title":"Playlist","artworkUrl":"https://example.com/p.png","creatorName":"Creator","description":"Description","trackCount":1,"playCount":3}""")
        val album = objectOf("""{"id":"album","title":"Album","artworkUrl":"https://example.com/a.png","trackCount":1,"releaseDate":"2026-11-05"}""").put("artists", JSONArray().put(artistRef))
        val artist = objectOf("""{"id":"artist","name":"Artist","artworkUrl":"https://example.com/r.png","description":"Description","songCount":1,"albumCount":1}""")
        val ranking = objectOf("""{"id":"ranking","title":"Ranking","subtitle":"Subtitle","artworkUrl":"https://example.com/r.png"}""")
        val account = objectOf("""{"id":"account","displayName":"Synthetic","subtitle":"Fixture","avatarUrl":"https://example.com/user.png"}""")
        val playback = objectOf("""{"type":"playable","url":"https://example.com/a.flac","headers":{"X-Test":"value"},"actualQuality":"lossless","bitrate":1000000,"format":"flac","expiresAtEpochMs":4102444800000,"credential":"active"}""")
        fun page(model: JSONObject) = JSONObject().put("items", JSONArray().put(model)).put("page", 1).put("pageSize", 10).put("total", 1).put("hasMore", false)
        return buildMap {
            listOf("search", "rankingTracks").forEach { put(it, page(track)) }
            listOf("playlists", "searchPlaylists", "userPlaylists").forEach { put(it, page(playlist)) }
            listOf("searchAlbums", "savedAlbums").forEach { put(it, page(album)) }
            listOf("searchArtists", "followedArtists").forEach { put(it, page(artist)) }
            put("rankings", page(ranking))
            listOf("playlistDetail" to playlist, "albumDetail" to album, "artistDetail" to artist).forEach { (method, model) ->
                put(method, JSONObject().put("summary", model).put("tracks", page(track)))
            }
            put("homeFeed", JSONObject().put("recommendedPlaylists", JSONArray().put(playlist)).put("newSongs", JSONArray().put(track)).put("rankings", JSONArray().put(ranking)))
            put("accountSummary", account)
            put("authenticationStatus", JSONObject().put("type", "signedIn").put("account", account))
            listOf("resolvePlayback", "resolveDownload", "resolveFallback").forEach { put(it, playback) }
            put("lyrics", objectOf("""{"isUnsynchronized":false,"lines":[{"timeMs":0,"durationMs":1000,"text":"Text","translation":"Translation","romanization":"Romanization"}]}"""))
            put("isFavorite", true); put("setFavorite", JSONObject.NULL)
        }
    }

    @Test fun allMethodsRejectWrongTypesAtEveryPresentField() {
        val cases = cases()
        assertEquals(PluginCapability.entries.flatMap { it.methods }.toSet(), cases.keys)
        var mutations = 0
        cases.forEach { (method, value) ->
            PluginWireContract.check(method, value.toString())
            visit(value) { path, original ->
                listOf(JSONObject.NULL, "wrong", 123, true, JSONObject(), JSONArray()).filter { kind(it) != kind(original) }.forEach { wrong ->
                    val raw = if (path.isEmpty()) wrong.toString() else {
                        val copy = JSONObject(value.toString())
                        var parent: Any = copy
                        path.dropLast(1).forEach { key -> parent = if (parent is JSONObject) (parent as JSONObject).get(key) else (parent as JSONArray).get(key.toInt()) }
                        val key = path.last()
                        if (parent is JSONObject) (parent as JSONObject).put(key, wrong) else (parent as JSONArray).put(key.toInt(), wrong)
                        copy.toString()
                    }
                    assertFails("$method.${path.joinToString(".")}: ${kind(wrong)}") { PluginWireContract.check(method, raw) }
                    mutations++
                }
            }
        }
        assertTrue(mutations > 1000, "must cover nested optional fields across all methods")
    }

    @Test fun rejectsOrderingPaginationAndUnsafeMediaBounds() {
        for (raw in listOf(
            """{"lines":[{"timeMs":2,"text":"a"},{"timeMs":1,"text":"b"}]}""",
            """{"lines":[{"timeMs":86400001,"text":"a"}]}"""
        )) assertFails { PluginWireContract.check("lyrics", raw) }
        val page = JSONObject(cases().getValue("search").toString())
        for (size in listOf(0, 101, 1.5, "10")) assertFails { PluginWireContract.check("search", JSONObject(page.toString()).put("pageSize", size).toString()) }
        val media = JSONObject(cases().getValue("resolvePlayback").toString())
        assertFails { PluginWireContract.check("resolvePlayback", JSONObject(media.toString()).put("drm", false).toString()) }
        assertFails { PluginWireContract.check("resolvePlayback", JSONObject(media.toString()).put("headers", JSONObject().put("X-Test", "bad\r\nvalue")).toString()) }
        PluginWireContract.check("accountSummary", "null")
        assertFails { PluginWireContract.check("unknown", "{}") }
    }

    private fun kind(v: Any): String = when (v) { JSONObject.NULL -> "null"; is JSONObject -> "object"; is JSONArray -> "array"; is String -> "string"; is Boolean -> "boolean"; else -> "number" }
    private fun visit(value: Any, path: List<String> = emptyList(), action: (List<String>, Any) -> Unit) {
        if (value !is JSONObject && value !is JSONArray) { action(path, value); return }
        val keys = if (value is JSONObject) value.keys().asSequence().toList() else (0 until (value as JSONArray).length()).map(Int::toString)
        keys.forEach { key ->
            val child = if (value is JSONObject) value.get(key) else (value as JSONArray).get(key.toInt())
            action(path + key, child)
            if (child is JSONObject || child is JSONArray) visit(child, path + key, action)
        }
    }
}
