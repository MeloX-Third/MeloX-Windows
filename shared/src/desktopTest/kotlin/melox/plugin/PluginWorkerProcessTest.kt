package melox.plugin

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import melox.music.model.SourceId
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.*
import kotlinx.coroutines.async

class PluginWorkerProcessTest {
    private fun start(): Process {
        val java = File(System.getProperty("java.home"), "bin/java.exe")
        val classpath = requireNotNull(System.getProperty("melox.test.workerClasspath"))
        return ProcessBuilder(java.absolutePath, "-Xmx96m", "-cp", classpath, "melox.plugin.PluginWorkerMain")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    }

    @Test fun providerFactoryValidatesCapabilitiesBeforeReturningProvider() = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("plugin-factory").toFile()
        val source = requireNotNull(SourceId.parse("plugin:test.factory"))
        val manifest = PluginManifest(source, "Factory", "1.0.0", 1, "index.js", setOf(PluginCapability.Search), emptySet())
        val factory = PluginProcessProviderFactory { plugin ->
            PluginProcessRuntime(plugin, PluginWorkerLauncher {
                val process = start()
                object : PluginWorkerProcess {
                    override val input = process.inputStream
                    override val output = process.outputStream
                    override fun close() { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
                }
            }, { token, current -> PluginHostCalls(token, PluginHttpBroker(manifest), PluginStorage(source, base = root), current) })
        }
        try {
            assertFailsWith<IllegalArgumentException> {
                factory.prepare(PluginArchiveReader.Package(manifest, "globalThis.meloxPlugin={};"))
            }
            val loaded = factory.prepare(PluginArchiveReader.Package(manifest,
                "globalThis.meloxPlugin={search:(query,page,pageSize)=>({items:[],page,pageSize,total:0,hasMore:false})};"))
            try {
                val provider = loaded.provider as PluginMusicProvider
                assertTrue(provider.searchSongs("fixture", 1, 30).items.isEmpty())
            } finally { loaded.resources.close() }
        } finally { root.deleteRecursively() }
    }

    @Test fun runtimeCreatesFreshProcessAndRejectsCallsAfterClose() = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("plugin-runtime-process").toFile()
        val source = requireNotNull(SourceId.parse("plugin:test.runtime"))
        val manifest = PluginManifest(source, "Runtime", "1.0.0", 1, "index.js", setOf(PluginCapability.Search), emptySet())
        var launches = 0
        val runtime = PluginProcessRuntime(PluginArchiveReader.Package(manifest,
            "let calls=0; globalThis.meloxPlugin={search:()=>++calls};"), PluginWorkerLauncher {
            launches++
            val process = start()
            object : PluginWorkerProcess {
                override val input = process.inputStream
                override val output = process.outputStream
                override fun close() { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
            }
        }, { token, current -> PluginHostCalls(token, PluginHttpBroker(manifest), PluginStorage(source, base = root), current) })
        try {
            repeat(2) { assertEquals("1", runtime.invoke("search", JSONArray())) }
            assertEquals(2, launches)
            runtime.close()
            assertFailsWith<IllegalStateException> { runtime.invoke("search", JSONArray()) }
            assertEquals(2, launches)
        } finally { runtime.close(); root.deleteRecursively() }
    }

    @Test fun fullSizeEscapedScriptStartsButOversizedArgumentsNeverLaunch() = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("plugin-large-script").toFile()
        val source = requireNotNull(SourceId.parse("plugin:test.large.script"))
        val manifest = PluginManifest(source, "Large", "1.0.0", 1, "index.js", setOf(PluginCapability.Search), emptySet())
        val body = "globalThis.meloxPlugin={search:()=>42};/*"
        val script = body + "\t".repeat(PluginIpcFrames.MAX_BYTES - body.length - 2) + "*/"
        assertEquals(PluginIpcFrames.MAX_BYTES, script.toByteArray().size)
        var launches = 0
        val runtime = PluginProcessRuntime(PluginArchiveReader.Package(manifest, script), PluginWorkerLauncher {
            launches++
            val process = start()
            object : PluginWorkerProcess {
                override val input = process.inputStream
                override val output = process.outputStream
                override fun close() { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
            }
        }, { token, current -> PluginHostCalls(token, PluginHttpBroker(manifest), PluginStorage(source, base = root), current) })
        try {
            assertEquals("42", runtime.invoke("search", JSONArray()))
            assertEquals(1, launches)
            assertFailsWith<IllegalArgumentException> {
                runtime.invoke("search", JSONArray().put("x".repeat(PluginIpcFrames.MAX_BYTES)))
            }
            assertEquals(1, launches)
        } finally { runtime.close(); root.deleteRecursively() }
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

    @Test fun independentWatchdogKillsWorkerBlockedOnInvocationInput() {
        val process = start()
        try {
            // Keep stdin open without a frame: neither the JS guard nor EOF can end this worker.
            assertTrue(process.waitFor(15, TimeUnit.SECONDS))
            assertEquals(74, process.exitValue())
        } finally { process.destroyForcibly() }
    }

    @Test fun closingRuntimeReleasesBlockedProcessAndRejectsLateResult() = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("plugin-runtime-cancel").toFile()
        val source = requireNotNull(SourceId.parse("plugin:test.cancel.runtime"))
        val manifest = PluginManifest(source, "Cancel", "1.0.0", 1, "index.js", setOf(PluginCapability.Search), emptySet())
        val started = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.CountDownLatch(1)
        val runtime = PluginProcessRuntime(PluginArchiveReader.Package(manifest, "globalThis.meloxPlugin={};"),
            PluginWorkerLauncher {
                object : PluginWorkerProcess {
                    override val output = java.io.ByteArrayOutputStream()
                    override val input = object : java.io.InputStream() {
                        override fun read(): Int { started.countDown(); released.await(); return -1 }
                    }
                    override fun close() { released.countDown() }
                }
            }, { token, current -> PluginHostCalls(token, PluginHttpBroker(manifest), PluginStorage(source, base = root), current) })
        try {
            val task = async(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { runtime.invoke("search", JSONArray()) }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            runtime.close()
            assertTrue(released.await(1, TimeUnit.SECONDS))
            assertTrue(kotlinx.coroutines.withTimeout(2000) { task.await() }.isFailure)
        } finally { runtime.close(); root.deleteRecursively() }
    }
}
