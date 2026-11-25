package melox.plugin

import melox.music.model.SourceId
import java.nio.file.Files
import org.json.JSONObject
import kotlin.test.*

class PluginPrivateStorageTest {
    private val first = SourceId.parse("plugin:example.storage")!!
    private val second = SourceId.parse("plugin:example.other")!!

    @Test fun dataSurvivesReloadAndClearingOnlyRemovesRequestedNamespace() {
        val root = Files.createTempDirectory("plugin-storage").toFile()
        try {
            val anonymous = PluginStorage(first, base = root)
            val account = PluginStorage(first, "slot", root)
            val other = PluginStorage(second, "slot", root)
            anonymous.execute("storage.set", "key", "anonymous")
            account.execute("storage.set", "key", "account")
            other.execute("storage.set", "key", "other")
            assertEquals("account", PluginStorage(first, "slot", root).execute("storage.get", "key"))
            assertEquals("anonymous", anonymous.execute("storage.get", "key"))
            PluginStorage.clearAccount(first, "slot", root)
            assertEquals(JSONObject.NULL, account.execute("storage.get", "key"))
            assertEquals("other", other.execute("storage.get", "key"))
            PluginStorage.clear(first, root)
            assertEquals(JSONObject.NULL, anonymous.execute("storage.get", "key"))
            assertEquals("other", other.execute("storage.get", "key"))
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectedWritesAndCorruptionPreserveCommittedBytes() {
        val root = Files.createTempDirectory("plugin-storage-limits").toFile()
        try {
            val store = PluginStorage(first, base = root)
            repeat(64) { store.execute("storage.set", "key$it", "value") }
            val file = root.walkTopDown().single { it.isFile }
            val before = file.readBytes()
            assertFails { store.execute("storage.set", "overflow", "value") }
            assertFails { store.execute("storage.set", "key0", "字".repeat(6000)) }
            assertFails { store.execute("storage.get", "../key") }
            assertFails { store.execute("unknown", "key0") }
            assertContentEquals(before, file.readBytes())
            store.execute("storage.remove", "key0")
            store.execute("storage.set", "new", "value")
            file.writeText("{broken")
            assertFails { store.execute("storage.set", "new", "replacement") }
            assertEquals("{broken", file.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun diagnosticRingStoresOnlyAllowlistedHostFieldsAndIsSourceScoped() {
        val root = Files.createTempDirectory("plugin-diagnostics").toFile()
        try {
            val log = PluginDiagnostics(first, root)
            repeat(105) { log.record("search", "PLUGIN_EXECUTION_FAILED", it.toLong()) }
            log.record("https://secret.example/token", "LOGIN_REQUIRED", Long.MAX_VALUE)
            val restored = PluginDiagnostics(first, root).lines()
            assertEquals(100, restored.size)
            assertTrue(restored.last().contains("unknown"))
            assertTrue(restored.last().endsWith("3600000ms"))
            assertFalse(restored.any { "secret" in it })
            assertFails { log.record("search", "Bearer secret", 0) }
            assertTrue(PluginDiagnostics(second, root).lines().isEmpty())
            val file = root.walkTopDown().single { it.isFile }
            assertFalse(file.readText().contains("secret"))
            file.writeText("[broken")
            assertFails { log.record("search", "LOGIN_REQUIRED", 0) }
            assertEquals("[broken", file.readText())
            log.clear()
            assertTrue(log.lines().isEmpty())
        } finally { root.deleteRecursively() }
    }
}
