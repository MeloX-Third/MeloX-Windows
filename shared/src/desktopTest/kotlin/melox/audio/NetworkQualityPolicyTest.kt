package melox.audio

import kotlin.test.*

class NetworkQualityPolicyTest {
    @Test fun disabledAndUnknownConnectionsPreserveExplicitDefault() {
        for (network in listOf(null, false, true)) assertEquals(MusicQuality.High,
            MeloXNetworkQualityPolicy.resolveEffectiveQuality(false, network, MusicQuality.Lossless, MusicQuality.Standard, MusicQuality.High))
        assertEquals(MusicQuality.High, MeloXNetworkQualityPolicy.resolveEffectiveQuality(true, null,
            MusicQuality.Lossless, MusicQuality.Standard, MusicQuality.High))
        assertEquals(MusicQuality.Lossless, MeloXNetworkQualityPolicy.resolveEffectiveQuality(true, false,
            MusicQuality.Lossless, MusicQuality.Standard, MusicQuality.High))
        assertEquals(MusicQuality.Standard, MeloXNetworkQualityPolicy.resolveEffectiveQuality(true, true,
            MusicQuality.Lossless, MusicQuality.Standard, MusicQuality.High))
    }
}
