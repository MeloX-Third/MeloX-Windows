package melox.plugin

import java.nio.file.Files
import melox.music.model.SourceId
import org.json.JSONObject
import kotlin.test.*

class PluginHostCallsTest {
    private val source = requireNotNull(SourceId.parse("plugin:example.host"))
    private val manifest = PluginManifest(source, "Host", "1.0.0", 1, "index.js", emptySet(), emptySet())

    @Test fun storageCallsAreScopedAndDuplicateIdsCloseInvocation() {
        val root = Files.createTempDirectory("plugin-host-calls").toFile()
        try {
            val storage = PluginStorage(source, "first", root)
            PluginHostCalls("invocation", PluginHttpBroker(manifest), storage, { true }).use { calls ->
                assertFailsWith<IllegalArgumentException> { calls.execute("wrong", 1, "{}") }
                assertTrue(JSONObject(calls.execute("invocation", 1, """{"operation":"storage.set","key":"item","value":"secret-fixture"}""")).getBoolean("ok"))
                assertEquals("secret-fixture", JSONObject(calls.execute("invocation", 2, """{"operation":"storage.get","key":"item"}""")).getString("value"))
                assertEquals(JSONObject.NULL, PluginStorage(source, "other", root).execute("storage.get", "item"))
                val error = JSONObject(calls.execute("invocation", 3, """{"operation":"storage.set","key":"item","value":123}"""))
                assertEquals("INVALID_ARGUMENT", error.getString("error"))
                assertFalse(error.toString().contains("secret-fixture"))
                assertFailsWith<IllegalStateException> { calls.execute("invocation", 2, "{}") }
                assertFailsWith<IllegalStateException> { calls.execute("invocation", 4, "{}") }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun revokedInvocationCannotMutateStorage() {
        val root = Files.createTempDirectory("plugin-host-revoke").toFile()
        try {
            val storage = PluginStorage(source, base = root)
            var current = true
            PluginHostCalls("invocation", PluginHttpBroker(manifest), storage, { current }).use { calls ->
                current = false
                assertFailsWith<IllegalStateException> { calls.execute("invocation", 1,
                    """{"operation":"storage.set","key":"item","value":"late"}""") }
                assertEquals(JSONObject.NULL, storage.execute("storage.get", "item"))
            }
        } finally { root.deleteRecursively() }
    }
}
