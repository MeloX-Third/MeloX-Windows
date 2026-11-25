package melox.plugin

import kotlin.test.*

class PluginVersionTest {
    @Test fun precedenceAndValidationMatchManifestProtocol() {
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
            "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.1.0", "999999999999999999999.0.0")
        ordered.zipWithNext().forEach { (before, after) ->
            assertTrue(PluginVersion.compare(after, before) > 0)
            assertTrue(PluginVersion.compare(before, after) < 0)
        }
        ordered.forEach { assertEquals(0, PluginVersion.compare(it, it)) }
        listOf("01.0.0", "1.0", "1.0.0-01", "1.0.0-", "1.0.0+build").forEach { assertFalse(PluginVersion.valid(it)) }
    }
}
