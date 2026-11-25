package melox.ui.artwork

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

class PluginArtworkDimensionsTest {
    private fun png(width: Int, height: Int) = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY), "png", it)
    }.toByteArray()

    @Test fun checksDimensionsAndPixelCountBeforeAllocatingDecodedPixels(): Unit {
        validatePluginArtworkDimensions(png(32, 32))
        validatePluginArtworkDimensions(png(4096, 1))
        assertFails { validatePluginArtworkDimensions(png(4097, 1)) }
        assertFails { validatePluginArtworkDimensions(png(3000, 3000)) }
        assertFails { validatePluginArtworkDimensions(byteArrayOf(1, 2, 3)) }
    }
}
