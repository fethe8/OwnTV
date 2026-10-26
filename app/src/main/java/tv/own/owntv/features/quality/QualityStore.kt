package tv.own.owntv.features.quality

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.own.owntv.ui.stage.StageTag

/**
 * What the app has learned about channels, in memory only: nothing is written to disk and it is gone when
 * the app closes. Only the most recent [MAX] channels are kept, so it can never grow.
 */
object QualityStore {
    private const val MAX = 3000

    private val _entries = MutableStateFlow<Map<Long, QualityEntry>>(emptyMap())
    val entries: StateFlow<Map<Long, QualityEntry>> = _entries.asStateFlow()

    @Synchronized
    fun put(id: Long, e: QualityEntry) {
        val m = LinkedHashMap(_entries.value)
        m.remove(id)
        m[id] = e
        while (m.size > MAX) m.remove(m.keys.first())
        _entries.value = m
    }
}

/** The coloured tag on a channel row; nothing for a channel that has not been watched yet. */
@Composable
fun QualityBadge(channelId: Long) {
    val map by QualityStore.entries.collectAsStateWithLifecycle()
    val e = map[channelId] ?: return
    StageTag(e.badge(), tint = e.tier.color)
}
