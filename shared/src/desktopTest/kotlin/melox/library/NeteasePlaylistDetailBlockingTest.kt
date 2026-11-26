package melox.library

import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.*

class NeteasePlaylistDetailBlockingTest {
    @Test fun blockingPlaylistDetailFetchesMissingTracksThroughPagedDetails() {
        val songBatches = CopyOnWriteArrayList<List<Long>>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (uri, json) = decode(request.body as FormBody)
            val body = if (uri == "/api/v6/playlist/detail") {
                JSONObject().put("code", 200).put("playlist", JSONObject()
                    .put("id", 7L)
                    .put("name", "playlist")
                    .put("trackCount", 251)
                    .put("trackIds", JSONArray().apply { (1L..251L).forEach { put(JSONObject().put("id", it)) } }))
            } else {
                val descriptors = JSONArray(json.getString("c"))
                val ids = (0 until descriptors.length()).map { descriptors.getJSONObject(it).getLong("id") }
                songBatches += ids
                JSONObject().put("code", 200).put("songs", JSONArray().apply {
                    ids.forEach { put(JSONObject().put("id", it).put("name", "Song $it")) }
                })
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toString().toResponseBody()).build()
        }.build()
        try {
            val detail = NeteaseLibraryClient({ "" }, http).playlistDetailBlocking(7L)
            assertEquals((1L..251L).toList(), detail.songs.map { it.id })
            assertEquals(listOf(51, 100, 100), songBatches.map { it.size }.sorted())
        } finally { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow() }
    }

    private fun decode(form: FormBody): Pair<String, JSONObject> {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(), "AES"))
        val plain = cipher.doFinal(form.value(0).chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            .toString(Charsets.UTF_8)
            .split("-36cd479b6b5-")
        return plain[0] to JSONObject(plain[1])
    }
}
