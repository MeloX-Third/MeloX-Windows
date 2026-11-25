package melox.plugin

import java.nio.file.Files
import melox.music.model.SourceId
import melox.platform.SecretProtection
import melox.platform.WindowsDpapi
import org.junit.Assume.assumeTrue
import com.sun.jna.Platform
import kotlin.test.*

class PluginAccountVaultTest {
    private val source = SourceId.parse("plugin:example.vault")!!
    private val policy = PluginAuthentication("apiToken", domains = setOf("api.example.com"))

    @Test fun oauthRefreshRetainsRefreshTokenAndReauthorizationReplacesPolicyWithoutDuplicatingSlot(): Unit {
        assumeTrue(Platform.isWindows())
        val root = Files.createTempDirectory("plugin-vault-oauth").toFile()
        try {
            val oauth = PluginOAuthConfiguration("https://api.example.com/authorize", "https://api.example.com/token", "client", emptyList())
            val auth = policy.copy(type = "oauth2Pkce", oauth = oauth)
            val vault = PluginAccountVault(source, root, WindowsDpapi)
            val account = vault.importOAuthAndActivate("OAuth", PluginAccountVault.Tokens("access", "refresh", 1000), auth)
            val previous = assertNotNull(vault.lease())
            val refreshed = vault.replaceTokens(previous, PluginAccountVault.Tokens("new-access", expiresAt = 2000))
            assertEquals("refresh", refreshed.refreshToken)
            assertFalse(previous.isCurrent())
            val changed = auth.copy(oauth = oauth.copy(scopes = listOf("library")))
            val authorized = vault.reauthorize(refreshed, PluginAccountVault.Tokens("reauthorized"), changed)
            assertEquals(changed, authorized.policy)
            assertNull(authorized.refreshToken)
            assertEquals(listOf(account), vault.accounts())
            assertFalse(refreshed.isCurrent())
            assertFails { vault.reauthorize(refreshed, PluginAccountVault.Tokens("late"), auth) }
            assertEquals("reauthorized", vault.lease()?.token)
        } finally { root.deleteRecursively() }
    }

    @Test fun windowsProtectionPersistsSlotsAndSessionChangesRevokeOldLeases(): Unit {
        assumeTrue(Platform.isWindows())
        val root = Files.createTempDirectory("plugin-vault-dpapi").toFile()
        try {
            val vault = PluginAccountVault(source, root, WindowsDpapi)
            val first = vault.importTokenAndActivate("First", "secret-token-first", policy)
            val before = assertNotNull(vault.lease())
            val restored = PluginAccountVault(source, root, WindowsDpapi)
            assertEquals(first, restored.accounts().single())
            assertEquals("secret-token-first", restored.lease()?.token)
            assertFalse(root.walkTopDown().filter { it.isFile }.any { it.readBytes().toString(Charsets.ISO_8859_1).contains("secret-token-first") })
            assertFalse(before.toString().contains("secret-token-first"))
            restored.activate(null)
            restored.activate(first.id)
            assertFails { vault.replaceTokens(before, PluginAccountVault.Tokens("late-refresh")) }
            val current = assertNotNull(vault.lease())
            val updated = vault.replaceTokens(current, PluginAccountVault.Tokens("new-token", "refresh-token", 12345))
            assertNotEquals(current.selectionStamp, updated.selectionStamp)
            assertEquals("new-token", restored.lease()?.token)
            assertFails { restored.replaceTokens(current, PluginAccountVault.Tokens("stale-token")) }
            val second = vault.importToken("Second", "second-token", policy)
            vault.remove(first.id)
            assertNull(vault.lease())
            assertEquals(listOf(second), vault.accounts())
            vault.activate(second.id)
            vault.clear()
            assertTrue(vault.accounts().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun corruptForeignAndFailedProtectionNeverOverwriteExistingCredentials(): Unit {
        assumeTrue(Platform.isWindows())
        val root = Files.createTempDirectory("plugin-vault-faults").toFile()
        try {
            val vault = PluginAccountVault(source, root, WindowsDpapi)
            val account = vault.importTokenAndActivate("Saved", "preserved-token", policy)
            val record = root.walkTopDown().single { it.extension == "dpapi" }
            val committed = record.readBytes()
            val failing = object : SecretProtection {
                override fun protect(plain: ByteArray): ByteArray = error("synthetic encryption failure")
                override fun unprotect(encrypted: ByteArray): ByteArray = WindowsDpapi.unprotect(encrypted)
            }
            val failed = PluginAccountVault(source, root, failing)
            assertFails { failed.importTokenAndActivate("Failure", "new-token", policy) }
            assertEquals(account.id, vault.selected())
            assertContentEquals(committed, record.readBytes())
            val foreign = PluginAccountVault(SourceId.parse("plugin:example.other")!!, root, WindowsDpapi)
            val other = foreign.importToken("Other", "other-token", policy)
            val otherFile = root.walkTopDown().single { it.name == "${other.id}.dpapi" }
            otherFile.writeBytes(committed)
            assertFails { foreign.activate(other.id) }
            assertTrue(foreign.accounts().single().displayName.contains("无法解密"))
            record.writeBytes(byteArrayOf(1, 2, 3))
            assertFails { vault.lease() }
            assertTrue(vault.accounts().single().displayName.contains("无法解密"))
            assertContentEquals(byteArrayOf(1, 2, 3), record.readBytes())
            vault.remove(account.id)
            assertTrue(vault.accounts().isEmpty())
            assertTrue(otherFile.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun missingProtectionAndInvalidInputCannotCreatePlaintextSlots(): Unit {
        val root = Files.createTempDirectory("plugin-vault-unavailable").toFile()
        try {
            val vault = PluginAccountVault(source, root, null)
            assertFails { vault.importToken("Saved", "token", policy) }
            assertFails { vault.importToken("Saved", "header\ninjection", policy) }
            assertFails { vault.activate("../../escape") }
            assertTrue(root.walkTopDown().none { it.isFile })
        } finally { root.deleteRecursively() }
    }
}
