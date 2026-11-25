package melox.plugin

import melox.music.model.SourceId
import melox.music.provider.MusicProviderRegistry
import melox.provider.local.LocalProvider
import kotlin.test.*

class PluginArtworkAccessTest {
    @Test fun unavailableForeignAndStaleArtworkCannotPassCacheAuthorization(): Unit {
        val manifest = PluginManifest(SourceId.parse("plugin:example.artwork")!!, "Art", "1.0.0", 1,
            "index.js", emptySet(), setOf("images.example.com"))
        fun provider() = PluginMusicProvider(manifest, PluginInvoker { _, _ -> error("not invoked") })
        val registry = MusicProviderRegistry(listOf(LocalProvider()))
        val url = PluginArtwork.wrap(manifest, "https://images.example.com/cover.png")!!
        assertNull(PluginArtworkAccess.capture("https://ordinary.example.com/image", registry))
        assertFails { PluginArtworkAccess.capture(url, registry) }
        registry.registerDynamic(provider())
        val access = assertNotNull(PluginArtworkAccess.capture(url, registry))
        access.checkCurrent()
        val foreign = url.replace("images.example.com", "foreign.example.com")
        assertFails { PluginArtworkAccess.capture(foreign, registry) }
        registry.setDynamicEnabled(manifest.sourceId, false)
        assertFails { access.checkCurrent() }
        assertFails { access.bytes() }
        assertFails { PluginArtworkAccess.capture(url, registry) }
        registry.setDynamicEnabled(manifest.sourceId, true)
        assertFails { access.checkCurrent() }
        val restored = assertNotNull(PluginArtworkAccess.capture(url, registry))
        registry.replaceDynamic(provider())
        assertFails { restored.checkCurrent() }
        assertNotNull(PluginArtworkAccess.capture(url, registry)).checkCurrent()
    }
}
