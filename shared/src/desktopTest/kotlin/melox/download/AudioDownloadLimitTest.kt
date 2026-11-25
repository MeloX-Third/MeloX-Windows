package melox.download

import java.io.IOException
import kotlin.test.*

class AudioDownloadLimitTest {
    @Test fun unknownLengthStreamCannotWritePastLimitAndExactBoundaryCompletes() {
        val data = ByteArray(65537) { (it % 127).toByte() }
        val exact = java.io.ByteArrayOutputStream()
        assertEquals(65537, AudioDownloadLimit.copy(data.inputStream(), exact, 65537).toInt())
        assertContentEquals(data, exact.toByteArray())
        val oversized = java.io.ByteArrayOutputStream()
        assertFailsWith<IOException> { AudioDownloadLimit.copy(data.inputStream(), oversized, 65536) }
        assertEquals(65536, oversized.size())
        assertContentEquals(data.copyOf(65536), oversized.toByteArray())
    }
    @Test fun knownAndUnknownSizesUseTheSameBoundary() {
        AudioDownloadLimit.check(-1)
        AudioDownloadLimit.check(AudioDownloadLimit.MAX_BYTES)
        assertFailsWith<IOException> { AudioDownloadLimit.check(AudioDownloadLimit.MAX_BYTES + 1) }
    }
}
