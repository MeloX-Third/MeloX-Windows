package melox.plugin

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

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
}
