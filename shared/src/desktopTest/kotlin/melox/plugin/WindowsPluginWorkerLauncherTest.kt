package melox.plugin

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.json.JSONArray
import org.json.JSONObject

class WindowsPluginWorkerLauncherTest {
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
        try {
            launcher.start().use { worker ->
                val result = executor.submit<String> { PluginIpcFrames.read(worker.input) }
                PluginIpcFrames.write(worker.output, JSONObject().put("token", "container-invocation")
                    .put("method", "search").put("args", JSONArray()).put("script",
                        "globalThis.meloxPlugin={search:()=>({platform:'windows',value:42})};").toString())
                val frame = JSONObject(result.get(15, TimeUnit.SECONDS))
                assertEquals("container-invocation", frame.getString("token"))
                assertFalse(frame.getBoolean("error"), frame.toString())
                assertEquals(42, JSONObject(frame.getString("payload")).getInt("value"))
            }
        } finally { executor.shutdownNow() }
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
