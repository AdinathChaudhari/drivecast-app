package com.drivecast.tv.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.drivecast.tv.api.WatchedProgress
import com.drivecast.tv.di.AppContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A single VLC hand-off the host composable should fire as an intent. */
data class VlcLaunchRequest(
    val uri: String,
    val title: String,
    val fromStart: Boolean,
    val positionMs: Long,
    val subtitleUrl: String?,
)

data class ExternalPlayerUiState(
    val loading: Boolean = true,
    val nowPlayingTitle: String? = null,
    val playing: Boolean = false,
    val error: String? = null,
    val upNext: UpNext? = null,
    val finished: Boolean = false,
)

/**
 * Drives playback handed off to VLC. Builds the same queue/resume as the
 * internal player via [PlaybackQueue], emits one-shot [VlcLaunchRequest]s the
 * host composable turns into intents, folds VLC's returned position back into
 * the server's progress reporting, autoplays the next episode, and keeps the
 * Mac awake while VLC is foreground (the in-app prompt can't be seen then).
 */
class ExternalPlayerViewModel(
    private val container: AppContainer,
    private val titleId: String,
    private val startFileId: String,
    private val startOver: Boolean,
) : ViewModel() {

    private val repo = container.repository

    private val _ui = MutableStateFlow(ExternalPlayerUiState())
    val ui: StateFlow<ExternalPlayerUiState> = _ui.asStateFlow()

    private val _launches = Channel<VlcLaunchRequest>(Channel.BUFFERED)
    val launches = _launches.receiveAsFlow()

    private var queue: List<QueueItem> = emptyList()
    private var currentIndex = 0
    private var progressMap: Map<String, WatchedProgress> = emptyMap()

    private var upNextJob: Job? = null
    private var awakeJob: Job? = null

    init {
        viewModelScope.launch { bootstrap() }
    }

    private suspend fun bootstrap() {
        val title = runCatching { repo.title(titleId) }.getOrNull()
        queue = PlaybackQueue.build(title, startFileId)
        currentIndex = PlaybackQueue.startIndex(queue, startFileId)
        progressMap = runCatching { repo.watchedMap().progress }.getOrDefault(emptyMap())
        launchCurrent(fromStart = startOver)
    }

    private suspend fun launchCurrent(fromStart: Boolean) {
        val item = queue.getOrNull(currentIndex)
        if (item == null) {
            _ui.value = _ui.value.copy(loading = false, playing = false, finished = true)
            return
        }
        val resumeMs = if (fromStart) 0L else PlaybackQueue.resumeMsFor(item, progressMap)
        val subtitle = runCatching { repo.probeSubtitle(item.fileId) }.getOrNull()
        val subtitleUrl = if (subtitle != null) repo.authorizedSubtitleUrl(item.fileId) else null
        _ui.value = _ui.value.copy(
            loading = false,
            nowPlayingTitle = item.name,
            playing = true,
            upNext = null,
            error = null,
        )
        _launches.send(
            VlcLaunchRequest(
                uri = repo.authorizedStreamUrl(item.fileId),
                title = item.name ?: "Video",
                fromStart = fromStart,
                positionMs = resumeMs,
                subtitleUrl = subtitleUrl,
            )
        )
    }

    /** Host tells us VLC actually launched; opt into the keep-awake handshake. */
    fun onVlcLaunched() {
        container.keepAwake.markPlaybackStarted()
        startAwakePolling()
    }

    /**
     * VLC returned. [positionMs] / [durationMs] are VLC's extras in ms, or < 0
     * when it reported nothing (in which case we must not clobber stored
     * progress). Reports progress in SECONDS, then autoplays or pops back.
     */
    fun onVlcResult(positionMs: Long, durationMs: Long) {
        stopAwakePolling()
        val item = queue.getOrNull(currentIndex)
        if (item == null) {
            _ui.value = _ui.value.copy(playing = false, finished = true)
            return
        }
        if (positionMs < 0) {
            // VLC gave us no position; leave the server's stored progress alone.
            _ui.value = _ui.value.copy(playing = false, finished = true)
            return
        }

        val durMs = if (durationMs > 0) durationMs else (item.durationMs ?: 0L)
        val fraction = if (durMs > 0) positionMs.toDouble() / durMs else 0.0
        val finishedEpisode = fraction >= 0.95

        val positionSec = positionMs / 1000.0
        val durationSec = if (durMs > 0) durMs / 1000.0 else null
        viewModelScope.launch {
            repo.postProgress(
                fileId = item.fileId,
                name = item.name,
                position = positionSec,
                duration = durationSec,
                ended = finishedEpisode,
            )
        }

        val nextIndex = currentIndex + 1
        if (finishedEpisode && nextIndex in queue.indices) {
            startUpNextCountdown(nextIndex)
        } else {
            _ui.value = _ui.value.copy(playing = false, finished = true)
        }
    }

    /** Host couldn't launch VLC (e.g. it vanished mid-session). */
    fun onLaunchFailed() {
        stopAwakePolling()
        _ui.value = _ui.value.copy(playing = false, finished = true)
    }

    private fun startUpNextCountdown(nextIndex: Int) {
        upNextJob?.cancel()
        val next = queue[nextIndex]
        _ui.value = _ui.value.copy(playing = false)
        upNextJob = viewModelScope.launch {
            for (seconds in 5 downTo 1) {
                _ui.value = _ui.value.copy(upNext = UpNext(next.name ?: "Next", seconds))
                delay(1_000)
            }
            _ui.value = _ui.value.copy(upNext = null)
            currentIndex = nextIndex
            launchCurrent(fromStart = true)
        }
    }

    fun playNextNow() {
        upNextJob?.cancel()
        if (_ui.value.upNext == null) return
        _ui.value = _ui.value.copy(upNext = null)
        val nextIndex = currentIndex + 1
        if (nextIndex in queue.indices) {
            currentIndex = nextIndex
            viewModelScope.launch { launchCurrent(fromStart = true) }
        } else {
            _ui.value = _ui.value.copy(finished = true)
        }
    }

    fun cancelUpNext() {
        upNextJob?.cancel()
        _ui.value = _ui.value.copy(upNext = null, finished = true)
    }

    private fun startAwakePolling() {
        if (awakeJob?.isActive == true) return
        awakeJob = viewModelScope.launch {
            while (isActive) {
                val status = container.keepAwake.poll()
                if (status?.phase == "prompt") container.keepAwake.extend()
                delay(30_000)
            }
        }
    }

    private fun stopAwakePolling() {
        awakeJob?.cancel()
        awakeJob = null
    }

    override fun onCleared() {
        stopAwakePolling()
        upNextJob?.cancel()
        super.onCleared()
    }

    companion object {
        fun factory(
            container: AppContainer,
            titleId: String,
            fileId: String,
            startOver: Boolean,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ExternalPlayerViewModel(container, titleId, fileId, startOver) as T
        }
    }
}
