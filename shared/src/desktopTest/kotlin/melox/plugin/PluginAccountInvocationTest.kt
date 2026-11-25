package melox.plugin

import java.nio.file.Files
import melox.music.model.SourceId
import melox.platform.WindowsDpapi
import org.json.JSONObject
import kotlin.test.*

class PluginAccountInvocationTest {
    @Test fun expiringOAuthRefreshesBeforeCapturingWorkerAndValidationDoesNotRefresh() {
        org.junit.Assume.assumeTrue(com.sun.jna.Platform.isWindows())
        val root = Files.createTempDirectory("plugin-invocation-refresh").toFile()
        val source = requireNotNull(SourceId.parse("plugin:test.invocation.refresh"))
        val oauth = PluginOAuthConfiguration("https://api.example.com/authorize", "https://api.example.com/token", "client", emptyList())
        val policy = PluginAuthentication("oauth2Pkce", domains = setOf("api.example.com"), oauth = oauth)
        val manifest = PluginManifest(source, "Refresh", "1.0.0", 1, "index.js", emptySet(), policy.domains, authentication = policy)
        val vault = PluginAccountVault(source, root, WindowsDpapi)
        try {
            val account = vault.importOAuthAndActivate("Account", PluginAccountVault.Tokens("old", "refresh", 1), policy)
            var requests = 0
            val capture = PluginAccountInvocation(manifest, vault, root) { candidate, active, lease ->
                PluginHttpBroker(candidate, accountCurrent = active, credential = lease, internalTransport = okhttp3.Interceptor { chain ->
                    requests++
                    assertEquals("https://api.example.com/token", chain.request().url.toString())
                    okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                        .body(okhttp3.ResponseBody.create(null, """{"access_token":"renewed","token_type":"Bearer","expires_in":3600}""")).build()
                })
            }
            capture.capture("__validate")
            assertEquals(0, requests)
            val context = capture.capture("search")
            assertEquals(1, requests)
            assertEquals("renewed", vault.lease()?.token)
            assertEquals("refresh", vault.lease()?.refreshToken)
            assertEquals(account.id, context.account?.getString("id"))
            assertTrue(context.current())
            capture.capture("search")
            assertEquals(1, requests)
        } finally { root.deleteRecursively() }
    }

    @Test fun workerUsesCapturedAccountAndHostStorageTogether() = kotlinx.coroutines.runBlocking {
        org.junit.Assume.assumeTrue(com.sun.jna.Platform.isWindows())
        val root = Files.createTempDirectory("plugin-account-worker").toFile()
        val source = requireNotNull(SourceId.parse("plugin:test.account.worker"))
        val policy = PluginAuthentication("apiToken", domains = setOf("api.example.com"))
        val manifest = PluginManifest(source, "Account", "1.0.0", 1, "index.js", setOf(PluginCapability.Search), policy.domains, authentication = policy)
        val vault = PluginAccountVault(source, root, WindowsDpapi)
        val first = vault.importTokenAndActivate("First", "secret-first", policy)
        val capture = PluginAccountInvocation(manifest, vault, root)
        val runtime = PluginProcessRuntime(PluginArchiveReader.Package(manifest,
            """globalThis.meloxPlugin={async search(host) {
                const previous=await host.storage.get('value');
                await host.storage.set('value',host.account.id);
                return {previous,id:host.account.id,name:host.account.displayName};
            }};"""), PluginWorkerLauncher {
            val process = ProcessBuilder(java.io.File(System.getProperty("java.home"), "bin/java.exe").absolutePath,
                "-cp", requireNotNull(System.getProperty("melox.test.workerClasspath")), "melox.plugin.PluginWorkerMain")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            object : PluginWorkerProcess {
                override val input = process.inputStream
                override val output = process.outputStream
                override fun close() { process.destroyForcibly(); process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) }
            }
        }, { _, _ -> error("uncaptured host context") }, context = capture::capture)
        try {
            val firstResult = JSONObject(runtime.invoke("search", org.json.JSONArray()))
            assertEquals(first.id, firstResult.getString("id"))
            assertTrue(firstResult.isNull("previous"))
            val second = vault.importTokenAndActivate("Second", "secret-second", policy)
            val secondResult = JSONObject(runtime.invoke("search", org.json.JSONArray()))
            assertEquals(second.id, secondResult.getString("id"))
            assertEquals("Second", secondResult.getString("name"))
            assertTrue(secondResult.isNull("previous"))
            assertEquals(first.id, PluginStorage(source, first.id, root).execute("storage.get", "value"))
            assertEquals(second.id, PluginStorage(source, second.id, root).execute("storage.get", "value"))
        } finally { runtime.close(); root.deleteRecursively() }
    }

    @Test fun selectedAccountScopesStorageAndSwitchRevokesCapturedInvocation() {
        org.junit.Assume.assumeTrue(com.sun.jna.Platform.isWindows())
        val root = Files.createTempDirectory("plugin-account-invocation").toFile()
        val source = requireNotNull(SourceId.parse("plugin:test.invocation.account"))
        val policy = PluginAuthentication("apiToken", domains = setOf("api.example.com"))
        val manifest = PluginManifest(source, "Account", "1.0.0", 1, "index.js", emptySet(), policy.domains, authentication = policy)
        val vault = PluginAccountVault(source, root, WindowsDpapi)
        try {
            val first = vault.importTokenAndActivate("First", "secret-first", policy)
            val context = PluginAccountInvocation(manifest, vault, root).capture()
            assertEquals(first.id, context.account?.getString("id"))
            assertFalse(context.account.toString().contains("secret-first"))
            context.hostCalls("token", { true }).use { calls ->
                calls.execute("token", 1, """{"operation":"storage.set","key":"value","value":"first"}""")
                assertEquals("first", PluginStorage(source, first.id, root).execute("storage.get", "value"))
                assertEquals(JSONObject.NULL, PluginStorage(source, base = root).execute("storage.get", "value"))
                val second = vault.importTokenAndActivate("Second", "secret-second", policy)
                assertFalse(context.current())
                assertFails { calls.execute("token", 2, """{"operation":"storage.get","key":"value"}""") }
                val next = PluginAccountInvocation(manifest, vault, root).capture()
                assertEquals(second.id, next.account?.getString("id"))
                next.hostCalls("next", { true }).use { fresh ->
                    val response = JSONObject(fresh.execute("next", 1, """{"operation":"storage.get","key":"value"}"""))
                    assertTrue(response.isNull("value"))
                }
            }
        } finally { root.deleteRecursively() }
    }
}
