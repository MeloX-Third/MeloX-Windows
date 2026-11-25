package melox.dailyhistory

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import melox.model.SearchSong
import melox.platform.meloXDataDir
import org.json.JSONArray
import org.json.JSONObject

data class DailyHistoryEntry(val date: String, val songs: List<SearchSong>)

/** Android edc829e daily archive, using Windows atomic replacement instead of renameTo. */
class DailyHistoryStore(private val directory: File = File(meloXDataDir(), "melox_daily_history")) {
    companion object {
        val instance by lazy { DailyHistoryStore() }
        fun accountKey(cookie: String): String = MessageDigest.getInstance("SHA-256")
            .digest(cookie.ifBlank { "anonymous" }.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it.toInt() and 255) }

        fun validDate(key: String): Boolean = runCatching {
            LocalDate.parse(key).toString() == key && Regex("\\d{4}-\\d{2}-\\d{2}").matches(key)
        }.getOrDefault(false)
    }

    private val mutex = Mutex()
    private fun accountDir(key: String): File {
        require(Regex("[0-9a-f]{16}").matches(key)) { "Invalid daily history account" }
        return File(directory, key)
    }
    private fun dates(dir: File): List<String> = dir.listFiles().orEmpty()
        .filter { it.isFile && it.extension == "json" && validDate(it.nameWithoutExtension) }
        .map { it.nameWithoutExtension }.sortedDescending()

    suspend fun archive(account: String, date: String, songs: List<SearchSong>): Boolean {
        if (songs.isEmpty() || !validDate(date)) return false
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val dir = accountDir(account)
                runCatching {
                    dir.mkdirs()
                    val encoded = JSONObject().put("date", date).put("songs", JSONArray().apply {
                        songs.forEach { song -> put(JSONObject().apply {
                            put("id", song.id); put("name", song.name); put("artists", song.artists)
                            put("album", song.album); put("artworkUrl", song.artworkUrl.orEmpty())
                            put("durationMs", song.durationMs); put("albumId", song.albumId ?: -1)
                            put("artistId", song.artistId ?: -1)
                        }) }
                    }).toString()
                    val temporary = Files.createTempFile(dir.toPath(), "$date-", ".tmp")
                    try {
                        Files.writeString(temporary, encoded)
                        Files.move(temporary, File(dir, "$date.json").toPath(),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } finally { Files.deleteIfExists(temporary) }
                    dates(dir).drop(30).forEach { Files.deleteIfExists(File(dir, "$it.json").toPath()) }
                }.isSuccess
            }
        }
    }

    suspend fun listDates(account: String): List<String> = mutex.withLock {
        withContext(Dispatchers.IO) { dates(accountDir(account)) }
    }

    suspend fun read(account: String, date: String): DailyHistoryEntry? {
        if (!validDate(date)) return null
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val dir = accountDir(account)
                runCatching {
                    val value = JSONObject(File(dir, "$date.json").readText())
                    check(value.getString("date") == date)
                    val values = value.getJSONArray("songs")
                    DailyHistoryEntry(date, List(values.length()) { index ->
                        val song = values.getJSONObject(index)
                        SearchSong(song.getLong("id"), song.getString("name"), song.getString("artists"),
                            song.getString("album"), song.optString("artworkUrl").takeIf(String::isNotBlank),
                            durationMs = song.optLong("durationMs"),
                            albumId = song.optLong("albumId", -1).takeIf { it > 0 },
                            artistId = song.optLong("artistId", -1).takeIf { it > 0 })
                    })
                }.getOrNull()
            }
        }
    }
}
