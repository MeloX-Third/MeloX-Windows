package melox.music.provider

import melox.lyrics.LyricsDocument
import melox.music.model.AudioQualityTier
import melox.music.model.MusicAccountSummary
import melox.music.model.MusicAlbumDetail
import melox.music.model.MusicAlbumSummary
import melox.music.model.MusicArtistDetail
import melox.music.model.MusicArtistSummary
import melox.music.model.MusicHomeFeed
import melox.music.model.MusicPage
import melox.music.model.MusicPlaylistDetail
import melox.music.model.MusicPlaylistSummary
import melox.music.model.MusicRankingSummary
import melox.music.model.MusicSource
import melox.music.model.SourceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import melox.music.model.MusicTrack
import melox.music.model.PlaybackResolution

enum class MusicCapability {
    Search,
    Playback,
    Lyrics,
    Library,
    Playlists,
    PlaylistWrite,
    Albums,
    Artists,
    Favorites,
    Comments,
    HomeRecommendations,
    DailyRecommendations,
    Rankings,
    Podcasts,
    CloudMusic,
    PrivateFm,
    HeartMode,
    ListenTogether,
    Messages,
    Recognition,
    Resolver,
}

/**
 * A provider only declares identity/capabilities. Provider-specific product
 * features must not be forced into this interface just because NetEase has them.
 */
interface MusicProvider {
    val source: MusicSource
        get() = error("Dynamic music providers have no built-in MusicSource")
    val sourceId: SourceId
        get() = SourceId.fromBuiltIn(source)
    val displayName: String
    val capabilities: Set<MusicCapability>

    fun supports(capability: MusicCapability): Boolean = capability in capabilities
}

interface SearchCapability {
    suspend fun searchSongs(
        query: String,
        page: Int = 1,
        pageSize: Int = 30,
    ): MusicPage<MusicTrack>
}

/** Optional richer search surface. Providers only implement types their API actually exposes. */
interface CatalogSearchCapability {
    suspend fun searchPlaylists(
        query: String,
        page: Int = 1,
        pageSize: Int = 30,
    ): MusicPage<MusicPlaylistSummary>

    suspend fun searchAlbums(
        query: String,
        page: Int = 1,
        pageSize: Int = 30,
    ): MusicPage<MusicAlbumSummary>

    suspend fun searchArtists(
        query: String,
        page: Int = 1,
        pageSize: Int = 30,
    ): MusicPage<MusicArtistSummary>
}

interface LyricsCapability {
    suspend fun lyrics(track: MusicTrack): LyricsDocument

    suspend fun lyrics(track: MusicTrack, preference: melox.lyrics.MeloXLyricPreference): LyricsDocument = lyrics(track)
}

interface PlaybackCapability {
    suspend fun resolvePlayback(
        track: MusicTrack,
        quality: AudioQualityTier,
    ): PlaybackResolution
}

/** Provider-native download resolution. This is deliberately separate from playback fallback. */
interface FallbackPlaybackCapability {
    suspend fun resolveFallback(track: MusicTrack, quality: AudioQualityTier): PlaybackResolution
}

interface DownloadCapability {
    suspend fun resolveDownload(
        track: MusicTrack,
        quality: AudioQualityTier,
    ): PlaybackResolution
}

/**
 * Optional account write capability for a provider's native "favorite/liked"
 * collection. Providers must only implement this when the platform exposes a
 * real authenticated write API with unambiguous semantics.
 */
interface FavoriteCapability {
    suspend fun setFavorite(
        track: MusicTrack,
        favorite: Boolean,
    )
}

/** A nullable/failed read must never be interpreted as "not liked" by a toggle. */
interface FavoriteStateCapability {
    suspend fun isFavorite(track: MusicTrack): Boolean
}

/**
 * Optional playlist mutation surface. A provider returns only playlists for
 * which it has a reliable provider-native writable id; display/global ids must
 * never be guessed for writes.
 */
interface PlaylistWriteCapability {
    suspend fun writablePlaylists(
        page: Int = 1,
        pageSize: Int = 50,
    ): MusicPage<MusicPlaylistSummary>

    suspend fun addTrackToPlaylist(
        track: MusicTrack,
        playlist: MusicPlaylistSummary,
    )
}

/** Optional full playlist mutation surface for providers with native sync APIs. */
interface PlaylistSyncCapability {
    suspend fun createPlaylist(name: String): MusicPlaylistSummary
    suspend fun renamePlaylist(playlist: MusicPlaylistSummary, name: String)
    suspend fun removeTrackFromPlaylist(track: MusicTrack, playlist: MusicPlaylistSummary)
    suspend fun reorderPlaylistTrack(playlist: MusicPlaylistSummary, track: MusicTrack, newIndex: Int)

    /** False when the provider can edit membership but has no delete API. */
    val canDeletePlaylists: Boolean
        get() = false

    suspend fun deletePlaylist(playlist: MusicPlaylistSummary) {
        error("这个音源不能删除歌单")
    }
}

/** Home semantic feed. Providers return only the sections they actually expose. */
interface HomeFeedCapability {
    suspend fun homeFeed(
        playlistLimit: Int = 12,
        newSongLimit: Int = 12,
        rankingLimit: Int = 8,
    ): MusicHomeFeed
}

/** Logged-in account/library data. Credentials remain private to each provider. */
interface UserLibraryCapability {
    suspend fun accountSummary(): MusicAccountSummary?

    suspend fun userPlaylists(
        page: Int = 1,
        pageSize: Int = 30,
    ): MusicPage<MusicPlaylistSummary>
}

/** Saved albums and followed artists are independent of playlist/library support. */
interface LibraryCollectionCapability {
    suspend fun savedAlbums(page: Int = 1, pageSize: Int = 50): MusicPage<MusicAlbumSummary>
    suspend fun followedArtists(page: Int = 1, pageSize: Int = 50): MusicPage<MusicArtistSummary>
}

/** Optional read-only data surface used by local aggregation analysis. */
interface LocalAggregationCapability {
    suspend fun aggregationTracks(page: Int = 1, pageSize: Int = 100): MusicPage<MusicTrack>
}

/** Read-only playlist surface shared by provider-specific Experience UIs. */
interface PlaylistCapability {
    suspend fun playlistDetail(
        playlist: MusicPlaylistSummary,
        page: Int = 1,
        pageSize: Int = 100,
    ): MusicPlaylistDetail
}

/** Rankings are not forced into playlist semantics even if a provider implements them similarly. */
interface RankingCapability {
    suspend fun rankingTracks(
        ranking: MusicRankingSummary,
        page: Int = 1,
        pageSize: Int = 100,
    ): MusicPage<MusicTrack>
}

interface AlbumCapability {
    suspend fun albumDetail(
        album: MusicAlbumSummary,
        page: Int = 1,
        pageSize: Int = 100,
    ): MusicAlbumDetail
}

interface ArtistCapability {
    suspend fun artistDetail(
        artist: MusicArtistSummary,
        page: Int = 1,
        pageSize: Int = 100,
    ): MusicArtistDetail
}

/** Small registry used by repositories and the provider-aware UI layer. */
class MusicProviderRegistry(
    providers: Iterable<MusicProvider>,
    private val dynamic: DynamicMusicProviders = DynamicMusicProviders(),
    private val awaitDynamicReady: () -> Unit = {},
) {
    private val providersBySource = providers.associateBy(MusicProvider::source)

    init {
        require(providersBySource.isNotEmpty()) { "at least one music provider is required" }
        require(providersBySource.size == providers.count()) { "duplicate music provider source" }
    }

    val providers: List<MusicProvider>
        get() = providersBySource.values.toList()

    operator fun get(source: MusicSource): MusicProvider? = providersBySource[source]

    operator fun get(source: SourceId): MusicProvider? = source.builtIn?.let(providersBySource::get)
        ?: dynamic[source]

    val generation: Long get() = dynamic.generation
    val changes: StateFlow<Long> get() = dynamic.changes
    fun awaitPluginRestore() = awaitDynamicReady()
    fun registerDynamic(provider: MusicProvider, enabled: Boolean = true) = dynamic.register(provider, enabled)
    fun setDynamicEnabled(source: SourceId, enabled: Boolean): Boolean = dynamic.setEnabled(source, enabled)
    fun unregisterDynamic(source: SourceId): Boolean = dynamic.unregister(source)
    fun replaceDynamic(provider: MusicProvider) = dynamic.replace(provider)
    fun enabledDynamicProviders(): List<MusicProvider> = dynamic.enabledProviders()

    fun require(source: MusicSource): MusicProvider =
        providersBySource[source] ?: error("Music provider ${source.storageValue} is not registered")
}

/** Shared live registrations; persistence belongs to the plugin manager. */
class DynamicMusicProviders {
    private val lock = Any()
    private data class Entry(val provider: MusicProvider, val enabled: Boolean)
    @Volatile private var entries: Map<SourceId, Entry> = emptyMap()
    @Volatile var generation: Long = 0
        private set
    private val changesState = MutableStateFlow(0L)
    val changes: StateFlow<Long> = changesState.asStateFlow()

    operator fun get(source: SourceId): MusicProvider? = entries[source]?.takeIf { it.enabled }?.provider

    private fun validate(provider: MusicProvider) {
        val id = provider.sourceId
        require(id.value.startsWith("plugin:") && id.builtIn == null) { "dynamic provider needs a plugin source id" }
        require(MusicCapability.Search !in provider.capabilities || provider is SearchCapability) { "missing search capability" }
        require(MusicCapability.Playback !in provider.capabilities || provider is PlaybackCapability) { "missing playback capability" }
        require(MusicCapability.Lyrics !in provider.capabilities || provider is LyricsCapability) { "missing lyrics capability" }
        require(MusicCapability.Resolver !in provider.capabilities || provider is FallbackPlaybackCapability) { "missing resolver capability" }
    }

    private fun changed() {
        generation++
        changesState.value = generation
    }

    fun register(provider: MusicProvider, enabled: Boolean = true) = synchronized(lock) {
        validate(provider)
        val id = provider.sourceId
        require(id !in entries) { "duplicate dynamic provider source: ${id.value}" }
        entries = entries + (id to Entry(provider, enabled))
        changed()
    }

    fun replace(provider: MusicProvider) = synchronized(lock) {
        validate(provider)
        val old = entries[provider.sourceId] ?: error("plugin provider is not registered")
        entries = entries + (provider.sourceId to old.copy(provider = provider))
        changed()
    }

    fun setEnabled(source: SourceId, enabled: Boolean): Boolean = synchronized(lock) {
        val old = entries[source] ?: return@synchronized false
        if (old.enabled != enabled) {
            entries = entries + (source to old.copy(enabled = enabled))
            changed()
        }
        true
    }

    fun unregister(source: SourceId): Boolean = synchronized(lock) {
        if (source !in entries) return@synchronized false
        entries = entries - source
        changed()
        true
    }

    fun enabledProviders(): List<MusicProvider> = entries.values.filter { it.enabled }.map { it.provider }
}
