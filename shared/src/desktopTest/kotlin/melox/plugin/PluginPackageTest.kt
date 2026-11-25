package melox.plugin

import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import kotlin.test.*

class PluginPackageTest {
    private val manifest = """{"id":"example.music","name":"Example","version":"1.0.0","apiVersion":1,"entry":"index.js","capabilities":["search","playback"],"networkDomains":["example.com"]}"""
    private fun payload() = linkedMapOf("manifest.json" to manifest.toByteArray(), "index.js" to "globalThis.meloxPlugin={};".toByteArray())
    private fun archive(files: Map<String, ByteArray>) = ByteArrayOutputStream().apply {
        ZipOutputStream(this).use { zip -> files.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()

    @Test fun unsignedPackagePreservesManifestAndScript() {
        val bytes = archive(payload())
        val plugin = PluginArchiveReader.read(bytes)
        assertEquals("plugin:example.music", plugin.manifest.sourceId.value)
        assertEquals(setOf(PluginCapability.Search, PluginCapability.Playback), plugin.manifest.capabilities)
        assertTrue(plugin.manifest.isMusicSource)
        assertEquals("globalThis.meloxPlugin={};", plugin.script)
        assertEquals(PluginPackageTrust.sha256(bytes), plugin.archiveSha256)
        assertNull(plugin.publisher)
        assertFalse(plugin.integrityChecked)
    }

    @Test fun rejectsPathsBadUtf8MissingEntryAndExpansionBomb() {
        assertFails { PluginArchiveReader.read(archive(payload().apply { put("../outside", byteArrayOf(1)) })) }
        assertFails { PluginArchiveReader.read(archive(payload().apply { put("C:\\outside", byteArrayOf(1)) })) }
        assertFails { PluginArchiveReader.read(archive(payload().apply { put("index.js", byteArrayOf(0xc3.toByte(), 0x28)) })) }
        assertFails { PluginArchiveReader.read(archive(payload().apply { remove("index.js") })) }
        assertFails { PluginArchiveReader.read(archive(payload().apply { put("index.js", ByteArray(128 * 1024 + 1) { 65 }) })) }
        assertFails { PluginArchiveReader.read(archive(payload().apply { put("large.bin", ByteArray(8 * 1024 * 1024) { 65 }) })) }
    }

    @Test fun manifestRejectsUnknownDuplicateAndCredentialPermissionExpansion() {
        val original = JSONObject(manifest)
        listOf(
            JSONObject(original.toString()).put("apiVersion", "1"),
            JSONObject(original.toString()).put("entry", "../index.js"),
            JSONObject(original.toString()).put("capabilities", listOf("unknown")),
            JSONObject(original.toString()).put("capabilities", listOf("search", "search")),
            JSONObject(original.toString()).put("networkDomains", listOf("*.example.com")),
            JSONObject(original.toString()).put("capabilities", listOf("authentication")).put("authentication",
                JSONObject().put("type", "apiToken").put("header", "Authorization").put("prefix", "Bearer ").put("domains", listOf("foreign.com"))),
        ).forEach { assertFails { PluginManifestParser.parse(it.toString()) } }
        assertFalse(PluginManifestParser.parse(JSONObject(manifest).put("capabilities", listOf("resolver")).toString()).isMusicSource)
    }

    private fun signed(): MutableMap<String, ByteArray> {
        val files = payload()
        val hashes = JSONObject()
        files.forEach { (name, bytes) -> hashes.put(name, PluginPackageTrust.sha256(bytes)) }
        val integrity = JSONObject().put("version", 1).put("files", hashes).toString().toByteArray()
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(pair.private); update("MeloX-package-v1\n".toByteArray(Charsets.US_ASCII)); update(integrity)
        }.sign()
        files["integrity.json"] = integrity
        files["publisher.json"] = JSONObject().put("algorithm", "RSA-SHA256")
            .put("publicKey", Base64.getEncoder().encodeToString(pair.public.encoded))
            .put("signature", Base64.getEncoder().encodeToString(signature)).toString().toByteArray()
        return files
    }

    @Test fun verifiesPublisherRejectsTamperingAndBindsPublisherChangeApprovalToArchive() {
        val files = signed()
        val first = PluginArchiveReader.read(archive(files))
        assertNotNull(first.publisher)
        assertTrue(first.integrityChecked)
        assertFails { PluginArchiveReader.read(archive(files.toMutableMap().apply { put("index.js", "tampered".toByteArray()) })) }
        assertFails { PluginArchiveReader.read(archive(files.toMutableMap().apply { put("extra.js", "extra".toByteArray()) })) }
        val next = PluginArchiveReader.read(archive(signed()))
        assertFails { PluginPackageTrust.requireUpdate(first, next, first.archiveSha256) }
        PluginPackageTrust.requireUpdate(first, next, next.archiveSha256)
        assertFails { PluginPackageTrust.requireUpdate(first, PluginArchiveReader.read(archive(payload())), null) }
    }
}
