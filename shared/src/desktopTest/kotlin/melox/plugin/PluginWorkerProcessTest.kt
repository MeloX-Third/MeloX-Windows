package melox.plugin

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import melox.music.model.SourceId
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.*

class PluginWorkerProcessTest {
    private fun start(): Process {
        val java = File(System.getProperty("java.home"), "bin/java.exe")
        val classpath = requireNotNull(System.getProperty("melox.test.workerClasspath"))
        return ProcessBuilder(java.absolutePath, "-Xmx96m", "-cp", classpath, "melox.plugin.PluginWorkerMain")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    }

    @Test fun realWorkerExecutesPromiseAndHostStorageWithoutJavaBridge() {
        val root = Files.createTempDirectory("plugin-process-test").toFile()
        val executor = Executors.newSingleThreadExecutor()
        val source = requireNotNull(SourceId.parse("plugin:test.process"))
        val manifest = PluginManifest(source, "Process", "1.0.0", 1, "index.js", emptySet(), emptySet())
        val storage = PluginStorage(source, base = root)
        val process = start()
        try {
            val result = executor.submit<String> {
                PluginHostCalls("invocation", PluginHttpBroker(manifest), storage, { true }).use { calls ->
                    PluginIpcFrames.write(process.outputStream, JSONObject().put("token", "invocation").put("method", "search")
                        .put("args", JSONArray().put("中文😀")).put("script", """
                            globalThis.meloxPlugin = {async search(query,host) {
                              await host.storage.set('query',query);
                              return {query:await host.storage.get('query'),platform:host.platform,
                                java:typeof Java,require:typeof require,native:typeof __native};
                            }};
                        """.trimIndent()).toString())
                    PluginWorkerChannel("invocation", calls, { true }).receive(process.inputStream, process.outputStream)
                }
            }.get(15, TimeUnit.SECONDS)
            val parsed = JSONObject(result)
            assertEquals("中文😀", parsed.getString("query"))
            assertEquals("windows", parsed.getString("platform"))
            listOf("java", "require", "native").forEach { assertEquals("undefined", parsed.getString(it)) }
            assertEquals("中文😀", storage.execute("storage.get", "query"))
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
        } finally {
            process.destroyForcibly()
            executor.shutdownNow()
            root.deleteRecursively()
        }
    }

    @Test fun independentWatchdogTerminatesInfiniteScript() {
        val process = start()
        try {
            PluginIpcFrames.write(process.outputStream, JSONObject().put("token", "invocation").put("method", "search")
                .put("args", JSONArray()).put("script", "while(true) {}").toString())
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Worker must terminate independently of native execution")
        } finally { process.destroyForcibly() }
    }
}
