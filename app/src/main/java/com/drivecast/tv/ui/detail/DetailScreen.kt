package com.drivecast.tv.ui.detail

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drivecast.tv.LocalAppContainer
import com.drivecast.tv.api.Episode
import com.drivecast.tv.api.SectionInfo
import com.drivecast.tv.api.Title
import com.drivecast.tv.api.WatchedProgress
import com.drivecast.tv.ui.common.PosterCard
import com.drivecast.tv.ui.common.SkeletonBox
import com.drivecast.tv.ui.common.StatusView
import com.drivecast.tv.ui.theme.Accent
import com.drivecast.tv.ui.theme.Background
import com.drivecast.tv.ui.theme.ErrorRed
import com.drivecast.tv.ui.theme.MotionTokens
import com.drivecast.tv.ui.theme.OnAccent
import com.drivecast.tv.ui.theme.SurfaceVariant
import com.drivecast.tv.ui.theme.TextPrimary
import com.drivecast.tv.ui.theme.TextSecondary
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.saveable.rememberSaveable
import com.drivecast.tv.ui.common.tvFocusRestorer
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlin.random.Random

@Composable
fun DetailScreen(
    titleId: String,
    onPlay: (titleId: String, fileId: String, startOver: Boolean, shuffle: Boolean, seed: Long) -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: DetailViewModel = viewModel(factory = DetailViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()

    // Detail is scoped to the nav back-stack entry (DetailViewModel), so detail -> player -> back
    // finds the same instance alive: only the watched-progress map is silently refreshed on
    // resume, never a refetch of the title, never a spinner.
    LifecycleResumeEffect(Unit) {
        vm.refreshProgress()
        onPauseOrDispose { }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = Background),
    ) {
        Crossfade(
            targetState = state.title == null,
            animationSpec = tween(300),
            label = "detailLoading",
        ) { loading ->
            if (loading) {
                if (state.error != null) {
                    StatusView(
                        title = if (state.networkError) "Can't reach your server" else "Couldn't load this title",
                        body = state.error ?: "",
                        primaryLabel = "Retry",
                        onPrimary = { vm.reload() },
                    )
                } else {
                    DetailSkeleton()
                }
            } else {
                DetailContent(
                    title = state.title!!,
                    progress = state.progress,
                    posterUrl = container.repository::posterUrl,
                    sections = container.repository.lastSections,
                    onPlay = onPlay,
                )
            }
        }
    }
}

/**
 * Mirrors [DetailContent]'s real layout at final dimensions: a poster-shaped box, three text-line
 * bars (title/metadata/overview), and six episode-row bars. Shimmer is capped at the poster plus
 * the first two episode rows — everything else is static, per [SkeletonBox]'s own Stick-GPU
 * guidance.
 */
@Composable
private fun DetailSkeleton() {
    Row(Modifier.fillMaxSize().padding(48.dp)) {
        SkeletonBox(
            modifier = Modifier.width(220.dp).aspectRatio(2f / 3f),
            animated = true,
        )
        Spacer(Modifier.width(40.dp))
        Column(Modifier.fillMaxHeight().weight(1f)) {
            SkeletonBox(Modifier.width(360.dp).height(36.dp), animated = false)
            Spacer(Modifier.height(12.dp))
            SkeletonBox(Modifier.width(200.dp).height(20.dp), animated = false)
            Spacer(Modifier.height(16.dp))
            SkeletonBox(Modifier.fillMaxWidth(0.7f).height(18.dp), animated = false)
            Spacer(Modifier.height(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(6) { i ->
                    SkeletonBox(
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        animated = i < 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailContent(
    title: Title,
    progress: Map<String, WatchedProgress>,
    posterUrl: (String?) -> String?,
    // Sections carry per-behavior vocabulary (a course tab -> Module/Lesson).
    // lastSections is already populated by Home's fetch, so no extra call.
    sections: List<SectionInfo>,
    onPlay: (String, String, Boolean, Boolean, Long) -> Unit,
) {
    val vocab = sections.find { it.key == title.section }
    Row(Modifier.fillMaxSize().padding(48.dp)) {
        PosterCard(
            title = title.displayTitle,
            posterUrl = posterUrl(title.poster),
            onClick = {},
            widthDp = 220.dp,
            // Inert: a dead first focus target with zero action. One less node for the D-pad
            // focus search to consider.
            modifier = Modifier.focusProperties { canFocus = false },
        )
        Spacer(Modifier.width(40.dp))
        Column(Modifier.fillMaxHeight().weight(1f)) {
            Text(title.displayTitle, style = MaterialTheme.typography.displaySmall, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                title.year?.let { Text(it.toString(), color = TextSecondary) }
                title.quality?.let { Text(it, color = TextSecondary) }
            }
            Spacer(Modifier.height(16.dp))
            title.overview?.let {
                Text(
                    it,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(24.dp))

            if (title.isShow) {
                ShowSeasons(title, progress, vocab, onPlay)
            } else {
                MovieActions(title, onPlay)
                MovieExtras(title, progress, vocab, onPlay)
            }
        }
    }
}

@Composable
private fun MovieActions(title: Title, onPlay: (String, String, Boolean, Boolean, Long) -> Unit) {
    val fileId = title.fileId
    if (fileId == null) {
        Text("This title has no playable file.", color = ErrorRed)
        return
    }
    // Deterministic initial focus: Play is the first thing the D-pad lands on. This composable
    // only enters composition once the real content is on screen (the Crossfade above gates
    // loading), so this never fires against the skeleton.
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Button(
            onClick = { onPlay(title.id, fileId, false, false, 0L) },
            modifier = Modifier.focusRequester(playFocus),
        ) { Text("Play") }
        Button(onClick = { onPlay(title.id, fileId, true, false, 0L) }) { Text("Start Over") }
    }
}

@Composable
private fun MovieExtras(
    title: Title,
    progress: Map<String, WatchedProgress>,
    vocab: SectionInfo?,
    onPlay: (String, String, Boolean, Boolean, Long) -> Unit,
) {
    val groups = title.extras.filter { it.episodes.isNotEmpty() }
    if (groups.isEmpty()) return
    Spacer(Modifier.height(24.dp))
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        groups.forEach { group ->
            item {
                Text(
                    group.name ?: "Extras",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            items(
                group.episodes,
                key = { it.fileId ?: it.hashCode() },
                contentType = { "episode" },
            ) { episode ->
                EpisodeRow(
                    episode = episode,
                    watched = episode.fileId?.let { progress[it]?.watched } ?: false,
                    percent = episode.fileId?.let { progress[it]?.percent } ?: 0.0,
                    episodeWord = vocab?.episode ?: "Episode",
                    // Single clip: play just this featurette, no queue.
                    onClick = { episode.fileId?.let { onPlay(title.id, it, false, false, 0L) } },
                )
            }
        }
    }
}

@Composable
private fun ShowSeasons(
    title: Title,
    progress: Map<String, WatchedProgress>,
    vocab: SectionInfo?,
    onPlay: (String, String, Boolean, Boolean, Long) -> Unit,
) {
    val seasons = title.seasons.filter { it.episodes.isNotEmpty() }
    if (seasons.isEmpty()) {
        Text("No episodes available.", color = ErrorRed)
        return
    }
    var selected by remember { mutableIntStateOf(0) }
    var focusedSeason by remember { mutableIntStateOf(0) }
    val current = seasons[selected.coerceIn(0, seasons.lastIndex)]
    // A course tab renders "Module N" / "Lesson N"; default is Season/Episode.
    val seasonWord = vocab?.season ?: "Season"
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    // Season-pill scrubbing was the laggiest interaction in the app: onFocusChanged used to
    // commit the season (and rebuild the whole episode list) on every pass-over keypress.
    // Now a focus pass-over only writes focusedSeason; a 250ms rest is what actually commits.
    LaunchedEffect(focusedSeason) {
        delay(250)
        if (focusedSeason != selected) selected = focusedSeason
    }
    LaunchedEffect(selected) { listState.animateScrollToItem(0) }

    // Deterministic initial focus: the first not-yet-watched episode in the opening season (the
    // resume point), or its first episode if every one is already watched. Fires once — this
    // composable only enters composition once real content is on screen.
    val resumeFocus = remember { FocusRequester() }
    val resumeIndex = remember(current, progress) {
        current.episodes.indexOfFirst { ep -> ep.fileId?.let { progress[it]?.watched != true } ?: true }
            .takeIf { it >= 0 } ?: 0
    }
    LaunchedEffect(Unit) { runCatching { resumeFocus.requestFocus() } }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                val firstFileId = title.seasons.filterNot { it.extras }
                    .flatMap { it.episodes }.firstNotNullOfOrNull { it.fileId }
                if (firstFileId != null) {
                    onPlay(title.id, firstFileId, true, true, Random.nextLong(0, Long.MAX_VALUE))
                }
            }) { Text("Shuffle") }
            Spacer(Modifier.width(12.dp))
            LazyRow(
                modifier = Modifier.weight(1f).tvFocusRestorer(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(seasons) { index, season ->
                    SeasonPill(
                        // Honour a pseudo-season label ("Featurettes") when present.
                        label = season.name ?: "$seasonWord ${season.season ?: (index + 1)}",
                        selected = index == selected.coerceIn(0, seasons.lastIndex),
                        onFocused = { focusedSeason = index },
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // Season swaps fade (alpha-only, GPU-safe) instead of hard-cutting.
        Crossfade(
            targetState = selected,
            animationSpec = tween(220, easing = LinearOutSlowInEasing),
            modifier = Modifier.fillMaxWidth().weight(1f),
            label = "seasonEpisodes",
        ) { seasonIdx ->
            val season = seasons[seasonIdx.coerceIn(0, seasons.lastIndex)]
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().tvFocusRestorer(),
            ) {
                itemsIndexed(
                    season.episodes,
                    key = { _, ep -> ep.fileId ?: ep.hashCode() },
                    contentType = { _, _ -> "episode" },
                ) { i, episode ->
                    EpisodeRow(
                        episode = episode,
                        watched = episode.fileId?.let { progress[it]?.watched } ?: false,
                        percent = episode.fileId?.let { progress[it]?.percent } ?: 0.0,
                        episodeWord = vocab?.episode ?: "Episode",
                        onClick = { episode.fileId?.let { onPlay(title.id, it, false, false, 0L) } },
                        focusRequester = if (seasonIdx == selected && i == resumeIndex) resumeFocus else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun SeasonPill(label: String, selected: Boolean, onFocused: () -> Unit) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) Accent else SurfaceVariant,
        animationSpec = tween(MotionTokens.DurationShort, easing = MotionTokens.Emphasized),
        label = "seasonPillContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) OnAccent else TextPrimary,
        animationSpec = tween(MotionTokens.DurationShort, easing = MotionTokens.Emphasized),
        label = "seasonPillContent",
    )
    Button(
        onClick = onFocused,
        colors = ButtonDefaults.colors(containerColor = containerColor, contentColor = contentColor),
        modifier = Modifier.onFocusChanged { if (it.isFocused) onFocused() },
    ) {
        Text(label, maxLines = 1)
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    watched: Boolean,
    percent: Double,
    episodeWord: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    Button(
        onClick = onClick,
        scale = ButtonDefaults.scale(focusedScale = 1.02f),
        modifier = Modifier
            .fillMaxWidth()
            .let { m -> focusRequester?.let { m.focusRequester(it) } ?: m },
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = episode.episode?.let { "%d. ".format(it) }.orEmpty() +
                    (episode.title?.ifBlank { null } ?: episode.name?.ifBlank { null } ?: episodeWord),
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            when {
                watched -> Text("✓", color = Accent)
                percent > 1.0 -> Text("${percent.toInt()}%", color = TextSecondary)
            }
        }
    }
}
