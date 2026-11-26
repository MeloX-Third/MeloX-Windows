package melox.library

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.*

/** Loopback EAPI/WEAPI fixture: never contacts NetEase, no app account is installed. */
class NeteaseLikedBackfillIntegrationTest {
    private data class Fixture(
        val likeIds: List<Long>,
        val trackCount: Int,
        val detailIds: List<Long>,
        val playlistTrackIds: List<Long>,
        val embeddedTrackIds: List<Long>,
    )

    private fun song(id: Long) = JSONObject()
        .put("id", id)
        .put("name", "Song $id")
        .put("ar", JSONArray().put(JSONObject().put("id", 100 + id).put("name", "Artist $id")))
        .put("al", JSONObject().put("id", 200 + id).put("name", "Album $id"))
        .put("dt", 1_000L)

    private fun songs(ids: List<Long>) = JSONArray().apply { ids.forEach { put(song(it)) } }

    private fun playlistJson(fixture: Fixture, detailed: Boolean) = JSONObject()
        .put("id", 7L)
        .put("name", "我喜欢的音乐")
        .put("specialType", 5)
        .put("trackCount", fixture.trackCount)
        .put("creator", JSONObject().put("userId", 42L).put("nickname", "Me"))
        .apply {
            if (detailed) {
                put("trackIds", JSONArray().apply {
                    fixture.playlistTrackIds.forEach { put(JSONObject().put("id", it)) }
                })
                put("tracks", songs(fixture.embeddedTrackIds))
            }
        }

    private fun serve(fixture: Fixture, paths: MutableList<String>): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            paths += path
            val body = when (path) {
                "/eapi/user/playlist" -> JSONObject().put("code", 200)
                    .put("playlist", JSONArray().put(playlistJson(fixture, detailed = false)))
                "/eapi/song/like/get" -> JSONObject().put("code", 200)
                    .put("ids", JSONArray().apply { fixture.likeIds.forEach { put(it) } })
                "/weapi/v3/song/detail" -> JSONObject().put("code", 200).put("songs", songs(fixture.detailIds))
                "/eapi/v6/playlist/detail" -> JSONObject().put("code", 200)
                    .put("playlist", playlistJson(fixture, detailed = true))
                "/weapi/play-record/song/list" -> JSONObject().put("code", 200)
                    .put("data", JSONObject().put("list", JSONArray()))
                else -> JSONObject().put("code", 404).put("message", "unexpected $path")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toString().toResponseBody()).build()
        }.build()

    private fun snapshot(fixture: Fixture): Pair<NeteaseLibrarySnapshot, List<String>> {
        val paths = CopyOnWriteArrayList<String>()
        val http = serve(fixture, paths)
        try {
            val client = NeteaseLibraryClient({ "MUSIC_U=fixture-token" }, http)
            return runBlocking { client.snapshot(42L) } to paths.toList()
        } finally {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow()
        }
    }

    @Test fun likeEndpointShortOfPlaylistCountIsBackfilledFromTheSystemPlaylist() {
        val (snapshot, paths) = snapshot(
            Fixture(likeIds = listOf(1, 2), trackCount = 3, detailIds = listOf(1, 2, 3),
                playlistTrackIds = listOf(1, 2, 3), embeddedTrackIds = listOf(1, 2)),
        )
        assertEquals(listOf(1L, 2L, 3L), snapshot.likedSongs.map { it.id })
        assertEquals(1, paths.count { it == "/eapi/v6/playlist/detail" })
    }

    @Test fun droppedDetailIdIsRecoveredFromThePlaylistPayload() {
        val (snapshot, paths) = snapshot(
            Fixture(likeIds = listOf(1, 2, 3), trackCount = 3, detailIds = listOf(1, 2),
                playlistTrackIds = listOf(1, 2, 3), embeddedTrackIds = listOf(3)),
        )
        assertEquals(listOf(1L, 2L, 3L), snapshot.likedSongs.map { it.id })
        assertEquals(3L, snapshot.likedSongs.last().id)
        assertEquals(1, paths.count { it == "/eapi/v6/playlist/detail" })
    }

    @Test fun healthyAccountSendsNoBackfillRequest() {
        val (snapshot, paths) = snapshot(
            Fixture(likeIds = listOf(1, 2), trackCount = 2, detailIds = listOf(1, 2),
                playlistTrackIds = listOf(1, 2), embeddedTrackIds = listOf(1, 2)),
        )
        assertEquals(listOf(1L, 2L), snapshot.likedSongs.map { it.id })
        assertEquals(0, paths.count { it == "/eapi/v6/playlist/detail" })
    }
}
