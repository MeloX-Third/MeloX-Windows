package melox.lyrics

import melox.platform.getPreferences
import melox.music.model.MusicResourceId
import melox.music.model.MusicSource
import melox.music.model.SourceId
import org.json.JSONObject

enum class BoundLyricSource { AmlL, Provider }

data class LyricBinding(
    val source: BoundLyricSource,
    val provider: MusicSource? = null,
    val resourceValue: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val providerSourceId: SourceId? = provider?.let(SourceId::fromBuiltIn),
) {
    fun stableKey(): String = listOf(source.name, providerSourceId?.value.orEmpty(), resourceValue).joinToString(":")
}

object LyricBindingStore {
    private const val PreferencesName = "melox_lyric_bindings"

    private fun prefs() = getPreferences(PreferencesName)

    fun read(playbackId: MusicResourceId): LyricBinding? {
        val raw = prefs().getString(playbackId.key(), null) ?: return null
        return runCatching {
            val value = JSONObject(raw)
            LyricBinding(
                source = BoundLyricSource.valueOf(value.getString("source")),
                provider = SourceId.parse(value.optString("provider"))?.builtIn,
                resourceValue = value.getString("resourceValue"),
                title = value.getString("title"),
                artist = value.getString("artist"),
                durationMs = value.optLong("durationMs"),
                providerSourceId = SourceId.parse(value.optString("provider")),
            )
        }.getOrNull()
    }

    fun write(playbackId: MusicResourceId, binding: LyricBinding) {
        val value = JSONObject()
            .put("source", binding.source.name)
            .put("provider", binding.providerSourceId?.value ?: "")
            .put("resourceValue", binding.resourceValue)
            .put("title", binding.title)
            .put("artist", binding.artist)
            .put("durationMs", binding.durationMs)
        prefs().putString(playbackId.key(), value.toString())
    }

    fun clear() {
        prefs().clear()
    }

    private fun MusicResourceId.key(): String = "${sourceId.value}:$value"
}
