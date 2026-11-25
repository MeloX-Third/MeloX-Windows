package melox.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VolumeSliderPreferenceTest {
    @Test fun volumeSliderVisibilityUpdatesImmediatelyAndReloads() {
        val previous = MeloXSettingsPreferences.boolean("playback_show_volume_slider", true)
        try {
            MeloXSettingsPreferences.setBoolean("playback_show_volume_slider", false)
            assertFalse(MeloXSettingsRuntime.showVolumeSlider)
            // Discard the runtime value to verify initialization reads the saved preference.
            MeloXSettingsRuntime.showVolumeSlider = true
            MeloXSettingsRuntime.initialize(force = true)
            assertFalse(MeloXSettingsRuntime.showVolumeSlider)
            MeloXSettingsPreferences.setBoolean("playback_show_volume_slider", true)
            assertTrue(MeloXSettingsRuntime.showVolumeSlider)
        } finally {
            MeloXSettingsPreferences.setBoolean("playback_show_volume_slider", previous)
        }
    }
}
