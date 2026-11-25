package melox.plugin

import melox.music.model.SourceId
import kotlin.test.*

class PluginIpcPolicyTest {
    private val manifest = PluginManifest(SourceId.parse("plugin:example.policy")!!, "Policy", "1.0.0", 1,
        "index.js", setOf(PluginCapability.Search), setOf("example.com"))

    @Test fun rejectsStaleTokensUndeclaredMethodsAndHostCallOverflow() {
        assertTrue(PluginIpcPolicy.validToken("call-123", "call-123"))
        assertFalse(PluginIpcPolicy.validToken("call-123", "call-122"))
        assertFalse(PluginIpcPolicy.validToken("call-123", null))
        assertTrue(PluginIpcPolicy.methodAllowed(manifest, "search"))
        assertFalse(PluginIpcPolicy.methodAllowed(manifest, "resolvePlayback"))
        assertFalse(PluginIpcPolicy.methodAllowed(manifest, "__proto__"))
        assertTrue(PluginIpcPolicy.validHostCall(32, 32, 48 * 1024))
        assertFalse(PluginIpcPolicy.validHostCall(33, 1, 1))
        assertFalse(PluginIpcPolicy.validHostCall(1, 33, 1))
        assertFalse(PluginIpcPolicy.validHostCall(1, 1, 48 * 1024 + 1))
    }

    @Test fun remoteErrorsNeverExposeArbitraryScriptText() {
        assertEquals("LOGIN_REQUIRED", PluginIpcPolicy.errorCode("""{"code":"LOGIN_REQUIRED","message":"secret"}"""))
        listOf("secret", """{"code":"Bearer secret"}""", "x".repeat(2048)).forEach {
            assertEquals("PLUGIN_EXECUTION_FAILED", PluginIpcPolicy.errorCode(it))
        }
    }

    @Test fun permissionExpansionIncludesCredentialsDomainsAndCapabilities() {
        assertFalse(PluginPermissions.expands(manifest, manifest.copy(version = "1.0.1")))
        assertTrue(PluginPermissions.expands(manifest, manifest.copy(networkDomains = setOf("other.com"))))
        assertTrue(PluginPermissions.expands(manifest, manifest.copy(capabilities = manifest.capabilities + PluginCapability.Playback)))
        assertTrue(PluginPermissions.expands(manifest, manifest.copy(authentication = PluginAuthentication("apiToken"))))
    }
}
