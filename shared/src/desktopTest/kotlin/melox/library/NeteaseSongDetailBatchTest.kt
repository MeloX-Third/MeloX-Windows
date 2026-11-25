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

class NeteaseSongDetailBatchTest {
    @Test fun publicDetailMethodPreservesEveryIdAcrossServerSizedRequests() {
        val batches = CopyOnWriteArrayList<List<Long>>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("/eapi/v3/song/detail", request.url.encodedPath)
            val params = (request.body as FormBody).value(0)
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(), "AES"))
            val json = JSONObject(cipher.doFinal(params.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                .toString(Charsets.UTF_8).split("-36cd479b6b5-")[1])
            val descriptors = JSONArray(json.getString("c"))
            val ids = (0 until descriptors.length()).map { descriptors.getJSONObject(it).getLong("id") }
            batches += ids
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(JSONObject().put("code", 200).put("songs", JSONArray().apply {
                    ids.forEach { put(JSONObject().put("id", it).put("name", "Song $it")) }
                }).toString().toResponseBody()).build()
        }.build()
        try {
            val ids = (1L..251L).toList()
            assertEquals(ids, NeteaseLibraryClient({ "" }, http).songDetailsBlocking(ids).map { it.id })
            assertEquals(listOf(100, 100, 51), batches.map { it.size })
        } finally { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow() }
    }
}
