package melox.plugin

import com.caoccao.javet.enums.JSRuntimeType
import com.caoccao.javet.interop.loader.JavetLibLoader
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Copies trusted application binaries into a private bundle; the sandbox receives RX only. */
internal object WindowsPluginWorkerBundle {
    @Synchronized fun prepare(destination: File, javaHome: File, classpath: List<File>): PluginWorkerLauncher {
        require(!destination.exists()) { "worker bundle destination already exists" }
        require(File(javaHome, "bin/java.exe").isFile && classpath.isNotEmpty())
        require(classpath.all { it.isFile || it.isDirectory })
        val parent = requireNotNull(destination.absoluteFile.parentFile).apply { mkdirs() }
        val staging = File(parent, ".worker-${UUID.randomUUID()}").apply { check(mkdir()) }
        var published = false
        try {
            val runtime = File(staging, "runtime")
            for (name in listOf("bin", "lib", "conf")) {
                val source = File(javaHome, name)
                require(source.isDirectory) { "incomplete Java runtime" }
                copyTree(source, File(runtime, name))
            }
            val libraries = File(staging, "libraries").apply { mkdirs() }
            val relativeClasspath = classpath.mapIndexed { index, source ->
                val target = File(libraries, "$index-${source.name}")
                copyTree(source, target)
                target.relativeTo(staging).path
            }
            val loader = JavetLibLoader(JSRuntimeType.V8)
            val native = File(staging, "native").apply { mkdirs() }
            val dll = File(native, loader.libFileName)
            requireNotNull(WindowsPluginWorkerBundle::class.java.getResourceAsStream(loader.resourceFileName)) {
                "V8 native resource missing"
            }.use { input -> dll.outputStream().use { input.copyTo(it) } }
            val sid = PointerByReference()
            val profile = WString("MeloX.Plugin.Worker.v1")
            val env = BundleUserenv.instance
            val created = env.CreateAppContainerProfile(profile, WString("MeloX plugin worker"), WString("No network capabilities"), null, 0, sid)
            if (created < 0) check(env.DeriveAppContainerSidFromAppContainerName(profile, sid) >= 0) { "PLUGIN_ISOLATION_UNAVAILABLE" }
            val sidText = try { Advapi32Util.convertSidToStringSid(WinNT.PSID(sid.value)) }
            finally { BundleSecurity.instance.FreeSid(sid.value) }
            val acl = ProcessBuilder(File(System.getenv("SystemRoot"), "System32/icacls.exe").absolutePath,
                staging.absolutePath, "/grant:r", "*${sidText}:(OI)(CI)(RX)", "/T", "/Q")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            try { check(acl.waitFor(30, TimeUnit.SECONDS) && acl.exitValue() == 0) { "PLUGIN_BUNDLE_ACL_FAILED" } }
            finally { if (acl.isAlive) acl.destroyForcibly() }
            Files.move(staging.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            published = true
            cleanupStale(parent, keep = destination)
            return WindowsPluginWorkerLauncher(listOf(File(destination, "runtime/bin/java.exe").absolutePath,
                "-Xmx96m", "-XX:-UsePerfData", "-Djavet.lib.loading.type=custom",
                "-Djavet.lib.loading.path=${File(destination, "native").absolutePath}", "-cp",
                relativeClasspath.joinToString(File.pathSeparator) { File(destination, it).absolutePath },
                "melox.plugin.PluginWorkerMain"), destination)
        } finally {
            // This directory is uniquely owned by this preparation attempt; never remove a prior bundle.
            if (!published) staging.deleteRecursively()
        }
    }

    /** Sessions can crash without cleanup; remove only old, inactive bundles. */
    internal fun cleanupStale(root: File, keep: File, now: Long = System.currentTimeMillis()) {
        val cutoff = now - 30L * 24 * 60 * 60 * 1000
        root.listFiles().orEmpty()
            .filter { it.isDirectory && it.absoluteFile != keep.absoluteFile && it.lastModified() < cutoff }
            .forEach { runCatching { it.deleteRecursively() } }
    }

    /** Best-effort removal of this session's bundle; open worker files may defer deletion. */
    internal fun remove(destination: File) {
        runCatching { destination.deleteRecursively() }
    }

    private fun copyTree(source: File, target: File) {
        require(!Files.isSymbolicLink(source.toPath()) && !Files.readAttributes(source.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).isOther) {
            "worker binaries must not contain links or reparse points"
        }
        if (source.isDirectory) {
            check(target.mkdirs() || target.isDirectory)
            requireNotNull(source.listFiles()).forEach { copyTree(it, File(target, it.name)) }
        } else {
            require(source.isFile)
            target.parentFile.mkdirs()
            source.copyTo(target, overwrite = false)
        }
    }
}

private interface BundleUserenv : StdCallLibrary {
    fun CreateAppContainerProfile(name: WString, display: WString, description: WString, capabilities: Pointer?, count: Int, sid: PointerByReference): Int
    fun DeriveAppContainerSidFromAppContainerName(name: WString, sid: PointerByReference): Int
    companion object { val instance = Native.load("userenv", BundleUserenv::class.java) }
}
private interface BundleSecurity : StdCallLibrary {
    fun FreeSid(sid: Pointer?): Pointer?
    companion object { val instance = Native.load("advapi32", BundleSecurity::class.java) }
}
