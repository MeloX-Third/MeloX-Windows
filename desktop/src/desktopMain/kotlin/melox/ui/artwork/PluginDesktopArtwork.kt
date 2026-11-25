package melox.ui.artwork

import androidx.compose.runtime.*
import melox.music.provider.MeloXMusicProviders
import melox.plugin.PluginArtworkAccess
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data

/** Recompose visible plugin images when their source is disabled, replaced or uninstalled. */
@Composable
internal fun pluginArtworkRevision(): Long {
    val registry = remember { MeloXMusicProviders.create() }
    val generation by registry.changes.collectAsState()
    return generation
}

internal fun pluginArtworkBytes(source: String): ByteArray? {
    val access = PluginArtworkAccess.capture(source) ?: return null
    val bytes = access.bytes()
    validatePluginArtworkDimensions(bytes)
    access.checkCurrent()
    return bytes
}

internal fun validatePluginArtworkDimensions(bytes: ByteArray) {
    Data.makeFromBytes(bytes).use { data ->
        Codec.makeFromData(data).use { codec ->
            require(codec.width in 1..4096 && codec.height in 1..4096 &&
                codec.width.toLong() * codec.height <= 8_000_000) { "artwork dimensions exceeded" }
        }
    }
}
