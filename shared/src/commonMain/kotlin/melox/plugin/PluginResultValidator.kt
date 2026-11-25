package melox.plugin

import melox.music.model.*
import org.json.JSONObject
import java.net.URI

/** Standard MeloX fields only. Network authorization belongs to the host broker. */
object PluginResultValidator {
    data class SearchPage(val tracks: List<MusicTrack>, val page: Int, val pageSize: Int, val hasMore: Boolean)

    fun search(source: SourceId, json: String): SearchPage {
        require(source.value.startsWith("plugin:")) { "invalid plugin source" }
        PluginJson.check(json)
        val result = JSONObject(json)
        val page = integer(result, "page", 1..Int.MAX_VALUE)
        val size = integer(result, "pageSize", 1..100)
        require(result.opt("hasMore") is Boolean)
        val items = result.getJSONArray("items")
        require(items.length() <= size)
        val tracks = (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val id = requireNotNull(resource(source, item, "id"))
            val title = text(item, "title", 1024)
            val rawArtists = item.getJSONArray("artists")
            require(rawArtists.length() in 1..32)
            val artists = (0 until rawArtists.length()).map { i ->
                val artist = rawArtists.getJSONObject(i)
                MusicArtistRef(resource(source, artist, "id"), text(artist, "name", 512))
            }
            val album = if (item.has("album")) item.getJSONObject("album").let {
                MusicAlbumRef(resource(source, it, "id"), text(it, "name", 1024), url(it, "artworkUrl"))
            } else null
            val availability = if (item.has("availability")) TrackAvailability.entries.firstOrNull {
                wire(it.name) == item.getString("availability")
            } ?: error("invalid availability") else TrackAvailability.Unknown
            val quality = if (item.has("bestQuality")) AudioQualityTier.entries.firstOrNull {
                wire(it.name) == item.getString("bestQuality")
            } ?: error("invalid quality") else null
            MusicTrack(id, title, artists, album, url(item, "artworkUrl") ?: album?.artworkUrl,
                optionalInteger(item, "durationMs", 0..86_400_000)?.toLong(), availability,
                bestQuality = quality, bestBitrate = optionalInteger(item, "bestBitrate", 1..Int.MAX_VALUE))
        }
        return SearchPage(tracks, page, size, result.getBoolean("hasMore"))
    }

    private fun resource(source: SourceId, item: JSONObject, key: String): MusicResourceId? {
        if (!item.has(key)) return null
        val value = text(item, key, 4096)
        require(value.toByteArray(Charsets.UTF_8).size <= 4096)
        return MusicResourceId(source, value)
    }
    private fun text(item: JSONObject, key: String, max: Int): String {
        require(item.opt(key) is String)
        return item.getString(key).also { require(it.isNotBlank() && it.codePointCount(0, it.length) <= max) }
    }
    private fun url(item: JSONObject, key: String): String? = if (!item.has(key)) null
        else text(item, key, 4096).also(::validateUrlSyntax)
    private fun optionalInteger(item: JSONObject, key: String, range: IntRange): Int? =
        if (item.has(key)) integer(item, key, range) else null
    private fun integer(item: JSONObject, key: String, range: IntRange): Int {
        require(item.opt(key) is Number)
        val value = item.getDouble(key)
        require(value.isFinite() && value == kotlin.math.floor(value) && value >= range.first && value <= range.last)
        return value.toInt()
    }
    private fun wire(value: String) = value.replaceFirstChar { it.lowercaseChar() }

    fun validateUrlSyntax(value: String) {
        require(value.length <= 4096)
        val uri = URI(value)
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "invalid URL" }
    }
}
