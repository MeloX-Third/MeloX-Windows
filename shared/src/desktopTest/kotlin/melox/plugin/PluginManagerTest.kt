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

    @Test fun diagnosticsSurviveDisableAndCorruptionCanBeClearedWithoutLoadingRuntime() {
        val root = Files.createTempDirectory("manager-diagnostics").toFile()
        val registry = MusicProviderRegistry(listOf(melox.provider.local.LocalProvider()))
        var failedRead = false
        var erased = 0
        val manager = PluginManager(PluginPackageStore(root), registry,
            PluginManager.ProviderFactory { error("must remain disabled") },
            readPersistentDiagnostics = { checked ->
                assertEquals(source, checked)
                check(!failedRead) { "private-file-content" }
                listOf("host event")
            }, erasePersistentDiagnostics = { checked -> assertEquals(source, checked); erased++; failedRead = false })
        try {
            manager.install(archive("1.0.0"))
            assertTrue(manager.diagnostics(source).contains("host event"))
            assertTrue(manager.diagnostics(source).contains("运行时未加载"))
            failedRead = true
            val damaged = manager.diagnostics(source)
            assertTrue(damaged.any { "原文件已保留" in it })
            assertFalse(damaged.any { "private-file-content" in it })
            manager.clearDiagnostics(source)
            assertEquals(1, erased)
            assertTrue(manager.diagnostics(source).contains("host event"))
            manager.uninstall(source)
            assertEquals(2, erased)
            assertTrue(manager.diagnostics(source).single().startsWith("插件未安装"))
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

    @Test fun oauthCommitPreservesClaimedLoginWhileOrdinaryAccountChangesCancelIt() {
        val root = Files.createTempDirectory("manager-oauth").toFile()
        val registry = MusicProviderRegistry(listOf(melox.provider.local.LocalProvider()))
        var cancelled = 0
        val manager = PluginManager(PluginPackageStore(root), registry, PluginManager.ProviderFactory {
            PluginManager.LoadedProvider(object : MusicProvider {
                override val sourceId = this@PluginManagerTest.source
                override val displayName = "Test"
                override val capabilities = emptySet<MusicCapability>()
            }, Closeable {})
        }, cancelAuthorization = { cancelled++ })
        try {
            val manifest = manager.install(archive("1.0.0"))
            manager.enable(source)
            manager.commitOAuthAccount(source, manifest) {
                assertEquals(0, cancelled)
                assertNull(registry[source])
            }
            assertNotNull(registry[source])
            manager.changeAccount(source, manifest) {
                assertEquals(1, cancelled)
                assertNull(registry[source])
            }
            manager.disable(source)
            assertFails { manager.commitOAuthAccount(source, manifest) { error("must not save") } }
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
