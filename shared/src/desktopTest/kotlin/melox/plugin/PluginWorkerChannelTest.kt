package melox.plugin

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import melox.music.model.SourceId
import org.json.JSONObject
import kotlin.test.*

class PluginWorkerChannelTest {
    private val source = requireNotNull(SourceId.parse("plugin:test.worker.channel"))
    private val manifest = PluginManifest(source, "Worker", "1.0.0", 1, "index.js", emptySet(), emptySet())
    private fun frame(type: String, token: String = "invocation") = JSONObject().put("token", token).put("type", type)
    private fun input(vararg frames: JSONObject) = ByteArrayOutputStream().apply {
        frames.forEach { PluginIpcFrames.write(this, it.toString()) }
    }.toByteArray().inputStream()

    @Test fun workerHostCallsShareInvocationStorageAndReturnBoundResponse() {
        val root = Files.createTempDirectory("plugin-worker-channel").toFile()
        try {
            val storage = PluginStorage(source, base = root)
            PluginHostCalls("invocation", PluginHttpBroker(manifest), storage, { true }).use { calls ->
                val output = ByteArrayOutputStream()
                val request = frame("host").put("id", 1).put("payload",
                    """{"operation":"storage.set","key":"value","value":"fixture"}""")
                val result = frame("result").put("error", false).put("payload", "[1,2]")
                assertEquals("[1,2]", PluginWorkerChannel("invocation", calls, { true }).receive(input(request, result), output))
                assertEquals("fixture", storage.execute("storage.get", "value"))
                val response = JSONObject(PluginIpcFrames.read(ByteArrayInputStream(output.toByteArray())))
                assertEquals("invocation", response.getString("token"))
                assertEquals("response", response.getString("type"))
                assertEquals(1, response.getInt("id"))
                assertTrue(JSONObject(response.getString("payload")).getBoolean("ok"))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun wrongTokenInvalidIdsAndRemoteErrorsNeverEscapeProtocol() {
        val root = Files.createTempDirectory("plugin-worker-reject").toFile()
        try {
            fun reject(frame: JSONObject): String {
                PluginHostCalls("invocation", PluginHttpBroker(manifest), PluginStorage(source, base = root), { true }).use { calls ->
                    return assertFails { PluginWorkerChannel("invocation", calls, { true })
                        .receive(input(frame), ByteArrayOutputStream()) }.message.orEmpty()
                }
            }
            assertEquals("INVALID_PLUGIN_IPC", reject(frame("result", "other").put("payload", "null").put("error", false)))
            assertEquals("INVALID_PLUGIN_IPC", reject(frame("host").put("id", 1.5).put("payload", "{}")))
            assertEquals("INVALID_PLUGIN_IPC", reject(frame("result").put("payload", "null").put("error", "false")))
            assertEquals("PLUGIN_EXECUTION_FAILED", reject(frame("result").put("payload", """{"code":"secret raw error"}""").put("error", true)))
            assertEquals("LOGIN_REQUIRED", reject(frame("result").put("payload", """{"code":"LOGIN_REQUIRED"}""").put("error", true)))
        } finally { root.deleteRecursively() }
    }

    @Test fun revokedChannelClosesInvocationWithoutMutatingStorage() {
        val root = Files.createTempDirectory("plugin-worker-revoked").toFile()
        try {
            val storage = PluginStorage(source, base = root)
            val calls = PluginHostCalls("invocation", PluginHttpBroker(manifest), storage, { true })
            assertFailsWith<IllegalStateException> {
                PluginWorkerChannel("invocation", calls, { false }).receive(input(), ByteArrayOutputStream())
            }
            assertFailsWith<IllegalStateException> {
                calls.execute("invocation", 1, """{"operation":"storage.set","key":"value","value":"late"}""")
            }
            assertEquals(JSONObject.NULL, storage.execute("storage.get", "value"))
        } finally { root.deleteRecursively() }
    }
}
