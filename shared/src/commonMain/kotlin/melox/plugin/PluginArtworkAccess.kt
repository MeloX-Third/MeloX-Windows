package melox.plugin

import melox.music.provider.MeloXMusicProviders
import melox.music.provider.MusicProviderRegistry

/** Host-owned authorization captured before any image/cache access. */
class PluginArtworkAccess private constructor(
    private val request: PluginArtwork.Request,
    private val registry: MusicProviderRegistry,
    private val provider: PluginMusicProvider,
    private val generation: Long,
) {
    fun checkCurrent() {
        check(registry.generation == generation && registry[request.source] === provider) { "artwork source changed" }
    }

    fun bytes(): ByteArray {
        checkCurrent()
        return PluginHttpBroker(provider.manifest, accountCurrent = {
            registry.generation == generation && registry[request.source] === provider
        }).use { it.imageBytes(request.url) }.also { checkCurrent() }
    }

    companion object {
        fun isPluginArtwork(raw: String): Boolean = raw.startsWith("melox-plugin-image:", ignoreCase = true)
        fun capture(raw: String, registry: MusicProviderRegistry = MeloXMusicProviders.create()): PluginArtworkAccess? {
            if (!isPluginArtwork(raw)) return null
            val request = PluginArtwork.parse(raw)
            val generation = registry.generation
            val provider = registry[request.source] as? PluginMusicProvider ?: error("artwork source unavailable")
            PluginHttpBroker(provider.manifest).use { it.authorizeMediaUrl(request.url) }
            return PluginArtworkAccess(request, registry, provider, generation).also { it.checkCurrent() }
        }
    }
}
