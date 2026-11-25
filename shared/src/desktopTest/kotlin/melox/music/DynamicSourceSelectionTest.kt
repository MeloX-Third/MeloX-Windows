package melox.music

import melox.music.model.*
import melox.music.provider.MusicProviderSelectionStore as Selection
import melox.platform.getPreferences
import kotlin.test.*

class DynamicSourceSelectionTest {
    @Test fun explicitDynamicSelectionAndWhitelistSurviveBuiltInSelectionChanges() {
        val prefs = getPreferences("melox_music_providers")
        val keys = listOf("selected_source", "selected_source_id")
        val old = keys.associateWith { prefs.getString(it, null) }
        val plugins = prefs.getStringSet("unified_plugin_sources", null)
        val builtIns = prefs.getStringSet("unified_sources", null)
        try {
            prefs.remove("unified_plugin_sources")
            val plugin = SourceId.parse("plugin:example.catalog")!!
            Selection.setSelectedSource(MusicSource.Local)
            Selection.setSelectedSourceId(plugin)
            assertEquals(plugin, Selection.selectedSourceId())
            assertFalse(plugin in Selection.unifiedSourceIds())
            Selection.setUnifiedPluginEnabled(plugin, true)
            assertTrue(plugin in Selection.unifiedSourceIds())
            Selection.setSelectedSource(MusicSource.Netease)
            assertEquals(SourceId.fromBuiltIn(MusicSource.Netease), Selection.selectedSourceId())
            assertTrue(plugin in Selection.unifiedSourceIds())
            Selection.setUnifiedPluginEnabled(plugin, false)
            assertFalse(plugin in Selection.unifiedSourceIds())
            assertFailsWith<IllegalArgumentException> { Selection.setUnifiedPluginEnabled(SourceId.fromBuiltIn(MusicSource.Local), true) }
        } finally {
            old.forEach { (key, value) -> if (value == null) prefs.remove(key) else prefs.putString(key, value) }
            if (plugins == null) prefs.remove("unified_plugin_sources") else prefs.putStringSet("unified_plugin_sources", plugins)
            if (builtIns == null) prefs.remove("unified_sources") else prefs.putStringSet("unified_sources", builtIns)
        }
    }
}
