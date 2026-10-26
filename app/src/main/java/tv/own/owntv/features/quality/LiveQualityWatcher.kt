package tv.own.owntv.features.quality

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.own.owntv.player.LivePreviewEngine
import tv.own.owntv.player.OwnTVPlayer

/**
 * Learns a channel's quality from the channel the person is actually WATCHING: no extra connection, no
 * second decoder, no load on the provider. It reads what the real player reports (picture size, bitrate,
 * stalls, errors) and remembers it in [QualityStore]. Nothing runs for channels that are not watched.
 *
 * A channel that plays but never shows a picture (the black-screen kind) is marked OFF; so is one that
 * fails with a real player error. One that is merely slow to start is left alone.
 */
object LiveQualityWatcher {
    private class Src(
        val playing: StateFlow<Boolean>,
        val error: StateFlow<*>,
        val buffering: StateFlow<Boolean>,
        val audioOnly: StateFlow<Boolean>,
        val res: StateFlow<String?>,
        val chips: StateFlow<List<String>>,
    )

    private const val POLL_MS = 1_000L
    private const val WAIT_START_S = 30      // slow to start is not a verdict
    private const val BLACK_AFTER_S = 10     // playing this long with no picture size = black screen
    private const val STALL_WINDOW_S = 30    // count rebuffering this long before the final record

    private val SIZE_X = Regex("(\\d{2,5})\\s*[x×]\\s*(\\d{2,5})")
    private val SIZE_P = Regex("(\\d{3,4})\\s*[pi]")
    private val MBPS = Regex("(\\d+(?:[.,]\\d+)?)\\s*(?:Mbps|Mb/s|Mbit)", RegexOption.IGNORE_CASE)
    private val KBPS = Regex("(\\d+)\\s*(?:kbps|kb/s)", RegexOption.IGNORE_CASE)
    private val FPS = Regex("(\\d+(?:[.,]\\d+)?)\\s*fps", RegexOption.IGNORE_CASE)

    private var job: Job? = null

    /** Call when a channel is tuned; a new call replaces the previous one. Whichever engine starts playing is the one read. */
    fun watch(scope: CoroutineScope, player: OwnTVPlayer, preview: LivePreviewEngine, id: Long) {
        val sources = listOf(
            Src(player.isPlaying, player.error, player.buffering, player.audioOnly, player.videoRes, player.streamChips),
            Src(preview.isPlaying, preview.error, preview.buffering, preview.audioOnly, preview.videoRes, preview.streamChips),
        )
        job?.cancel()
        job = scope.launch {
            var e: Src? = null
            var waited = 0
            while (isActive && e == null && waited < WAIT_START_S) {
                delay(POLL_MS); waited++
                e = sources.firstOrNull { it.playing.value }
                if (e == null && sources.any { it.error.value != null }) { put(id, 0, 0, 0, 0, QTier.ERROR, 0); return@launch }
            }
            val s = e ?: return@launch

            // wait for a picture size
            var w = 0; var h = 0; var playingS = 0
            while (isActive && h == 0) {
                if (s.audioOnly.value) { put(id, 0, 0, 0, 0, QTier.AUDIO, 0); return@launch }
                val size = size(s.res.value)
                w = size.first; h = size.second
                if (h > 0) break
                if (!s.playing.value) { if (s.error.value != null) { put(id, 0, 0, 0, 0, QTier.ERROR, 0); return@launch } }
                else if (++playingS >= BLACK_AFTER_S) { put(id, 0, 0, 0, 0, QTier.ERROR, 0); return@launch }
                delay(POLL_MS)
            }
            if (h == 0) return@launch

            // record at once, then again after counting stalls
            var chips = s.chips.value
            put(id, w, h, fps10(chips), kbps(chips), classify(h, kbps(chips) / 1000f, 0, false, true), 0)
            var stalls = 0; var was = false; var t = 0
            while (isActive && t < STALL_WINDOW_S) {
                delay(POLL_MS); t++
                val b = s.buffering.value
                if (b && !was && t > 3) stalls++
                was = b
            }
            if (!isActive) return@launch
            chips = s.chips.value
            val again = size(s.res.value)
            if (again.second > h) { w = again.first; h = again.second }
            put(id, w, h, fps10(chips), kbps(chips), classify(h, kbps(chips) / 1000f, stalls, false, true), stalls)
        }
    }

    private fun put(id: Long, w: Int, h: Int, fps10: Int, kbps: Int, tier: QTier, stalls: Int) =
        QualityStore.put(id, QualityEntry(w, h, fps10, kbps, tier, stalls))

    // "1920x1080", "1920 × 1080", "1080p" ...
    private fun size(s: String?): Pair<Int, Int> {
        if (s.isNullOrBlank()) return 0 to 0
        SIZE_X.find(s)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        SIZE_P.find(s)?.let { val h = it.groupValues[1].toInt(); return (h * 16 / 9) to h }
        return 0 to 0
    }

    private fun kbps(chips: List<String>): Int {
        for (c in chips) {
            MBPS.find(c)?.let { return (it.groupValues[1].replace(',', '.').toFloat() * 1000).toInt() }
            KBPS.find(c)?.let { return it.groupValues[1].toInt() }
        }
        return 0
    }

    private fun fps10(chips: List<String>): Int {
        for (c in chips) FPS.find(c)?.let { return (it.groupValues[1].replace(',', '.').toFloat() * 10).toInt() }
        return 0
    }
}
