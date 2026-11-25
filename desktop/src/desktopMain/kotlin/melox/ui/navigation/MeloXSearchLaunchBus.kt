package melox.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import melox.music.model.MusicSource
import melox.music.model.SourceId
import melox.network.MeloXSearchKind

data class MeloXSearchLaunch(val query: String, val kind: MeloXSearchKind, val source: MusicSource? = null, val nonce: Long = System.nanoTime(),
    val sourceId: SourceId? = source?.let(SourceId::fromBuiltIn))

/** Same retained request/conditional consumption as Android SearchScreen's launch bus. */
object MeloXSearchLaunchBus {
    var request by mutableStateOf<MeloXSearchLaunch?>(null)
        private set
    fun post(query: String, kind: MeloXSearchKind, source: MusicSource? = null) { request = MeloXSearchLaunch(query, kind, source) }
    fun postSourceId(query: String, kind: MeloXSearchKind, source: SourceId) {
        request = MeloXSearchLaunch(query, kind, source.builtIn, sourceId = source)
    }
    fun consume(request: MeloXSearchLaunch) { if (this.request == request) this.request = null }
}
