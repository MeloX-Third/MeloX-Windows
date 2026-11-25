package melox.plugin

import com.sun.jna.*
import com.sun.jna.platform.win32.*
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Zero-capability AppContainer with an explicit pipe handle list and child-process prohibition. */
internal class WindowsPluginWorkerLauncher(private val command: List<String>, private val directory: File) : PluginWorkerLauncher {
    override fun start(): PluginWorkerProcess {
        check(Platform.isWindows()) { "PLUGIN_ISOLATION_UNAVAILABLE" }
        check(Native.POINTER_SIZE == 8) { "PLUGIN_ISOLATION_REQUIRES_64_BIT" }
        require(command.isNotEmpty() && File(command.first()).isFile && directory.isDirectory)
        val kernel = NativeKernel.instance
        val userenv = NativeUserenv.instance
        val sid = PointerByReference()
        val profile = "MeloX.Plugin.Worker.v1"
        val created = userenv.CreateAppContainerProfile(WString(profile), WString("MeloX plugin worker"),
            WString("No network capabilities"), null, 0, sid)
        if (created < 0) check(userenv.DeriveAppContainerSidFromAppContainerName(WString(profile), sid) >= 0) { "PLUGIN_ISOLATION_UNAVAILABLE" }
        val handles = mutableListOf<WinNT.HANDLE>()
        var attributes: Memory? = null
        var initialized = false
        val info = WinBase.PROCESS_INFORMATION()
        var launched = false
        try {
            fun pipe(): Pair<WinNT.HANDLE, WinNT.HANDLE> {
                val read = WinNT.HANDLEByReference(); val write = WinNT.HANDLEByReference()
                val security = WinBase.SECURITY_ATTRIBUTES().apply { bInheritHandle = true; dwLength = WinDef.DWORD(size().toLong()) }
                check(Kernel32.INSTANCE.CreatePipe(read, write, security, 0)) { "PLUGIN_PIPE_FAILED" }
                handles.add(read.value); handles.add(write.value)
                return read.value to write.value
            }
            val (childRead, hostWrite) = pipe()
            val (hostRead, childWrite) = pipe()
            check(Kernel32.INSTANCE.SetHandleInformation(hostWrite, 1, 0) && Kernel32.INSTANCE.SetHandleInformation(hostRead, 1, 0)) { "PLUGIN_PIPE_FAILED" }
            val capabilities = Memory((Native.POINTER_SIZE * 2 + 8).toLong()).apply {
                clear(); setPointer(0, sid.value)
            }
            val inherited = Memory((Native.POINTER_SIZE * 2).toLong()).apply {
                setPointer(0, childRead.pointer); setPointer(Native.POINTER_SIZE.toLong(), childWrite.pointer)
            }
            val childPolicy = Memory(4).apply { setInt(0, 1) }
            val size = com.sun.jna.ptr.LongByReference()
            kernel.InitializeProcThreadAttributeList(null, 3, 0, size)
            attributes = Memory(size.value)
            check(kernel.InitializeProcThreadAttributeList(attributes, 3, 0, size)) { "PLUGIN_ISOLATION_UNAVAILABLE" }
            initialized = true
            fun attribute(key: Long, value: Memory) {
                check(kernel.UpdateProcThreadAttribute(attributes, 0, BaseTSD.ULONG_PTR(key), value,
                    BaseTSD.SIZE_T(value.size()), null, null)) { "PLUGIN_ISOLATION_UNAVAILABLE" }
            }
            attribute(0x20009, capabilities)
            attribute(0x20002, inherited)
            attribute(0x2000e, childPolicy)
            val startup = WinBase.STARTUPINFO().apply {
                cb = WinDef.DWORD((size() + Native.POINTER_SIZE).toLong())
                dwFlags = 0x100
                lpDesktop = ""
                hStdInput = childRead; hStdOutput = childWrite; hStdError = childWrite
                write()
            }
            val extended = Memory(startup.cb.toLong()).apply {
                write(0, startup.pointer.getByteArray(0, startup.size()), 0, startup.size())
                setPointer(startup.size().toLong(), attributes)
            }
            // Pass only OS bootstrap paths, never the host's account or service environment.
            val windows = requireNotNull(System.getenv("SystemRoot"))
            val localAppData = requireNotNull(System.getenv("LOCALAPPDATA"))
            // Windows resolves AppContainer profile paths using LOCALAPPDATA during creation.
            val environmentText = "LOCALAPPDATA=$localAppData\u0000SystemRoot=$windows\u0000WINDIR=$windows\u0000\u0000"
            val environment = Memory((environmentText.length * 2).toLong()).apply {
                environmentText.forEachIndexed { index, char -> setChar(index * 2L, char) }
            }
            val commandLine = (command.joinToString(" ", transform = ::quote) + '\u0000').toCharArray()
            val started = kernel.CreateProcessW(WString(command.first()), commandLine, null, null, true,
                0x00080000 or 0x00000400 or 0x00000008, environment, WString(directory.absolutePath), extended, info)
            val startError = Native.getLastError()
            check(started) {
                "PLUGIN_ISOLATION_START_FAILED:$startError"
            }
            launched = true
            Kernel32.INSTANCE.CloseHandle(info.hThread)
            Kernel32.INSTANCE.CloseHandle(childRead); handles.remove(childRead)
            Kernel32.INSTANCE.CloseHandle(childWrite); handles.remove(childWrite)
            val worker = NativeWorker(info.hProcess, hostRead, hostWrite)
            handles.clear()
            return worker
        } catch (failure: Throwable) {
            if (launched) { Kernel32.INSTANCE.TerminateProcess(info.hProcess, 75); Kernel32.INSTANCE.CloseHandle(info.hProcess) }
            throw failure
        } finally {
            handles.forEach { Kernel32.INSTANCE.CloseHandle(it) }
            if (initialized) kernel.DeleteProcThreadAttributeList(attributes)
            NativeSecurity.instance.FreeSid(sid.value)
        }
    }

    private class NativeWorker(private val process: WinNT.HANDLE, private val read: WinNT.HANDLE,
        private val write: WinNT.HANDLE) : PluginWorkerProcess {
        private val closed = AtomicBoolean()
        override val input = object : InputStream() {
            override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                java.util.Objects.checkFromIndexSize(offset, length, bytes.size)
                if (length == 0) return 0
                val buffer = ByteArray(minOf(length, 32768)); val count = IntByReference()
                if (!Kernel32.INSTANCE.ReadFile(read, buffer, buffer.size, count, null)) {
                    if (closed.get()) return -1
                    if (Kernel32.INSTANCE.GetLastError() == 109) {
                        Kernel32.INSTANCE.WaitForSingleObject(process, 1000)
                        val exit = IntByReference()
                        Kernel32.INSTANCE.GetExitCodeProcess(process, exit)
                        check(exit.value == 0) { "PLUGIN_WORKER_EXIT:${Integer.toUnsignedString(exit.value, 16)}" }
                        return -1
                    }
                    error("PLUGIN_PIPE_READ_FAILED")
                }
                if (count.value == 0) return -1
                buffer.copyInto(bytes, offset, 0, count.value)
                return count.value
            }
        }
        override val output = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()))
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                java.util.Objects.checkFromIndexSize(offset, length, bytes.size)
                var sent = 0
                while (sent < length) {
                    check(!closed.get()) { "CANCELLED" }
                    val buffer = bytes.copyOfRange(offset + sent, offset + minOf(length, sent + 32768))
                    val count = IntByReference()
                    check(Kernel32.INSTANCE.WriteFile(write, buffer, buffer.size, count, null) && count.value > 0) {
                        Kernel32.INSTANCE.WaitForSingleObject(process, 1000)
                        val exit = IntByReference()
                        Kernel32.INSTANCE.GetExitCodeProcess(process, exit)
                        "PLUGIN_PIPE_WRITE_FAILED:${Integer.toUnsignedString(exit.value, 16)}"
                    }
                    sent += count.value
                }
            }
        }
        override fun close() {
            if (closed.compareAndSet(false, true)) {
                Kernel32.INSTANCE.TerminateProcess(process, 75)
                Kernel32.INSTANCE.WaitForSingleObject(process, 2000)
                Kernel32.INSTANCE.CloseHandle(read); Kernel32.INSTANCE.CloseHandle(write); Kernel32.INSTANCE.CloseHandle(process)
            }
        }
    }

    companion object {
        internal fun quote(value: String): String {
            require('\u0000' !in value)
            return buildString {
                append('"'); var slashes = 0
                for (char in value) when (char) {
                    '\\' -> slashes++
                    '"' -> { repeat(slashes * 2 + 1) { append('\\') }; append('"'); slashes = 0 }
                    else -> { repeat(slashes) { append('\\') }; slashes = 0; append(char) }
                }
                repeat(slashes * 2) { append('\\') }; append('"')
            }
        }
    }
}

private interface NativeUserenv : StdCallLibrary {
    fun CreateAppContainerProfile(name: WString, display: WString, description: WString, capabilities: Pointer?, count: Int, sid: PointerByReference): Int
    fun DeriveAppContainerSidFromAppContainerName(name: WString, sid: PointerByReference): Int
    companion object { val instance: NativeUserenv = Native.load("userenv", NativeUserenv::class.java, W32APIOptions.UNICODE_OPTIONS) }
}

private interface NativeKernel : StdCallLibrary {
    fun InitializeProcThreadAttributeList(list: Pointer?, count: Int, flags: Int, size: com.sun.jna.ptr.LongByReference): Boolean
    fun UpdateProcThreadAttribute(list: Pointer?, flags: Int, key: BaseTSD.ULONG_PTR, value: Pointer, size: BaseTSD.SIZE_T, previous: Pointer?, returned: Pointer?): Boolean
    fun DeleteProcThreadAttributeList(list: Pointer?)
    fun CreateProcessW(application: WString, command: CharArray, processSecurity: Pointer?, threadSecurity: Pointer?,
        inherit: Boolean, flags: Int, environment: Pointer, directory: WString, startup: Pointer, info: WinBase.PROCESS_INFORMATION): Boolean
    companion object { val instance: NativeKernel = Native.load("kernel32", NativeKernel::class.java, W32APIOptions.UNICODE_OPTIONS) }
}

private interface NativeSecurity : StdCallLibrary {
    fun FreeSid(sid: Pointer?): Pointer?
    companion object { val instance: NativeSecurity = Native.load("advapi32", NativeSecurity::class.java) }
}
