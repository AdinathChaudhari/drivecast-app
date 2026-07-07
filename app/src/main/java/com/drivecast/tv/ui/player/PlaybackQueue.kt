package com.drivecast.tv.ui.player

import com.drivecast.tv.api.Title
import com.drivecast.tv.api.WatchedProgress

/** One entry in the playback queue (a movie, or the ordered episodes of a show). */
data class QueueItem(val fileId: String, val name: String?, val durationMs: Long?)

/**
 * Shared playback bootstrap. Both the internal ExoPlayer path
 * ([PlayerViewModel]) and the external VLC hand-off ([ExternalPlayerViewModel])
 * build the same episode queue and honor the same resume rule through here, so
 * the two players always agree on what plays next and where it resumes.
 */
object PlaybackQueue {

    /** Build the ordered play queue for a title (all episodes of a show, or the single movie). */
    fun build(title: Title?, startFileId: String): List<QueueItem> = when {
        title == null -> listOf(QueueItem(startFileId, null, null))
        title.isShow -> title.seasons
            .flatMap { it.episodes }
            .mapNotNull { ep -> ep.fileId?.let { QueueItem(it, ep.name ?: ep.title, ep.durationMs) } }
            .ifEmpty { listOf(QueueItem(startFileId, title.title, null)) }
        else -> listOf(QueueItem(startFileId, title.title, title.durationMs))
    }

    /** Index of the requested start file in the queue, or 0 if it isn't found. */
    fun startIndex(queue: List<QueueItem>, startFileId: String): Int =
        queue.indexOfFirst { it.fileId == startFileId }.let { if (it < 0) 0 else it }

    /** Resume position in ms from the server's stored percent, when in the 1..90% band. */
    fun resumeMsFor(item: QueueItem, progress: Map<String, WatchedProgress>): Long {
        val percent = progress[item.fileId]?.percent ?: 0.0
        val durationMs = item.durationMs ?: return 0L
        if (percent <= 1.0 || percent >= 90.0) return 0L
        return (percent / 100.0 * durationMs).toLong()
    }
}
