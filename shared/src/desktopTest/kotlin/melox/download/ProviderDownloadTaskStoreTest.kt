package melox.download

import java.nio.file.Files
import java.util.UUID
import melox.music.model.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.*

class ProviderDownloadTaskStoreTest {
    private val track = MusicTrack(MusicResourceId(SourceId.parse("plugin:example.catalog")!!, "123"), "Track", emptyList())
    @Test fun interruptedTasksRestoreWithoutMediaCredentialsAndStaleRemovalKeepsRetry() {
        val directory = Files.createTempDirectory("melox-task-test").toFile()
        try {
            val store = ProviderDownloadTaskStore(directory)
            val first = ProviderDownloadTaskStore.Task(track, AudioQualityTier.Lossless, UUID.randomUUID().toString())
            store.save(first)
            val restored = ProviderDownloadTaskStore(directory).load().single()
            assertEquals(first.copy(errorCode = "DOWNLOAD_INTERRUPTED"), restored)
            val retry = first.copy(revision = UUID.randomUUID().toString(), errorCode = "DOWNLOAD_FAILED")
            store.save(retry)
            store.remove(track.id, first.revision)
            assertEquals(retry, store.load().single())
            val raw = directory.resolve("tasks-v1.json").readText()
            assertFalse(raw.contains("requestHeaders"))
            assertFalse(raw.contains("https://"))
            store.remove(track.id, retry.revision)
            assertTrue(store.load().isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun unknownRowsSurviveUpdatesAndCorruptFileCannotBeOverwritten() {
        val directory = Files.createTempDirectory("melox-task-test").toFile()
        try {
            val file = directory.resolve("tasks-v1.json")
            val future = JSONObject().put("source", "future").put("id", "x").put("future", true)
            file.writeText(JSONArray().put(future).put("opaque").toString())
            val store = ProviderDownloadTaskStore(directory)
            val task = ProviderDownloadTaskStore.Task(track, AudioQualityTier.Standard, UUID.randomUUID().toString())
            store.save(task)
            store.remove(track.id, task.revision)
            val rows = JSONArray(file.readText())
            assertEquals(2, rows.length())
            assertTrue(rows.getJSONObject(0).getBoolean("future"))
            assertEquals("opaque", rows.getString(1))
            file.writeText("corrupt")
            assertFails { store.save(task) }
            assertEquals("corrupt", file.readText())
            assertFailsWith<IllegalArgumentException> { store.save(task.copy(revision = "invalid")) }
            assertFailsWith<IllegalArgumentException> { store.save(task.copy(errorCode = "untrusted message")) }
        } finally { directory.deleteRecursively() }
    }
}
