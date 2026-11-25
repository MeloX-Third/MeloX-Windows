package melox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import melox.music.model.*
import melox.music.provider.*
import melox.settings.MeloXContentLayout
import melox.settings.MeloXSettingsRuntime
import melox.ui.desktop.DesktopHomeMediaCard
import melox.ui.desktop.desktopPromotedDragScroll
import melox.ui.navigation.MeloXDetailOverlay
import melox.ui.foundation.MeloXIosTopBar

private data class ProviderLibraryEntry(
    val key: String, val title: String, val subtitle: String, val artwork: String?,
    val playlist: MusicPlaylistSummary? = null, val album: MusicAlbumSummary? = null, val artist: MusicArtistSummary? = null,
)

/** Source-native collections; data is shared, phone and Desktop presentation stay independent. */
@Composable
internal fun MeloXProviderLibraryScreen(source: MusicSource, requestedPage: String?, onLogin: () -> Unit) {
    MeloXProviderLibraryScreen(SourceId.fromBuiltIn(source), requestedPage, onLogin)
}

@Composable
internal fun MeloXProviderLibraryScreen(source: SourceId, requestedPage: String?, onLogin: () -> Unit) {
    val registry = remember { MeloXMusicProviders.create() }
    val generation by registry.changes.collectAsState()
    val revision = when (source.builtIn) {
        MusicSource.Spotify -> melox.provider.spotify.SpotifySessionStore.revision.collectAsState().value
        MusicSource.YouTubeMusic -> melox.provider.youtubemusic.YouTubeSessionStore.revision.collectAsState().value
        else -> 0L
    }
    key(source, revision, generation) {
        val textStyle = if (MeloXSettingsRuntime.desktopLayoutEnabled) androidx.compose.ui.text.TextStyle(
            fontFamily = melox.ui.theme.MeloXDesktopTextFontFamily, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            else LocalTextStyle.current
        CompositionLocalProvider(LocalTextStyle provides textStyle) { ProviderLibraryContent(source, requestedPage, onLogin) }
    }
}

@Composable
private fun ProviderLibraryContent(source: SourceId, requestedPage: String?, onLogin: () -> Unit) {
    val registry = remember { MeloXMusicProviders.create() }
    val generation = remember { registry.generation }
    val provider = remember(source) { registry[source] }
    val sourceLabel = provider?.displayName ?: source.displayName
    val sourceTag = source.builtIn?.name ?: source.value
    val pages = source.builtIn?.let(MeloXContentLayout::libraryPages) ?: buildList {
        if (provider?.supports(MusicCapability.Library) == true && provider is UserLibraryCapability) add("Playlists")
        if (provider is LibraryCollectionCapability && provider.supports(MusicCapability.Albums)) add("Albums")
        if (provider is LibraryCollectionCapability && provider.supports(MusicCapability.Artists)) add("Artists")
    }
    var section by remember(source) { mutableStateOf(source.builtIn?.let(MeloXContentLayout::initialLibraryPage) ?: pages.firstOrNull().orEmpty()) }
    LaunchedEffect(source, requestedPage) { requestedPage?.takeIf { it in pages }?.let { section = it } }
    LaunchedEffect(source, section) { source.builtIn?.let { MeloXContentLayout.rememberLibraryPage(it, section) } }
    var entries by remember(source, section) { mutableStateOf<List<ProviderLibraryEntry>>(emptyList()) }
    var tracks by remember(source, section) { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var loading by remember(source, section) { mutableStateOf(true) }
    var error by remember(source, section) { mutableStateOf<String?>(null) }
    var loggedIn by remember(source) { mutableStateOf(source.builtIn == MusicSource.Local) }
    var page by remember(source, section) { mutableIntStateOf(1) }
    var more by remember(source, section) { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var detail by remember(source, section) { mutableStateOf<ProviderLibraryEntry?>(null) }
    var menuSong by remember(source, section) { mutableStateOf<melox.model.SearchSong?>(null) }
    var menuAnchor by remember(source, section) { mutableStateOf<IntOffset?>(null) }
    val queue by melox.playback.PlaybackCommands.queue.collectAsState()
    val playback by melox.playback.MeloXPlaybackOutput.state.collectAsState()
    val scope = key(source, section) { rememberCoroutineScope() }
    val desktop = MeloXSettingsRuntime.desktopLayoutEnabled
    val title = when (section) { "Songs" -> "歌曲"; "Albums" -> "收藏专辑"; "Artists" -> "关注歌手"; else -> "歌单" }
    suspend fun <T> currentResult(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }.also {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        check(registry.generation == generation && registry[source] === provider) { "音源已变化" }
    }
    fun checkIdentity(id: MusicResourceId) { require(id.sourceId == source) { "音乐库内容来源不匹配" } }
    suspend fun load(next: Int) {
        loading = true; error = null
        try {
                check(provider != null && pages.isNotEmpty()) { "$sourceLabel 暂不提供音乐库" }
                if (source.builtIn != MusicSource.Local) loggedIn = currentResult { (provider as? UserLibraryCapability)?.accountSummary() }?.also {
                    require(it.sourceId == source) { "账号来源不匹配" }
                } != null
                if (!loggedIn) { entries = emptyList(); tracks = emptyList(); more = false; return }
                val size = 50
                when (section) {
                    "Songs" -> {
                        val result = currentResult { (provider as LocalAggregationCapability).aggregationTracks(next, size) }
                        result.items.forEach { checkIdentity(it.id) }
                        tracks = (if (next == 1) result.items else tracks + result.items).distinctBy { it.id }
                        more = result.hasMore
                    }
                    "Albums" -> {
                        val result = currentResult { (provider as LibraryCollectionCapability).savedAlbums(next, size) }
                        result.items.forEach { checkIdentity(it.id) }
                        val values = result.items.map { ProviderLibraryEntry(it.id.value, it.title, it.artists.joinToString { a -> a.name }, it.artworkUrl, album = it) }
                        entries = (if (next == 1) values else entries + values).distinctBy { it.key }; more = result.hasMore
                    }
                    "Artists" -> {
                        val result = currentResult { (provider as LibraryCollectionCapability).followedArtists(next, size) }
                        result.items.forEach { checkIdentity(it.id) }
                        val values = result.items.map { ProviderLibraryEntry(it.id.value, it.name, sourceLabel, it.artworkUrl, artist = it) }
                        entries = (if (next == 1) values else entries + values).distinctBy { it.key }; more = result.hasMore
                    }
                    else -> {
                        val result = currentResult {
                            if (source.builtIn == MusicSource.Local) (provider as PlaylistWriteCapability).writablePlaylists(next, size)
                            else (provider as UserLibraryCapability).userPlaylists(next, size)
                        }
                        result.items.forEach { checkIdentity(it.id) }
                        val values = result.items.map { ProviderLibraryEntry(it.id.value, it.title, it.creatorName.orEmpty(), it.artworkUrl, playlist = it) }
                        entries = (if (next == 1) values else entries + values).distinctBy { it.key }; more = result.hasMore
                    }
                }
                page = next
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "音乐库读取失败" }
        finally { loading = false }
    }
    LaunchedEffect(source, section, reload) { load(1) }
    var creating by remember(source) { mutableStateOf(false) }
    var createName by remember(source) { mutableStateOf("") }
    var createBusy by remember(source) { mutableStateOf(false) }
    var createError by remember(source) { mutableStateOf<String?>(null) }
    val writer = provider as? PlaylistSyncCapability
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .testTag(if (desktop) "desktop-provider-library:$sourceTag" else "mobile-provider-library:$sourceTag")
        .semantics { stateDescription = "$section;count=${if (section == "Songs") tracks.size else entries.size};more=$more" }) {
        val scroll = key(source, section) { rememberLazyListState() }
        Column(Modifier.fillMaxSize()) {
        if (!desktop) {
            MeloXIosTopBar("音乐库", actions = {
                if (section == "Playlists" && writer != null && loggedIn) TextButton(
                    { createError = null; creating = true }, Modifier.testTag("provider-library-create")) { Text("新建歌单") }
            })
            MeloXProviderLibraryPicker(section, { section = it }, pages.map { value ->
                value to when (value) { "Albums" -> "专辑"; "Artists" -> "歌手"; "Songs" -> "歌曲"; else -> "歌单" }
            }, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().then(if (desktop) Modifier.desktopPromotedDragScroll(scroll) else Modifier), state = scroll,
            contentPadding = PaddingValues(horizontal = if (desktop) 34.dp else 0.dp, vertical = if (desktop) 28.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(if (desktop && section != "Songs") 18.dp else if (desktop) 2.dp else 0.dp)) {
            if (desktop) item("title") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, fontWeight = FontWeight.Bold, fontSize = if (desktop) 32.sp else 28.sp)
                        Text(sourceLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    if (section == "Playlists" && writer != null && loggedIn) TextButton({ createError = null; creating = true }, Modifier.testTag("provider-library-create")) { Text("新建歌单") }
                }
                if (source.builtIn == null && pages.size > 1) {
                    Spacer(Modifier.height(14.dp))
                    melox.ui.desktop.DesktopSettingsPicker("音乐库分类", section, pages.map { value ->
                        value to when (value) { "Albums" -> "专辑"; "Artists" -> "歌手"; else -> "歌单" }
                    }, "desktop-plugin-library-section") { section = it }
                }
            }
            if (loading && entries.isEmpty() && tracks.isEmpty()) item { CircularProgressIndicator(Modifier.size(22.dp)) }
            error?.let { message -> item("error") {
                Column { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("provider-library-error"))
                    TextButton({ reload++ }, Modifier.testTag("provider-library-retry")) { Text("重试") } }
            } }
            if (!loading && !loggedIn && error == null) item("login") {
                Text("请先登录 $sourceLabel")
                if (source.builtIn != null) TextButton(onLogin, Modifier.testTag("provider-library-login")) { Text("登录") }
                else Text("插件账号尚未授权", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!loading && loggedIn && entries.isEmpty() && tracks.isEmpty() && error == null) item("empty") {
                Text("$sourceLabel 暂无$title", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("provider-library-empty"))
            }
            if (desktop && entries.isNotEmpty()) item("grid") {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = ((maxWidth.value + 20) / 165).toInt().coerceAtLeast(1)
                    val width = ((maxWidth.value - (columns - 1) * 20) / columns).coerceAtMost(205f).dp
                    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        entries.chunked(columns).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            row.forEach { entry -> DesktopHomeMediaCard(entry.title, entry.subtitle, entry.artwork, width,
                                "provider-library-card:$sourceTag:${entry.key}", onOpen = { detail = entry }, isCircular = section == "Artists") }
                        } }
                    }
                }
            } else items(entries, key = { it.key }) { entry ->
                Row(Modifier.fillMaxWidth().height(66.dp).clickable { detail = entry }
                    .testTag("provider-library-card:$sourceTag:${entry.key}").padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    MeloXArtworkImage(entry.artwork, MaterialTheme.colorScheme.surfaceVariant, Modifier.size(44.dp).clip(
                        if (section == "Artists") androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(6.dp)))
                    Column(Modifier.padding(start = 14.dp)) { Text(entry.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(entry.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
                }
            }
            if (tracks.isNotEmpty()) item("play-all") {
                val playAll = { melox.playback.ProviderPlaybackCommands.playQueue(tracks, tracks.first().id); Unit }
                if (desktop) TextButton(playAll, Modifier.testTag("provider-library-play-all")) { Text("播放全部") }
                else Box(Modifier.testTag("provider-library-play-all")) { MeloXPlayAllRow(playAll) }
            }
            itemsIndexed(tracks, key = { _, track -> track.id.value }) { index, track ->
                val song = MeloXLegacyUiBridge.track(track)
                Box(Modifier.fillMaxWidth().testTag("provider-library-track:${track.id.value}")) {
                    if (desktop) melox.ui.desktop.DesktopCollectionTrackRow(index + 1, song,
                        queue.songs.getOrNull(queue.index)?.providerTrack?.id == track.id, playback.isPlaying, false,
                        { melox.playback.ProviderPlaybackCommands.playQueue(tracks, track.id) },
                        { menuAnchor = it; menuSong = song }, showsArtwork = true)
                     else Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { MeloXLibraryTrackRow(song) { melox.playback.ProviderPlaybackCommands.playQueue(tracks, track.id) } }
                            IconButton({ menuAnchor = null; menuSong = song }, Modifier.testTag("provider-library-song-menu:${track.id.value}")) {
                                melox.ui.foundation.MeloXSymbolIcon(melox.ui.foundation.MeloXSymbol.Ellipsis, size = 20)
                            }
                        }
                        HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
                    }
                }
            }
            if (more) item("more") { TextButton({ if (!loading) scope.launch { load(page + 1) } }, Modifier.testTag("provider-library-more"), enabled = !loading) { Text(if (loading) "加载中…" else "加载更多") } }
            item("clearance") { Spacer(Modifier.height(150.dp)) }
        }
        }
        MeloXDetailOverlay(detail, { detail = null }) { entry ->
            key(source, entry.key, section) {
                if (entry.playlist != null) PlaylistDetailScreen(MeloXLegacyUiBridge.playlist(entry.playlist), onBack = { detail = null })
                else ProviderCollectionDetail(entry.title, entry.subtitle, entry.artwork, load = {
                    currentResult {
                        if (entry.album != null) (provider as AlbumCapability).albumDetail(entry.album, pageSize = 100).tracks
                        else (provider as ArtistCapability).artistDetail(requireNotNull(entry.artist), pageSize = 100).tracks
                    }.also { values -> values.forEach { checkIdentity(it.id) } }
                }, onBack = { detail = null }, resourceId = entry.album?.id ?: entry.artist?.id)
            }
        }
    }
    menuSong?.let { MeloXSongActionsOverlay(it, tracks.map(MeloXLegacyUiBridge::track), true, { menuSong = null }, desktopAnchor = menuAnchor) }
    if (creating && writer != null) AlertDialog(onDismissRequest = { if (!createBusy) creating = false },
        shape = RoundedCornerShape(if (desktop) 10.dp else 28.dp),
        title = { Text("新建歌单") }, text = { Column {
            OutlinedTextField(createName, { createName = it }, label = { Text("名称") }, modifier = Modifier.testTag("provider-playlist-name"))
            createError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton({
            createBusy = true
            createError = null
            scope.launch {
                try { withContext(Dispatchers.IO) { writer.createPlaylist(createName.trim()) }; creating = false; createName = ""; reload++ }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { createError = failure.message ?: "歌单创建失败" }
                finally { createBusy = false }
            }
        }, enabled = !createBusy && createName.isNotBlank(), modifier = Modifier.testTag("provider-playlist-create-confirm")) { Text("创建") } },
        dismissButton = { TextButton({ creating = false }, enabled = !createBusy) { Text("取消") } })
}
