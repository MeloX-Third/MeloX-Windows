package melox.music.provider

import melox.platform.MeloXPreferences
import melox.platform.getPreferences
import melox.music.model.MusicSource
import melox.music.model.SourceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Provider selection is local-only. Cross-provider aggregation is deliberately
 * opt-in and defaults to disabled. Even after the global experiment switch is
 * enabled, the source whitelist defaults to the current provider only; MeloX
 * never silently fans a request out to every installed provider.
 */
object MusicProviderSelectionStore {
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    private const val PreferencesName = "melox_music_providers"
    private const val KeySelectedSource = "selected_source"
    private const val KeySelectedSourceId = "selected_source_id"
    private const val KeyUnifiedPlugins = "unified_plugin_sources"
    private const val KeyUnifiedEnabled = "unified_enabled"
    private const val KeyAutomaticFallback = "automatic_source_fallback"
    private const val KeyUnifiedSources = "unified_sources"

    private fun prefs(): MeloXPreferences = getPreferences(PreferencesName)

    fun selectedSourceId(): SourceId = prefs().getString(KeySelectedSourceId, null)
        ?.let(SourceId::parse) ?: SourceId.fromBuiltIn(selectedSource())

    fun setSelectedSourceId(source: SourceId) {
        source.builtIn?.let { setSelectedSource(it); return }
        prefs().putString(KeySelectedSourceId, source.value)
        mutableRevision.update { it + 1 }
    }

    fun unifiedSourceIds(): Set<SourceId> = unifiedSources().mapTo(linkedSetOf(), SourceId::fromBuiltIn) +
        prefs().getStringSet(KeyUnifiedPlugins, emptySet()).orEmpty()
            .mapNotNull(SourceId::parse).filter { it.builtIn == null }

    fun setUnifiedPluginEnabled(source: SourceId, enabled: Boolean) {
        require(source.builtIn == null)
        val values = prefs().getStringSet(KeyUnifiedPlugins, emptySet()).orEmpty().toMutableSet()
        if (enabled) {
            require(values.size < 32 || source.value in values)
            values.add(source.value)
        } else values.remove(source.value)
        prefs().putStringSet(KeyUnifiedPlugins, values)
        mutableRevision.update { it + 1 }
    }

    /** Spotify's public Client ID is configured in its login form, not required to select it. */
    fun visibleSources(): List<MusicSource> =
        MusicSource.entries.filter { source ->
            source != MusicSource.AppleMusic && source != MusicSource.Kuwo
        }

    fun selectedSource(): MusicSource =
        (prefs().getString(KeySelectedSourceId, null)?.let(SourceId::parse)?.builtIn
            ?: prefs().getString(KeySelectedSource, null)
            ?.let { MusicSource.fromStorageValue(it) }
        )?.takeIf { it in visibleSources() }
            ?: MusicSource.Netease

    fun setSelectedSource(source: MusicSource) {
        prefs().putString(KeySelectedSource, source.storageValue)
        prefs().putString(KeySelectedSourceId, source.storageValue)
        mutableRevision.update { it + 1 }
    }

    fun unifiedEnabled(): Boolean = prefs().getBoolean(KeyUnifiedEnabled, false)

    fun setUnifiedEnabled(enabled: Boolean) {
        val preferences = prefs()
        preferences.putBoolean(KeyUnifiedEnabled, enabled)
        if (enabled && !preferences.contains(KeyUnifiedSources)) {
            preferences.putStringSet(
                KeyUnifiedSources,
                setOf(selectedSource().storageValue),
            )
        }
        if (!enabled) {
            // Automatic fallback must never remain active when aggregation is off.
            preferences.putBoolean(KeyAutomaticFallback, false)
        }
        mutableRevision.update { it + 1 }
    }

    fun automaticFallbackEnabled(): Boolean =
        unifiedEnabled() && prefs().getBoolean(KeyAutomaticFallback, false)

    fun setAutomaticFallbackEnabled(enabled: Boolean) {
        val safeEnabled = enabled && unifiedEnabled()
        prefs().putBoolean(KeyAutomaticFallback, safeEnabled)
        mutableRevision.update { it + 1 }
    }

    fun unifiedSources(): Set<MusicSource> {
        val preferences = prefs()
        val raw = preferences.getStringSet(KeyUnifiedSources, null)
        val parsed = raw?.mapNotNullTo(linkedSetOf()) { value ->
            visibleSources().firstOrNull { it.storageValue == value }
        }.orEmpty()
        return parsed.ifEmpty { linkedSetOf(selectedSource()) }
    }

    fun setUnifiedSources(sources: Set<MusicSource>) {
        // An empty whitelist is normalized back to the current provider so a
        // single switch can never accidentally turn into an "all providers" request.
        val safeSources = sources.ifEmpty { setOf(selectedSource()) }
        prefs().putStringSet(KeyUnifiedSources, safeSources.mapTo(linkedSetOf()) { it.storageValue })
        mutableRevision.update { it + 1 }
    }

    fun setUnifiedSourceEnabled(source: MusicSource, enabled: Boolean): Set<MusicSource> {
        val updated = unifiedSources().toMutableSet().apply {
            if (enabled) add(source) else remove(source)
        }
        val normalized = updated.ifEmpty { mutableSetOf(selectedSource()) }
        setUnifiedSources(normalized)
        return normalized.toSet()
    }
}
