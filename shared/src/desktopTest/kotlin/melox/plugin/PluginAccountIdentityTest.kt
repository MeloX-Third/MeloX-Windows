package melox.plugin

import melox.music.model.*
import kotlin.test.*

class PluginAccountIdentityTest {
    @Test fun accountIdentityKeepsSourceNamespaceAndBuiltInCompatibility() {
        val first = MusicAccountSummary(SourceId.parse("plugin:example.first")!!, "same-account", "First")
        val second = MusicAccountSummary(SourceId.parse("plugin:example.second")!!, "same-account", "Second")
        assertNotEquals(first.sourceId, second.sourceId)
        assertEquals(first.sourceId, first.copy(displayName = "Renamed").sourceId)
        assertFailsWith<IllegalStateException> { first.source }
        val legacy = MusicAccountSummary(source = MusicSource.Netease, id = "legacy", displayName = "Account")
        assertEquals(MusicSource.Netease, legacy.source)
        assertEquals(SourceId.fromBuiltIn(MusicSource.Netease), legacy.sourceId)
        assertEquals(first, (MusicAuthenticationState.SignedIn(first)).account)
    }
}
