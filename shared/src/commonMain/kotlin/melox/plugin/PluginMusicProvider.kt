package melox.plugin

import melox.music.model.*
import melox.music.provider.*
import melox.lyrics.*
import org.json.JSONArray
import org.json.JSONObject

fun interface PluginInvoker {
    suspend fun invoke(method: String, args: JSONArray): String
}

/** Maps API v1 results after structural checks. Install/runtime ownership belongs to PluginManager. */
class PluginMusicProvider(val manifest: PluginManifest, private val runtime: PluginInvoker) :
    MusicProvider, SearchCapability, CatalogSearchCapability, PlaylistCapability, AlbumCapability,
    ArtistCapability, RankingCatalogCapability, HomeFeedCapability, UserLibraryCapability, LibraryCollectionCapability,
    FavoriteCapability, FavoriteStateCapability, AuthenticationCapability, PlaybackCapability, DownloadCapability,
    FallbackPlaybackCapability, LyricsCapability {
    override val sourceId = manifest.sourceId
    override val displayName = manifest.name
    override val capabilities = manifest.capabilities.mapTo(mutableSetOf()) {
        when (it) {
            PluginCapability.Search -> MusicCapability.Search
            PluginCapability.Playback -> MusicCapability.Playback
            PluginCapability.Lyrics -> MusicCapability.Lyrics
            PluginCapability.Playlists -> MusicCapability.Playlists
            PluginCapability.Albums -> MusicCapability.Albums
            PluginCapability.Artists -> MusicCapability.Artists
            PluginCapability.Rankings -> MusicCapability.Rankings
            PluginCapability.HomeFeed -> MusicCapability.HomeRecommendations
            PluginCapability.UserLibrary -> MusicCapability.Library
            PluginCapability.Favorites -> MusicCapability.Favorites
            PluginCapability.Authentication -> MusicCapability.Authentication
            PluginCapability.Download -> MusicCapability.Download
            PluginCapability.Resolver -> MusicCapability.Resolver
        }
    }
    private val codec = PluginCatalogCodec(manifest)
    private suspend fun call(capability: PluginCapability, method: String, vararg args: Any): String {
        require(capability in manifest.capabilities && method in capability.methods) { "UNSUPPORTED_OPERATION" }
        val result = runtime.invoke(method, JSONArray(args.toList()))
        PluginWireContract.check(method, result)
        return result
    }
    private suspend fun catalog(capability: PluginCapability, method: String, query: String?, page: Int, size: Int): JSONObject {
        require(page > 0 && size in 1..100 && (query == null || query.length <= 1024))
        return JSONObject(if (query == null) call(capability, method, page, size) else call(capability, method, query, page, size))
    }
    override suspend fun searchSongs(query: String, page: Int, pageSize: Int) = codec.tracks(
        catalog(PluginCapability.Search, "search", query, page, pageSize), page, pageSize)
    suspend fun playlists(page: Int = 1, pageSize: Int = 30) = codec.page(
        catalog(PluginCapability.Playlists, "playlists", null, page, pageSize), page, pageSize, codec::playlist)
    override suspend fun searchPlaylists(query: String, page: Int, pageSize: Int) = codec.page(
        catalog(PluginCapability.Playlists, "searchPlaylists", query, page, pageSize), page, pageSize, codec::playlist)
    override suspend fun searchAlbums(query: String, page: Int, pageSize: Int) = codec.page(
        catalog(PluginCapability.Albums, "searchAlbums", query, page, pageSize), page, pageSize, codec::album)
    override suspend fun searchArtists(query: String, page: Int, pageSize: Int) = codec.page(
        catalog(PluginCapability.Artists, "searchArtists", query, page, pageSize), page, pageSize, codec::artist)
    override suspend fun rankings(page: Int, pageSize: Int) = codec.page(
        catalog(PluginCapability.Rankings, "rankings", null, page, pageSize), page, pageSize, codec::ranking)

    private suspend fun detail(capability: PluginCapability, method: String, id: MusicResourceId, page: Int, size: Int): JSONObject {
        require(id.sourceId == sourceId && page > 0 && size in 1..100)
        return JSONObject(call(capability, method, id.value, page, size))
    }
    override suspend fun playlistDetail(playlist: MusicPlaylistSummary, page: Int, pageSize: Int): MusicPlaylistDetail {
        val value = detail(PluginCapability.Playlists, "playlistDetail", playlist.id, page, pageSize)
        val summary = codec.playlist(value.getJSONObject("summary")); require(summary.id == playlist.id)
        val tracks = codec.tracks(value.getJSONObject("tracks"), page, pageSize)
        return MusicPlaylistDetail(summary, tracks.items, tracks.total, tracks.hasMore)
    }
    override suspend fun albumDetail(album: MusicAlbumSummary, page: Int, pageSize: Int): MusicAlbumDetail {
        val value = detail(PluginCapability.Albums, "albumDetail", album.id, page, pageSize)
        val summary = codec.album(value.getJSONObject("summary")); require(summary.id == album.id)
        val tracks = codec.tracks(value.getJSONObject("tracks"), page, pageSize)
        return MusicAlbumDetail(summary, tracks.items, tracks.total, tracks.hasMore)
    }
    override suspend fun artistDetail(artist: MusicArtistSummary, page: Int, pageSize: Int): MusicArtistDetail {
        val value = detail(PluginCapability.Artists, "artistDetail", artist.id, page, pageSize)
        val summary = codec.artist(value.getJSONObject("summary")); require(summary.id == artist.id)
        val tracks = codec.tracks(value.getJSONObject("tracks"), page, pageSize)
        return MusicArtistDetail(summary, tracks.items, tracks.total, tracks.hasMore)
    }
    override suspend fun rankingTracks(ranking: MusicRankingSummary, page: Int, pageSize: Int) = codec.tracks(
        detail(PluginCapability.Rankings, "rankingTracks", ranking.id, page, pageSize), page, pageSize)
    override suspend fun homeFeed(playlistLimit: Int, newSongLimit: Int, rankingLimit: Int): MusicHomeFeed {
        require(playlistLimit in 0..100 && newSongLimit in 0..100 && rankingLimit in 0..100)
        val value = JSONObject(call(PluginCapability.HomeFeed, "homeFeed", playlistLimit, newSongLimit, rankingLimit))
        return MusicHomeFeed(codec.list(value, "recommendedPlaylists", playlistLimit, codec::playlist),
            codec.list(value, "newSongs", newSongLimit, codec::track), codec.list(value, "rankings", rankingLimit, codec::ranking))
    }
    override suspend fun accountSummary(): MusicAccountSummary? {
        val result = call(PluginCapability.UserLibrary, "accountSummary")
        return if (result.trim() == "null") null else codec.account(JSONObject(result))
    }
    override suspend fun userPlaylists(page: Int, pageSize: Int) = codec.page(
        catalog(PluginCapability.UserLibrary, "userPlaylists", null, page, pageSize), page, pageSize, codec::playlist)
    override suspend fun savedAlbums(page: Int, pageSize: Int) = codec.page(
        catalog(PluginCapability.UserLibrary, "savedAlbums", null, page, pageSize), page, pageSize, codec::album)
    override suspend fun followedArtists(page: Int, pageSize: Int) = codec.page(
        catalog(PluginCapability.UserLibrary, "followedArtists", null, page, pageSize), page, pageSize, codec::artist)
    override suspend fun authenticationStatus(): MusicAuthenticationState {
        val value = JSONObject(call(PluginCapability.Authentication, "authenticationStatus"))
        return when (value.getString("type")) {
            "notRequired" -> MusicAuthenticationState.NotRequired
            "signedOut" -> MusicAuthenticationState.SignedOut
            "signedIn" -> MusicAuthenticationState.SignedIn(codec.account(value.getJSONObject("account")))
            "expired" -> MusicAuthenticationState.Expired(codec.optionalText(value, "accountId"))
            else -> error("invalid authentication state")
        }
    }
    override suspend fun isFavorite(track: MusicTrack): Boolean {
        checkTrack(track)
        return call(PluginCapability.Favorites, "isFavorite", trackJson(track)).trim().toBooleanStrict()
    }
    override suspend fun setFavorite(track: MusicTrack, favorite: Boolean) {
        checkTrack(track)
        call(PluginCapability.Favorites, "setFavorite", trackJson(track), favorite)
    }
    override suspend fun resolvePlayback(track: MusicTrack, quality: AudioQualityTier) =
        resolveAudio(PluginCapability.Playback, "resolvePlayback", track, quality)
    override suspend fun resolveDownload(track: MusicTrack, quality: AudioQualityTier) =
        resolveAudio(PluginCapability.Download, "resolveDownload", track, quality)
    override suspend fun resolveFallback(track: MusicTrack, quality: AudioQualityTier) =
        resolveAudio(PluginCapability.Resolver, "resolveFallback", track, quality, external = true)

    private suspend fun resolveAudio(capability: PluginCapability, method: String, track: MusicTrack,
        quality: AudioQualityTier, external: Boolean = false): PlaybackResolution {
        if (!external) checkTrack(track)
        val value = JSONObject(call(capability, method, trackJson(track), wire(quality.name)))
        return when (value.getString("type")) {
            "playable" -> {
                val url = value.getString("url"); validateMediaUrl(url, external)
                // A host media lease must be connected before credential references can be consumed.
                require(!value.has("credential")) { "PERMISSION_DENIED" }
                val raw = value.optJSONObject("headers")
                val headers = buildMap {
                    raw?.keys()?.forEach { key ->
                        require(key.lowercase(java.util.Locale.ROOT) !in setOf("host", "connection", "content-length", "transfer-encoding", "proxy-authorization", "proxy-connection", "upgrade"))
                        put(key, raw.getString(key))
                    }
                }
                val expires = codec.optionalLong(value, "expiresAtEpochMs")
                require(expires == null || expires > System.currentTimeMillis())
                PlaybackResolution.Playable(url, headers, quality,
                    actualQuality = codec.optionalText(value, "actualQuality")?.let { text -> AudioQualityTier.entries.first { wire(it.name) == text } },
                    bitrate = codec.optionalLong(value, "bitrate")?.toInt(),
                    format = if (value.has("format")) value.getString("format") else null, expiresAtEpochMs = expires)
            }
            "preview" -> {
                val url = value.getString("url"); validateMediaUrl(url, external)
                PlaybackResolution.Preview(url, codec.optionalLong(value, "durationMs"))
            }
            "loginRequired" -> PlaybackResolution.LoginRequired
            "subscriptionRequired" -> PlaybackResolution.SubscriptionRequired
            "regionRestricted" -> PlaybackResolution.RegionRestricted
            "copyrightRestricted" -> PlaybackResolution.CopyrightRestricted
            "unavailable" -> PlaybackResolution.Unavailable(value.optString("reason").take(512))
            else -> error("invalid playback resolution")
        }
    }
    override suspend fun lyrics(track: MusicTrack): LyricsDocument {
        checkTrack(track)
        val value = JSONObject(call(PluginCapability.Lyrics, "lyrics", trackJson(track)))
        val lines = value.getJSONArray("lines")
        return LyricsDocument((0 until lines.length()).map { i ->
            val line = lines.getJSONObject(i)
            LyricLine(timeMs = line.getLong("timeMs"), text = line.getString("text"), durationMs = codec.optionalLong(line, "durationMs"),
                translation = line.optString("translation").takeIf(String::isNotEmpty),
                romanization = line.optString("romanization").takeIf(String::isNotEmpty))
        }, quality = LyricQuality.LineSynchronized, pseudoTimingAllowed = false,
            isUnsynchronized = value.optBoolean("isUnsynchronized", false))
    }
    private fun checkTrack(track: MusicTrack) { require(track.id.sourceId == sourceId) { "track belongs to another source" } }
    private fun validateMediaUrl(url: String, external: Boolean) {
        PluginResultValidator.validateUrlSyntax(url)
        val uri = java.net.URI(url)
        if (external) require(uri.port in setOf(-1, 80, 443))
        else require(uri.scheme == "https" && uri.host in manifest.networkDomains && uri.port in setOf(-1, 443))
    }
    private fun trackJson(track: MusicTrack) = JSONObject().put("id", track.id.value).put("title", track.title)
        .put("source", track.id.sourceId.value).put("artists", JSONArray(track.artists.map { artist ->
            JSONObject().put("name", artist.name).apply { artist.id?.takeIf { it.sourceId == sourceId }?.let { put("id", it.value) } }
        })).apply {
            track.durationMs?.let { put("durationMs", it) }
            track.album?.let { album -> put("album", JSONObject().put("name", album.name).apply {
                album.id?.takeIf { it.sourceId == sourceId }?.let { put("id", it.value) }
            }) }
            put("availability", wire(track.availability.name))
            track.bestQuality?.let { put("bestQuality", wire(it.name)) }
            track.bestBitrate?.let { put("bestBitrate", it) }
        }
    private fun wire(value: String) = value.replaceFirstChar { it.lowercaseChar() }
}
