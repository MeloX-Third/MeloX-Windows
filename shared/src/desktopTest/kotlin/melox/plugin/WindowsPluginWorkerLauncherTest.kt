package melox.plugin

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.json.JSONArray
import org.json.JSONObject

class WindowsPluginWorkerLauncherTest {
    @Test fun productionBundleRunsValidatedProvider() = kotlinx.coroutines.runBlocking {
        if (!com.sun.jna.Platform.isWindows()) return@runBlocking
        val root = File("build/parity/production-worker-${java.util.UUID.randomUUID()}").absoluteFile
        val entries = requireNotNull(System.getProperty("melox.test.workerClasspath")).split(File.pathSeparator).map(::File)
        val jars = entries.filter { it.isFile && (it.name.startsWith("kotlin-stdlib") || it.name.startsWith("json-") || it.name.startsWith("javet")) }
        val runtimeHome = File(System.getProperty("melox.test.workerJavaHome", System.getProperty("java.home")))
        val launcher = WindowsPluginWorkerBundle.prepare(root, runtimeHome,
            jars + File("build/classes/kotlin/desktop/main").absoluteFile)
        val storageRoot = java.nio.file.Files.createTempDirectory("container-factory-storage").toFile()
        val source = requireNotNull(melox.music.model.SourceId.parse("plugin:test.bundle"))
        val manifest = PluginManifest(source, "Bundle", "1.0.0", 1, "index.js", setOf(PluginCapability.Search), emptySet())
        try {
            val factory = PluginProcessProviderFactory { plugin -> PluginProcessRuntime(plugin, launcher,
                { token, current -> PluginHostCalls(token, PluginHttpBroker(manifest), PluginStorage(source, base = storageRoot), current) }) }
            val provider = factory.prepare(PluginArchiveReader.Package(manifest,
                "globalThis.meloxPlugin={search:(query,page,pageSize)=>({items:[],page,pageSize,hasMore:false})};"))
            try { assertTrue((provider.provider as PluginMusicProvider).searchSongs("fixture", 1, 30).items.isEmpty()) }
            finally { provider.resources.close() }
            assertFailsWith<IllegalArgumentException> {
                WindowsPluginWorkerBundle.prepare(root, runtimeHome, jars)
            }
        } finally { storageRoot.deleteRecursively(); root.deleteRecursively() }
    }

    @Test fun quotesWindowsArgumentsIncludingTrailingSlashes() {
        assertEquals("\"\"", WindowsPluginWorkerLauncher.quote(""))
        assertEquals("\"a b\"", WindowsPluginWorkerLauncher.quote("a b"))
        assertEquals("\"a\\\"b\"", WindowsPluginWorkerLauncher.quote("a\"b"))
        assertEquals("\"C:\\path\\\\\"", WindowsPluginWorkerLauncher.quote("C:\\path\\"))
        assertFailsWith<IllegalArgumentException> { WindowsPluginWorkerLauncher.quote("bad\u0000value") }
    }

    @Test fun realAppContainerPipesWorkAndPrivateFileIsDenied() {
        if (!com.sun.jna.Platform.isWindows()) return
        val system = File(System.getenv("SystemRoot"), "System32")
        val secret = File.createTempFile("melox-container-private", ".txt")
        secret.writeText("PRIVATE-CONTENT-MUST-NOT-ESCAPE")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val launcher = WindowsPluginWorkerLauncher(listOf(File(system, "cmd.exe").absolutePath, "/d", "/q"), system)
            launcher.start().use { worker ->
                val result = executor.submit<String> { worker.input.bufferedReader().readText() }
                worker.output.write(("echo MELOX-CONTAINER-READY\r\ntype \"${secret.absolutePath}\"\r\n" +
                    "\"${File(system, "cmd.exe").absolutePath}\" /d /c echo CHILD-PROCESS-ESCAPED\r\n" +
                    "if errorlevel 1 echo CHILD-PROCESS-BLOCKED\r\nexit /b 0\r\n").toByteArray())
                worker.output.flush()
                val output = result.get(10, TimeUnit.SECONDS)
                assertTrue(output.contains("MELOX-CONTAINER-READY"), output)
                assertFalse(output.contains("PRIVATE-CONTENT-MUST-NOT-ESCAPE"), output)
                assertTrue(output.contains("CHILD-PROCESS-BLOCKED"), output)
                assertFalse(output.contains("CHILD-PROCESS-ESCAPED"), output)
            }
        } finally { executor.shutdownNow(); secret.delete() }
    }

    @Test fun realJavaScriptWorkerRunsInsideAppContainer() {
        if (!com.sun.jna.Platform.isWindows()) return
        val root = File("build/parity/appcontainer-worker").absoluteFile.apply { mkdirs() }
        val javaHome = File(System.getProperty("java.home"))
        val runtime = File(root, "runtime")
        listOf("bin", "lib", "conf").forEach { name ->
            File(javaHome, name).walkTopDown().forEach { source ->
                val destination = File(runtime, source.relativeTo(javaHome).path)
                if (source.isDirectory) destination.mkdirs()
                else if (!destination.isFile || source.length() != destination.length()) source.copyTo(destination, overwrite = true)
            }
        }
        val libraries = File(root, "libraries").apply { mkdirs() }
        val entries = requireNotNull(System.getProperty("melox.test.workerClasspath")).split(File.pathSeparator).map(::File)
        val jars = entries.filter { it.isFile && (it.name.startsWith("kotlin-stdlib") || it.name.startsWith("json-") || it.name.startsWith("javet")) }
        jars.forEach { it.copyTo(File(libraries, it.name), overwrite = true) }
        val classes = File(root, "classes").apply { mkdirs() }
        File("build/classes/kotlin/desktop/main").walkTopDown().filter { it.isFile && it.extension == "class" }.forEach {
            val target = File(classes, it.relativeTo(File("build/classes/kotlin/desktop/main")).path)
            target.parentFile.mkdirs(); it.copyTo(target, overwrite = true)
        }
        val probeClass = "melox/plugin/WindowsContainerBoundaryProbe.class"
        File("build/classes/kotlin/desktop/test", probeClass).copyTo(File(classes, probeClass), overwrite = true)
        val native = File(root, "native").apply { mkdirs() }
        jars.filter { it.name.startsWith("javet-v8") }.forEach { jar ->
            java.util.zip.ZipFile(jar).use { zip ->
                zip.entries().asSequence().filter { it.name.endsWith(".dll") }.forEach { entry ->
                    zip.getInputStream(entry).use { input -> File(native, File(entry.name).name).outputStream().use(input::copyTo) }
                }
            }
        }
        // Grant this test bundle read/execute only; never grant access to the checkout or user's home.
        val sid = com.sun.jna.ptr.PointerByReference()
        val env = TestUserenv.instance
        check(env.DeriveAppContainerSidFromAppContainerName(com.sun.jna.WString("MeloX.Plugin.Worker.v1"), sid) >= 0)
        val sidText = try { com.sun.jna.platform.win32.Advapi32Util.convertSidToStringSid(com.sun.jna.platform.win32.WinNT.PSID(sid.value)) }
        finally { TestSecurity.instance.FreeSid(sid.value) }
        val acl = ProcessBuilder("icacls.exe", root.absolutePath, "/grant", "*${sidText}:(OI)(CI)(RX)", "/T", "/Q")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        check(acl.waitFor(30, TimeUnit.SECONDS) && acl.exitValue() == 0)
        val classpath = (listOf(classes) + libraries.listFiles().orEmpty().toList()).joinToString(File.pathSeparator) { it.absolutePath }
        val launcher = WindowsPluginWorkerLauncher(listOf(File(runtime, "bin/java.exe").absolutePath,
            "-Xmx96m", "-XX:-UsePerfData", "-Djavet.lib.loading.type=custom", "-Djavet.lib.loading.path=${native.absolutePath}",
            "-cp", classpath, "melox.plugin.PluginWorkerMain"), root)
        val executor = Executors.newSingleThreadExecutor()
        val storageRoot = java.nio.file.Files.createTempDirectory("container-host-storage").toFile()
        try {
            launcher.start().use { worker ->
                val source = requireNotNull(melox.music.model.SourceId.parse("plugin:test.container"))
                val manifest = PluginManifest(source, "Container", "1.0.0", 1, "index.js", emptySet(), emptySet())
                val storage = PluginStorage(source, base = storageRoot)
                val calls = PluginHostCalls("container-invocation", PluginHttpBroker(manifest), storage, { true })
                val result = executor.submit<String> {
                    PluginWorkerChannel("container-invocation", calls, { true }).receive(worker.input, worker.output)
                }
                PluginIpcFrames.write(worker.output, JSONObject().put("token", "container-invocation")
                    .put("method", "search").put("args", JSONArray()).put("script",
                        """globalThis.meloxPlugin={async search(host) {
                            await host.storage.set('value','中文😀');
                            return {platform:host.platform,value:await host.storage.get('value')};
                        }};""").toString())
                val value = JSONObject(result.get(15, TimeUnit.SECONDS))
                assertEquals("windows", value.getString("platform"))
                assertEquals("中文😀", value.getString("value"))
                assertEquals("中文😀", storage.execute("storage.get", "value"))
            }
            val privateFile = File(storageRoot, "private.txt").apply { writeText("private-fixture") }
            java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                val probe = WindowsPluginWorkerLauncher(listOf(File(runtime, "bin/java.exe").absolutePath,
                    "-Xmx64m", "-XX:-UsePerfData", "-cp", classpath, "melox.plugin.WindowsContainerBoundaryProbe",
                    privateFile.absolutePath, root.absolutePath, server.localPort.toString()), root)
                probe.start().use { worker ->
                    val report = JSONObject(executor.submit<String> { PluginIpcFrames.read(worker.input) }.get(10, TimeUnit.SECONDS))
                    assertTrue(report.getBoolean("fileReadDenied"), report.toString())
                    assertTrue(report.getBoolean("bundleWriteDenied"), report.toString())
                    assertTrue(report.getBoolean("networkDenied"), report.toString())
                    assertTrue(report.getBoolean("childDenied"), report.toString())
                }
                server.soTimeout = 200
                assertFailsWith<java.net.SocketTimeoutException> { server.accept().close() }
            }
        } finally { executor.shutdownNow(); storageRoot.deleteRecursively() }
    }
}

/** Test-only trusted probe checks the JVM's OS permissions, independently of JavaScript bindings. */
object WindowsContainerBoundaryProbe {
    @JvmStatic fun main(args: Array<String>) {
        val readDenied = runCatching { File(args[0]).readText() }.isFailure
        val writeDenied = runCatching { File(args[1], "container-write-probe").writeText("forbidden") }.isFailure
        val networkDenied = runCatching {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", args[2].toInt()), 1000) }
        }.isFailure
        val childDenied = runCatching {
            ProcessBuilder(File(System.getenv("SystemRoot"), "System32/cmd.exe").absolutePath, "/c", "exit", "0").start()
                .also { it.destroyForcibly() }
        }.isFailure
        PluginIpcFrames.write(System.out, JSONObject().put("fileReadDenied", readDenied).put("bundleWriteDenied", writeDenied)
            .put("networkDenied", networkDenied).put("childDenied", childDenied).toString())
    }
}

private interface TestUserenv : com.sun.jna.win32.StdCallLibrary {
    fun DeriveAppContainerSidFromAppContainerName(name: com.sun.jna.WString, sid: com.sun.jna.ptr.PointerByReference): Int
    companion object { val instance = com.sun.jna.Native.load("userenv", TestUserenv::class.java) }
}
private interface TestSecurity : com.sun.jna.win32.StdCallLibrary {
    fun FreeSid(sid: com.sun.jna.Pointer?): com.sun.jna.Pointer?
    companion object { val instance = com.sun.jna.Native.load("advapi32", TestSecurity::class.java) }
}
