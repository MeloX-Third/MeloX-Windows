package melox.audio

import melox.platform.getPreferences

/** Network presets affect effective playback quality, never the saved explicit default. */
object MeloXNetworkQualityPolicy {
    private fun preferences() = getPreferences("melox_network_quality")
    fun isEnabled() = preferences().getBoolean("enabled", false)
    fun setEnabled(enabled: Boolean) = preferences().putBoolean("enabled", enabled)
    fun wifiQuality() = MusicQuality.fromApiLevel(preferences().getString("wifi_quality", null)) ?: MusicQualityPreferences.read()
    fun mobileQuality() = MusicQuality.fromApiLevel(preferences().getString("mobile_quality", null)) ?: MusicQualityPreferences.read()
    fun setWifiQuality(quality: MusicQuality) = preferences().putString("wifi_quality", quality.apiLevel)
    fun setMobileQuality(quality: MusicQuality) = preferences().putString("mobile_quality", quality.apiLevel)
    fun effectiveQuality(): MusicQuality = resolveEffectiveQuality(isEnabled(),
        if (isEnabled()) melox.platform.networkUsesMeteredConnection() else null,
        wifiQuality(), mobileQuality(), MusicQualityPreferences.read())

    internal fun resolveEffectiveQuality(enabled: Boolean, cellular: Boolean?, wifi: MusicQuality,
        mobile: MusicQuality, fallback: MusicQuality): MusicQuality = when {
        !enabled -> fallback
        cellular == true -> mobile
        cellular == false -> wifi
        else -> fallback
    }
}
