package melox.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import melox.ui.desktop.DesktopSettingsButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import melox.lyrics.*
import melox.music.model.*
import melox.music.provider.*

/** Results and previews belong to the selected source instance and registry generation. */
@Composable
internal fun MeloXLyricBindingDialog(track: MusicTrack, onDismiss: () -> Unit) {
    val registry = remember { MeloXMusicProviders.create() }
    val generation by registry.changes.collectAsState()
    val providers = remember(generation) { (registry.providers + registry.enabledDynamicProviders()).filter {
        it is SearchCapability && it is LyricsCapability && it.supports(MusicCapability.Search) && it.supports(MusicCapability.Lyrics)
    } }
    var source by remember { mutableStateOf(track.id.sourceId) }
    var query by remember { mutableStateOf("${track.title} ${track.artistText}".trim()) }
    var amll by remember(source) { mutableStateOf(false) }
    var results by remember(source, generation) { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var selected by remember(source, generation) { mutableStateOf<MusicTrack?>(null) }
    var preview by remember(source, generation) { mutableStateOf<LyricsDocument?>(null) }
    var error by remember(source, generation) { mutableStateOf<String?>(null) }
    var busy by remember(source, generation) { mutableStateOf(false) }
    var searched by remember(source, generation) { mutableStateOf(false) }
    var page by remember(source, generation) { mutableIntStateOf(0) }
    var hasMore by remember(source, generation) { mutableStateOf(false) }
    var searchedQuery by remember(source, generation) { mutableStateOf("") }
    val scope = key(source, generation) { rememberCoroutineScope() }
    fun amllQuery(candidate: MusicTrack): AmlldbLyricQuery? = when (candidate.id.sourceId.builtIn) {
        MusicSource.Netease -> candidate.id.value.toLongOrNull()?.let { AmlldbLyricQuery.Netease(it) }
        MusicSource.QQMusic -> AmlldbLyricQuery.QQ(candidate.id.value)
        MusicSource.Spotify -> AmlldbLyricQuery.Spotify(candidate.id.value)
        MusicSource.AppleMusic -> AmlldbLyricQuery.AppleMusic(candidate.id.value)
        else -> null
    }
    fun current(provider: MusicProvider, stamp: Long) = registry.generation == stamp && registry[provider.sourceId] === provider
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
      Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(.9f).testTag("lyric-binding-dialog"), shape = RoundedCornerShape(10.dp)) {
       Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("选择歌词来源")
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("搜索歌曲并预览逐字歌词，确认后绑定到当前歌曲。")
            providers.forEach { provider ->
                DesktopSettingsButton((if (source == provider.sourceId) "✓ " else "") + provider.displayName,
                    enabled = !busy, onClick = { source = provider.sourceId })
            }
            OutlinedTextField(query, { query = it }, label = { Text("歌曲或歌手") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (source.builtIn in setOf(MusicSource.Netease, MusicSource.QQMusic, MusicSource.Spotify, MusicSource.AppleMusic)) {
                DesktopSettingsButton(if (amll) "✓ AMLL 逐字歌词" else "使用 AMLL 逐字歌词", enabled = !busy) {
                    amll = !amll; selected = null; preview = null; error = null
                }
            }
            Button(enabled = !busy && query.isNotBlank() && providers.any { it.sourceId == source }, onClick = {
                val provider = registry[source] ?: return@Button
                val stamp = registry.generation
                val text = query
                scope.launch {
                    busy = true; searched = false; error = null; selected = null; preview = null; results = emptyList(); hasMore = false
                    try {
                        val response = kotlinx.coroutines.withTimeout(30_000) { withContext(Dispatchers.IO) { (provider as SearchCapability).searchSongs(text, 1, 30) } }
                        if (current(provider, stamp)) {
                            results = response.items.filter { it.id.sourceId == source }; searched = true
                            page = 1; hasMore = response.hasMore; searchedQuery = text
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { if (current(provider, stamp)) error = failure.message ?: "搜索失败" }
                    finally { busy = false }
                }
            }) { Text("搜索") }
            if (busy) CircularProgressIndicator(Modifier.size(24.dp))
            if (providers.isEmpty()) Text("暂无支持搜索歌词的来源")
            if (searched && results.isEmpty() && !busy) Text("没有找到匹配歌曲，请修改关键词后重试")
            results.forEach { candidate -> TextButton(enabled = !busy, onClick = {
                val provider = registry[source] ?: return@TextButton
                val stamp = registry.generation
                scope.launch {
                    busy = true; error = null; selected = null; preview = null
                    try {
                        val document = withContext(Dispatchers.IO) {
                            if (amll) AmlldbLyricsClient().lyrics(requireNotNull(amllQuery(candidate)), requestedLyricPreference())
                            else (provider as LyricsCapability).lyrics(candidate, requestedLyricPreference())
                        }
                        if (current(provider, stamp)) {
                            selected = candidate; preview = document
                            if (document.lines.none { it.syllables.isNotEmpty() }) error = "此结果没有逐字歌词，无法建立强绑定"
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { if (current(provider, stamp)) error = failure.message ?: "歌词加载失败" }
                    finally { busy = false }
                }
            }) { Text("${candidate.title} · ${candidate.artistText}") } }
            if (hasMore) DesktopSettingsButton("加载更多", enabled = !busy) {
                val provider = registry[source] ?: return@DesktopSettingsButton
                val stamp = registry.generation
                val nextPage = page + 1
                scope.launch {
                    busy = true; error = null
                    try {
                        val response = kotlinx.coroutines.withTimeout(30_000) { withContext(Dispatchers.IO) {
                            (provider as SearchCapability).searchSongs(searchedQuery, nextPage, 30)
                        } }
                        if (current(provider, stamp)) {
                            results = (results + response.items.filter { it.id.sourceId == source }).distinctBy { it.id }
                            page = nextPage; hasMore = response.hasMore
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { if (current(provider, stamp)) error = failure.message ?: "加载失败，请重试" }
                    finally { busy = false }
                }
            }
            preview?.lines?.take(8)?.forEach { Text(it.text) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
         DesktopSettingsButton("解除当前歌曲绑定", enabled = !busy && LyricBindingStore.read(track.id) != null) {
             LyricBindingStore.remove(track.id); onDismiss()
         }
         Spacer(Modifier.weight(1f))
         DesktopSettingsButton("取消", onClick = onDismiss)
         DesktopSettingsButton("绑定歌词", enabled = !busy && selected != null && preview?.lines?.any { it.syllables.isNotEmpty() } == true, onClick = {
            val candidate = selected ?: return@DesktopSettingsButton
            if (registry[candidate.id.sourceId] == null) return@DesktopSettingsButton
            LyricBindingStore.write(track.id, LyricBinding(if (amll) BoundLyricSource.AmlL else BoundLyricSource.Provider, candidate.id.sourceId.builtIn,
                if (amll) requireNotNull(amllQuery(candidate)).bindingValue() else candidate.id.value,
                candidate.title, candidate.artistText, candidate.durationMs ?: track.durationMs ?: 0,
                candidate.id.sourceId))
            melox.settings.MeloXSettingsPreferences.setBoolean("lyrics_auto_select", true)
            melox.settings.MeloXSettingsPreferences.setBoolean("experimental_lyric_strong_binding", true)
            onDismiss()
        })
        }
       }
      }
    }
}
