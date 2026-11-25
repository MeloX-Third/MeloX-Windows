package melox.plugin

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import melox.music.model.SourceId
import kotlin.test.*

class PluginPackageStoreTest {
    private val source = SourceId.parse("plugin:example.store")!!
    private fun archive(version: String): ByteArray = ByteArrayOutputStream().apply {
        ZipOutputStream(this).use { zip ->
            mapOf("manifest.json" to """{"id":"example.store","name":"Store","version":"$version","apiVersion":1,"entry":"index.js","capabilities":[]}""",
                "index.js" to "globalThis.meloxPlugin={};").forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
    }.toByteArray()

    @Test fun installUpdateReloadAndUninstallPreservePublisherAndExplicitEnabledIntent() {
        val root = Files.createTempDirectory("plugin-store").toFile()
        try {
            val store = PluginPackageStore(root)
            store.install(archive("1.0.0"))
            assertTrue(store.enabledSources().isEmpty())
            store.setEnabledSources(setOf(source))
            assertFails { store.install(archive("1.0.0")) }
            assertFails { store.update(source, archive("1.0.0")) {} }
            assertFails { store.update(source, archive("2.0.0")) { error("bad entry") } }
            assertEquals("1.0.0", store.read(source)?.manifest?.version)
            store.update(source, archive("1.1.0")) {}
            val restored = PluginPackageStore(root)
            assertEquals("1.1.0", restored.read(source)?.manifest?.version)
            assertEquals(setOf(source), restored.enabledSources())
            assertEquals(PluginPackageTrust.UNSIGNED, restored.publisherFingerprint(source))
            assertEquals(source, restored.installed().single().sourceId)
            restored.setEnabledSources(emptySet())
            restored.uninstall(source)
            assertNull(restored.read(source))
            assertEquals(PluginPackageTrust.UNSIGNED, restored.publisherFingerprint(source))
        } finally { root.deleteRecursively() }
    }

    @Test fun decisionInterruptionAndFailedCommitRecoverOldPackageAndTrust() {
        for (stage in listOf("decision-durable", "archive-durable", "publisher-durable", "rollback-archive-durable", "rollback-publisher-durable")) {
            val root = Files.createTempDirectory("plugin-store-fault").toFile()
            try {
                PluginPackageStore(root).install(archive("1.0.0"))
                val faulty = PluginPackageStore(root) { reached ->
                    if (reached == stage || (stage.startsWith("rollback-") && reached == "publisher-durable")) throw java.io.IOException("fixture")
                }
                assertFails { faulty.update(source, archive("2.0.0")) {} }
                val restored = PluginPackageStore(root)
                assertEquals("1.0.0", restored.read(source)?.manifest?.version, stage)
                assertEquals(PluginPackageTrust.UNSIGNED, restored.publisherFingerprint(source), stage)
                assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".transaction") }, stage)
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun damagedStateIsPreservedAndBadPackageIsQuarantined() {
        val root = Files.createTempDirectory("plugin-store-corrupt").toFile()
        try {
            val store = PluginPackageStore(root)
            store.install(archive("1.0.0"))
            val state = java.io.File(root, "enabled.json").apply { writeText("{broken") }
            assertFails { store.enabledSources() }
            assertEquals("{broken", state.readText())
            val file = root.listFiles()!!.single { it.name.endsWith(".plugin") }
            file.writeText("damaged")
            val damaged = store.installed().single()
            assertNull(damaged.manifest)
            val quarantined = java.io.File(root, "quarantine/${file.name}")
            assertEquals("damaged", quarantined.readText())
            assertFails { store.dismissDamaged("../enabled.json") }
            store.dismissDamaged(damaged.storageKey!!)
            assertFalse(quarantined.exists())
        } finally { root.deleteRecursively() }
    }
}
