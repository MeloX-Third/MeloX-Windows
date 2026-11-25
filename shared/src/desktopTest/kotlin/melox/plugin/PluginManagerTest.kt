package melox.plugin

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import melox.music.model.*
import melox.music.provider.*
import kotlin.test.*

class PluginManagerTest {
    private val source = SourceId.parse("plugin:example.manager")!!
    private fun archive(version: String, domain: String? = null) = ByteArrayOutputStream().apply {
        val manifest = org.json.JSONObject().put("id", "example.manager").put("name", "Manager").put("version", version)
            .put("apiVersion", 1).put("entry", "index.js").put("capabilities", emptyList<String>())
            .put("networkDomains", listOfNotNull(domain)).toString()
        ZipOutputStream(this).use { zip -> mapOf("manifest.json" to manifest, "index.js" to "globalThis.meloxPlugin={};").forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
    }.toByteArray()

    @Test fun installedDisabledThenSharedEnableUpdateRestoreDisableAndUninstall() {
        val root = Files.createTempDirectory("manager").toFile()
        val dynamic = DynamicMusicProviders()
        val ui = MusicProviderRegistry(listOf(melox.provider.local.LocalProvider()), dynamic)
        val playback = MusicProviderRegistry(listOf(melox.provider.local.LocalProvider()), dynamic)
        val store = PluginPackageStore(root)
        var closed = 0
        val factory = PluginManager.ProviderFactory { plugin ->
            PluginManager.LoadedProvider(object : MusicProvider {
                override val sourceId = plugin.manifest.sourceId
                override val displayName = plugin.manifest.version
                override val capabilities = emptySet<MusicCapability>()
            }, Closeable { closed++ })
        }
        val manager = PluginManager(store, ui, factory)
        try {
            manager.install(archive("1.0.0"))
            assertNull(playback[source])
            manager.enable(source)
            assertSame(ui[source], playback[source])
            manager.update(source, archive("1.1.0"))
            assertEquals("1.1.0", playback[source]?.displayName)
            assertEquals(1, closed)
            manager.close()
            assertNull(playback[source])
            assertEquals(setOf(source), store.enabledSources())
            PluginManager(store, ui, factory).use { restored ->
                restored.restore()
                assertNotNull(playback[source])
                restored.disable(source)
                assertNull(playback[source])
                assertTrue(store.enabledSources().isEmpty())
                restored.uninstall(source)
                assertNull(store.read(source))
            }
        } finally { manager.close(); root.deleteRecursively() }
    }

    @Test fun rejectedPermissionAndPreparationLeaveRunningProviderAndOldPackageIntact() {
        val root = Files.createTempDirectory("manager-update").toFile()
        val registry = MusicProviderRegistry(listOf(melox.provider.local.LocalProvider()))
        val store = PluginPackageStore(root)
        var preparations = 0
        val manager = PluginManager(store, registry, PluginManager.ProviderFactory { plugin ->
            preparations++
            check(plugin.manifest.version != "3.0.0")
            PluginManager.LoadedProvider(object : MusicProvider {
                override val sourceId = this@PluginManagerTest.source
                override val displayName = "Test"
                override val capabilities = emptySet<MusicCapability>()
            }, Closeable {})
        })
        try {
            manager.install(archive("1.0.0")); manager.enable(source)
            val original = registry[source]
            val next = archive("2.0.0", "example.com")
            assertFails { manager.update(source, next) }
            assertEquals(1, preparations)
            assertSame(original, registry[source])
            assertEquals("1.0.0", store.read(source)?.manifest?.version)
            manager.update(source, next, PluginArchiveReader.read(next).manifest)
            val updated = registry[source]
            assertFails { manager.update(source, archive("3.0.0")) }
            assertSame(updated, registry[source])
            assertEquals("2.0.0", store.read(source)?.manifest?.version)
        } finally { manager.close(); root.deleteRecursively() }
    }

    @Test fun accountMutationWithdrawsOldProviderBeforeCredentialsChangeAndPreservesPrimaryFailure() {
        val root = Files.createTempDirectory("manager-account").toFile()
        val registry = MusicProviderRegistry(listOf(melox.provider.local.LocalProvider()))
        var closes = 0
        var failPreparation = false
        val manager = PluginManager(PluginPackageStore(root), registry, PluginManager.ProviderFactory {
            check(!failPreparation) { "restore failed" }
            PluginManager.LoadedProvider(object : MusicProvider {
                override val sourceId = this@PluginManagerTest.source
                override val displayName = "Test"
                override val capabilities = emptySet<MusicCapability>()
            }, Closeable { closes++ })
        })
        try {
            val manifest = manager.install(archive("1.0.0"))
            manager.enable(source)
            val primary = IllegalStateException("selection failed")
            val failure = assertFailsWith<IllegalStateException> {
                manager.commitAccount(source, manifest) {
                    assertNull(registry[source]); assertEquals(1, closes)
                    failPreparation = true
                    throw primary
                }
            }
            assertSame(primary, failure)
            assertEquals("restore failed", failure.suppressed.single().message)
            assertNull(registry[source])
            assertEquals(PluginManager.Status.Failed, manager.plugins.value[source]?.status)
        } finally { manager.close(); root.deleteRecursively() }
    }
}
