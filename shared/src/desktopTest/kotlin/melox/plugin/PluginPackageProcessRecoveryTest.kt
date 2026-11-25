package melox.plugin

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import melox.music.model.SourceId
import org.json.JSONObject
import kotlin.test.*

/** Separate JVM terminates without finally blocks, exercising durable recovery rather than rollback. */
class PluginPackageProcessRecoveryTest {
    @Test fun processDeathDuringSignedPublisherRotationRecoversOneConsistentDecision() {
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val previousKey = generator.generateKeyPair()
        val nextKey = generator.generateKeyPair()
        val previous = signedArchive("1.0.0", previousKey)
        val next = signedArchive("2.0.0", nextKey)
        val source = requireNotNull(SourceId.parse("plugin:example.crash"))
        for (stage in listOf("decision-durable", "archive-durable", "publisher-durable")) {
            val root = Files.createTempDirectory("plugin-process-recovery").toFile()
            try {
                val directory = File(root, "packages")
                PluginPackageStore(directory).install(previous)
                val candidate = File(root, "candidate.zip").apply { writeBytes(next) }
                val output = File(root, "worker-output.txt")
                val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java.exe").absolutePath,
                    "-cp", checkNotNull(System.getProperty("melox.test.workerClasspath")),
                    PluginPackageCrashWorker::class.java.name, directory.absolutePath, candidate.absolutePath, stage)
                    .redirectErrorStream(true).redirectOutput(output).start()
                try {
                    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "worker did not terminate at $stage")
                    assertEquals(73, process.exitValue(), output.readText().take(2000))
                } finally { if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS) }
                val restored = PluginPackageStore(directory)
                val expected = PluginArchiveReader.read(if (stage == "decision-durable") previous else next)
                val actual = assertNotNull(restored.read(source))
                assertEquals(expected.archiveSha256, actual.archiveSha256, stage)
                assertEquals(expected.publisher?.fingerprint, restored.publisherFingerprint(source), stage)
                assertEquals(expected.publisher?.fingerprint, actual.publisher?.fingerprint, stage)
                assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".transaction") }, stage)
                assertEquals(expected.archiveSha256, PluginPackageStore(directory).read(source)?.archiveSha256, stage)
            } finally { root.deleteRecursively() }
        }
    }

    private fun signedArchive(version: String, key: KeyPair): ByteArray {
        val files = linkedMapOf(
            "manifest.json" to """{"id":"example.crash","name":"Crash","version":"$version","apiVersion":1,"entry":"index.js","capabilities":[]}""".toByteArray(),
            "index.js" to "globalThis.meloxPlugin={};".toByteArray())
        val hashes = JSONObject().apply { files.forEach { (name, bytes) -> put(name, PluginPackageTrust.sha256(bytes)) } }
        val integrity = JSONObject().put("version", 1).put("files", hashes).toString().toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(key.private); update("MeloX-package-v1\n".toByteArray(Charsets.US_ASCII)); update(integrity); sign()
        }
        files["integrity.json"] = integrity
        files["publisher.json"] = JSONObject().put("algorithm", "RSA-SHA256")
            .put("publicKey", Base64.getEncoder().encodeToString(key.public.encoded))
            .put("signature", Base64.getEncoder().encodeToString(signature)).toString().toByteArray()
        return ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip -> files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            } }
        }.toByteArray()
    }
}

object PluginPackageCrashWorker {
    @JvmStatic fun main(args: Array<String>) {
        val source = requireNotNull(SourceId.parse("plugin:example.crash"))
        PluginPackageStore(File(args[0])) { stage ->
            if (stage == args[2]) Runtime.getRuntime().halt(73)
        }.update(source, File(args[1]).readBytes()) { candidate ->
            PluginPackageTrust.requireUpdate(checkNotNull(PluginPackageStore(File(args[0])).read(source)),
                candidate, candidate.archiveSha256)
        }
        error("checkpoint not reached")
    }
}
