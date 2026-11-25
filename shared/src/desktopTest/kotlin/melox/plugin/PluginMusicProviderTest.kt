package melox.plugin

import kotlinx.coroutines.runBlocking
import melox.music.model.*
import kotlin.test.*

class PluginMusicProviderTest {
    private val source = SourceId.parse("plugin:example.provider")!!
    private val track = MusicTrack(MusicResourceId(source, "track"), "Title", emptyList())
    private fun provider(capabilities: Set<PluginCapability> = PluginCapability.entries.toSet(), block: suspend (String) -> String) =
        PluginMusicProvider(PluginManifest(source, "Provider", "1.0.0", 1, "index.js", capabilities, setOf("example.com")),
            PluginInvoker { method, _ -> block(method) })

    @Test fun catalogFavoriteAccountAndLyricOperationsUseCheckedModels() = runBlocking {
        var favorite = false
        val plugin = provider { method -> when (method) {
            "search" -> """{"page":1,"pageSize":30,"hasMore":false,"total":1,"items":[{"id":"track","title":"Title","artists":[{"name":"Artist"}]}]}"""
            "accountSummary" -> """{"id":"account","displayName":"User"}"""
            "authenticationStatus" -> """{"type":"signedIn","account":{"id":"account","displayName":"User"}}"""
            "isFavorite" -> favorite.toString()
            "setFavorite" -> { favorite = true; "null" }
            "lyrics" -> """{"lines":[{"timeMs":0,"durationMs":1000,"text":"Text","translation":"Translation"}]}"""
            else -> error(method)
        } }
        assertEquals(source, plugin.searchSongs("test", 1, 30).items.single().id.sourceId)
        assertEquals(source, plugin.accountSummary()?.sourceId)
        assertEquals(source, (plugin.authenticationStatus() as MusicAuthenticationState.SignedIn).account.sourceId)
        assertFalse(plugin.isFavorite(track)); plugin.setFavorite(track, true); assertTrue(plugin.isFavorite(track))
        val lyrics = plugin.lyrics(track)
        assertEquals("Translation", lyrics.lines.single().translation)
        assertFalse(lyrics.pseudoTimingAllowed)
    }

    @Test fun catalogDetailsAndCollectionsKeepPaginationAndRequestedIdentity(): Unit = runBlocking {
        val playlist = """{"id":"p","title":"Playlist"}"""
        val album = """{"id":"a","title":"Album"}"""
        val artist = """{"id":"ar","name":"Artist"}"""
        val ranking = """{"id":"r","title":"Ranking"}"""
        val song = """{"id":"track","title":"Song","artists":[{"name":"Artist"}]}"""
        fun page(item: String) = """{"page":1,"pageSize":30,"total":1,"hasMore":false,"items":[$item]}"""
        val plugin = provider { method -> when (method) {
            "playlists", "searchPlaylists", "userPlaylists" -> page(playlist)
            "searchAlbums", "savedAlbums" -> page(album)
            "searchArtists", "followedArtists" -> page(artist)
            "rankings" -> page(ranking)
            "rankingTracks" -> page(song)
            "playlistDetail" -> """{"summary":$playlist,"tracks":${page(song)}}"""
            "albumDetail" -> """{"summary":$album,"tracks":${page(song)}}"""
            "artistDetail" -> """{"summary":$artist,"tracks":${page(song)}}"""
            "homeFeed" -> """{"recommendedPlaylists":[$playlist],"newSongs":[$song],"rankings":[$ranking]}"""
            else -> error(method)
        } }
        val p = plugin.playlists(1, 30).items.single()
        val a = plugin.searchAlbums("", 1, 30).items.single()
        val ar = plugin.searchArtists("", 1, 30).items.single()
        val r = (plugin as melox.music.provider.RankingCatalogCapability).rankings(1, 30).items.single()
        assertEquals(p, plugin.searchPlaylists("", 1, 30).items.single())
        assertEquals(source, plugin.playlistDetail(p, 1, 30).tracks.single().id.sourceId)
        assertEquals(false, plugin.albumDetail(a, 1, 30).hasMore)
        assertEquals(false, plugin.artistDetail(ar, 1, 30).hasMore)
        assertEquals(1L, plugin.rankingTracks(r, 1, 30).total)
        assertEquals(p, plugin.userPlaylists(1, 30).items.single())
        assertEquals(a, plugin.savedAlbums(1, 30).items.single())
        assertEquals(ar, plugin.followedArtists(1, 30).items.single())
        assertEquals(p, plugin.homeFeed(1, 1, 1).recommendedPlaylists.single())
        assertFails { plugin.playlistDetail(p.copy(id = MusicResourceId(source, "other")), 1, 30) }
        assertFails { plugin.albumDetail(a.copy(id = MusicResourceId(MusicSource.Netease, "a")), 1, 30) }
        assertFails { plugin.homeFeed(0, 0, 0) }
    }

    @Test fun undeclaredAndForeignRequestsNeverInvokeAndResponsesCannotCoerce(): Unit = runBlocking {
        var calls = 0
        val missing = provider(emptySet()) { calls++; "null" }
        assertFails { missing.searchSongs("test", 1, 30) }
        val full = provider { calls++; "null" }
        assertFails { full.setFavorite(track.copy(id = MusicResourceId(MusicSource.Netease, "1")), true) }
        assertEquals(0, calls)
        assertFails { provider { "\"true\"" }.isFavorite(track) }
        assertFails { provider { """{"lines":[{"timeMs":1.5,"text":"Text"}]}""" }.lyrics(track) }
    }

    @Test fun mediaRestrictsNativeDomainsAndResolverCredentials(): Unit = runBlocking {
        val quality = AudioQualityTier.High
        val valid = provider { """{"type":"playable","url":"https://example.com/a.mp3","actualQuality":"high"}""" }
        assertEquals(quality, (valid.resolvePlayback(track, quality) as PlaybackResolution.Playable).actualQuality)
        val external = provider { """{"type":"playable","url":"http://cdn.example.net/a.mp3"}""" }
        assertFails { external.resolvePlayback(track, quality) }
        assertIs<PlaybackResolution.Playable>(external.resolveFallback(track.copy(id = MusicResourceId(MusicSource.Netease, "1")), quality))
        assertFails { provider { """{"type":"playable","url":"https://example.com/a.mp3","credential":"active"}""" }.resolveFallback(track, quality) }
        assertFails { provider { """{"type":"playable","url":"https://example.com/a.m3u8"}""" }.resolveDownload(track, quality) }
    }
}
