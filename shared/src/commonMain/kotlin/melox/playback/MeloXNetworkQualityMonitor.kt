package melox.playback

import kotlinx.coroutines.*
import melox.audio.MeloXNetworkQualityPolicy
import melox.audio.MusicQualityRuntime

/** Poll the Windows preferred Internet route off Main; apply only an effective quality change. */
object MeloXNetworkQualityMonitor {
    private var job: Job? = null
    @Synchronized fun start() {
        if (job?.isActive == true) return
        job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (isActive) {
                try { refresh() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { melox.platform.logWarn("MeloXNetworkQuality", "Unable to apply network quality: ${failure.javaClass.simpleName}") }
                delay(5_000)
            }
        }
    }
    suspend fun refresh() {
        val quality = withContext(Dispatchers.IO) { MeloXNetworkQualityPolicy.effectiveQuality() }
        withContext(Dispatchers.Main) {
            if (quality != MusicQualityRuntime.selected) PlaybackCommands.applyNetworkQuality(quality)
        }
    }
    @Synchronized fun stop() { job?.cancel(); job = null }
}
