package tv.own.owntv.features.quality

import androidx.compose.ui.graphics.Color

/** What a channel is worth, best to worst. */
enum class QTier(val label: String, val color: Color) {
    ERROR("OFF", Color(0xFFE5484D)),
    AUDIO("AUDIO", Color(0xFF8B8D98)),
    LOW("LOW", Color(0xFFF76B15)),
    SD("SD", Color(0xFFF5A524)),
    HD("HD", Color(0xFF3DB7C9)),
    FHD("FHD", Color(0xFF30A46C)),
    UHD("4K", Color(0xFF8E7CF0)),
    UHD8("8K", Color(0xFFD16BDA)),
}

/** What the player reported for one channel. [fps10] is frames per second x10 and [kbps] the bitrate; 0 = unknown. */
data class QualityEntry(
    val width: Int,
    val height: Int,
    val fps10: Int,
    val kbps: Int,
    val tier: QTier,
    val stalls: Int,
) {
    /** One 0-10 number: OFF 0, AUDIO 1, LOW 2, SD 4, HD 6, FHD 8, 4K 9, 8K 10; +1 for 50/60 fps, -1 when it stalled. */
    fun index(): Int {
        val base = when (tier) {
            QTier.ERROR -> return 0
            QTier.AUDIO -> return 1
            QTier.LOW -> 2
            QTier.SD -> 4
            QTier.HD -> 6
            QTier.FHD -> 8
            QTier.UHD -> 9
            QTier.UHD8 -> 10
        }
        return (base + (if (fps10 >= 480) 1 else 0) - (if (stalls >= 2) 1 else 0)).coerceIn(1, 10)
    }

    /** Badge text: the tier and its 0-10 index, e.g. "FHD 9"; a dead channel is just "OFF". */
    fun badge(): String = if (tier == QTier.ERROR) tier.label else tier.label + " " + index()
}

/**
 * Turns what the player reported into a tier. Resolution sets it; a starved bitrate drops it one step
 * (a 1080p stream at 1 Mbps looks worse than a healthy 720p), and so does repeated stalling.
 */
fun classify(height: Int, mbps: Float, stalls: Int, failed: Boolean, playing: Boolean): QTier {
    if (failed) return QTier.ERROR
    if (height <= 0) return if (playing) QTier.AUDIO else QTier.ERROR
    var t = when {
        height >= 4000 -> QTier.UHD8
        height >= 2000 -> QTier.UHD
        height >= 1000 -> QTier.FHD
        height >= 700 -> QTier.HD
        height >= 450 -> QTier.SD
        else -> QTier.LOW
    }
    if (mbps > 0f) {
        val floor = when (t) {
            QTier.UHD8 -> 20f
            QTier.UHD -> 8f
            QTier.FHD -> 3f
            QTier.HD -> 1.5f
            QTier.SD -> 0.7f
            else -> 0f
        }
        if (mbps < floor) t = lower(t)
    }
    if (stalls >= 3) t = lower(t)
    return t
}

private fun lower(t: QTier): QTier = if (t.ordinal > QTier.LOW.ordinal) QTier.entries[t.ordinal - 1] else t
