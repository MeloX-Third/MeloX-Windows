package melox.ui.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import melox.account.NeteaseSessionStore
import melox.dailyhistory.DailyHistoryStore
import melox.model.SearchSong
import melox.playback.PlaybackCommands

@Composable
internal fun MeloXDesktopDailyHistory(onBack: () -> Unit) {
    val account = DailyHistoryStore.accountKey(NeteaseSessionStore.readCookie())
    var dates by remember(account) { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember(account) { mutableStateOf<String?>(null) }
    var songs by remember(account) { mutableStateOf<List<SearchSong>>(emptyList()) }
    var loading by remember(account) { mutableStateOf(true) }
    var error by remember(account) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var action by remember(account, selected) { mutableStateOf<SearchSong?>(null) }
    var anchor by remember { mutableStateOf<androidx.compose.ui.unit.IntOffset?>(null) }
    val queue by PlaybackCommands.queue.collectAsState()
    val playback by melox.playback.MeloXPlaybackOutput.state.collectAsState()
    LaunchedEffect(account, selected, retry) {
        songs = emptyList()
        error = null
        val date = selected
        loading = true
        try {
            if (date == null) dates = DailyHistoryStore.instance.listDates(account)
            else {
                val entry = DailyHistoryStore.instance.read(account, date)
                songs = entry?.songs.orEmpty()
                if (entry == null) error = desktopPlatformString("daily-history-unreadable")
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: desktopPlatformString("daily-history-unreadable") }
        finally { loading = false }
    }
    if (selected != null) {
        MeloXDesktopCollectionDetail(
            kind = desktopPlatformString("daily-history"), title = selected.orEmpty(), subtitle = null,
            metadata = desktopFormat("ui.common.song_count", songs.size), description = null,
            artworkUrl = songs.firstOrNull()?.artworkUrl, songs = songs, loading = loading, error = error,
            playingId = queue.songs.getOrNull(queue.index)?.id, isPlaying = playback.isPlaying,
            onBack = { selected = null },
            onPlayAll = { songs.firstOrNull()?.let { PlaybackCommands.playQueue(songs, it.id) } },
            onShuffle = { songs.shuffled().let { shuffled -> shuffled.firstOrNull()?.let { PlaybackCommands.playQueue(shuffled, it.id) } } },
            onRetry = { retry++ }, onSong = { PlaybackCommands.playQueue(songs, it.id) },
            onMore = { action = it }, onMenuAnchor = { anchor = it }, tag = "desktop-daily-history-detail")
        action?.let { MeloXDesktopSongMenu(it, { action = null }, anchor) }
        return
    }
    val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().desktopPromotedDragScroll(scroll).testTag("desktop-daily-history"),
        state = scroll, contentPadding = PaddingValues(34.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { DesktopDialogButton("ui.common.back", onClick = onBack) }
        item { Text(desktopPlatformString("daily-history"), style = MaterialTheme.typography.headlineLarge) }
        when {
            loading -> item { CircularProgressIndicator() }
            error != null -> item {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                DesktopDialogButton("ui.common.retry", onClick = { retry++ })
            }
            dates.isEmpty() -> item { Text(desktopPlatformString("daily-history-empty")) }
            else -> items(dates, key = { it }) { date ->
                Text(date, Modifier.fillMaxWidth().testTag("daily-history-date:$date")
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) { selected = date }.padding(16.dp))
            }
        }
    }
}
