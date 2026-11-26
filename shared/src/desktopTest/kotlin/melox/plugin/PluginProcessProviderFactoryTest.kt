package melox.plugin

import java.nio.file.Files
import melox.music.model.SourceId
import melox.platform.WindowsDpapi
import org.json.JSONArray
import kotlin.test.*

class PluginProcessProviderFactoryTest {
    @Test fun authorizationRefreshIsConsumedByExactlyOneInvocation() = kotlinx.coroutines.runBlocking {
        org.junit.Assume.assumeTrue(com.sun.jna.Platform.isWindows())
        val root = Files.createTempDirectory("plugin-factory-refresh").toFile()
        val source = requireNotNull(SourceId.parse("plugin:test.factory.refresh"))
        val oauth = PluginOAuthConfiguration("https://api.example.com/authorize", "https://api.example.com/token", "client", emptyList())
        val policy = PluginAuthentication("oauth2Pkce", domains = setOf("api.example.com"), oauth = oauth)
        val manifest = PluginManifest(source, "Factory", "1.0.0", 1, "index.js", setOf(PluginCapability.Search), policy.domains, authentication = policy)
        val vault = PluginAccountVault(source, root, WindowsDpapi)
        vault.importOAuthAndActivate("Account",
            PluginAccountVault.Tokens("old", "refresh", System.currentTimeMillis() + 3_600_000), policy)
        var requests = 0
        val capture = PluginAccountInvocation(manifest, vault, root) { candidate, active, lease ->
            PluginHttpBroker(candidate, accountCurrent = active, credential = lease, internalTransport = okhttp3.Interceptor { chain ->
                requests++
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                    .body(okhttp3.ResponseBody.create(null, """{"access_token":"refreshed","token_type":"Bearer","expires_in":3600}""")).build()
            })
        }
        val script = """globalThis.meloxPlugin={async search(host){return {ok:true};}};"""
        var runtime: PluginProcessRuntime? = null
        try {
            val factory = PluginProcessProviderFactory(createRuntime = { plugin, consumeRefresh ->
                PluginProcessRuntime(plugin, workerLauncher(), { _, _ -> error("uncaptured host context") },
                    context = { method -> capture.capture(method, forceRefresh = consumeRefresh()) }).also { runtime = it }
            })
            val provider = factory.prepare(PluginArchiveReader.Package(manifest, script)).provider as PluginMusicProvider
            assertEquals(0, requests)
            val active = requireNotNull(runtime)
            provider.authorizationRefresh?.invoke()
            active.invoke("search", JSONArray())
            assertEquals(1, requests)
            active.invoke("search", JSONArray())
            assertEquals(1, requests)
        } finally { runtime?.close(); root.deleteRecursively() }
    }

    private fun workerLauncher() = PluginWorkerLauncher {
        val process = ProcessBuilder(java.io.File(System.getProperty("java.home"), "bin/java.exe").absolutePath,
            "-cp", requireNotNull(System.getProperty("melox.test.workerClasspath")), "melox.plugin.PluginWorkerMain")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        object : PluginWorkerProcess {
            override val input = process.inputStream
            override val output = process.outputStream
            override fun close() { process.destroyForcibly(); process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) }
        }
    }
}
