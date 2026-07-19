package com.drivecast.tv.ui.detail

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.drivecast.tv.LocalAppContainer
import com.drivecast.tv.api.Episode
import com.drivecast.tv.api.SectionInfo
import com.drivecast.tv.api.Title
import com.drivecast.tv.api.WatchedProgress
import com.drivecast.tv.ui.common.PosterFallback
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
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
 * Mirrors [DetailContent]'s real full-bleed-hero anatomy at final dimensions — a top-65% backdrop
 * box with title/metadata/overview bars bottom-left inside the 48dp/27dp overscan safe zone, then
 * full-width episode-row bars below — so the loading->content Crossfade is a fill-in, not a
 * complete layout rearrangement. Shimmer is capped at the hero plus the first two row bars —
 * everything else is static, per [SkeletonBox]'s own Stick-GPU guidance.
 */
@Composable
private fun DetailSkeleton() {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.65f)) {
            SkeletonBox(
                modifier = Modifier.fillMaxSize(),
                shape = RectangleShape,
                animated = true,
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 48.dp, end = 48.dp, bottom = 27.dp),
            ) {
                // Matches DetailHero's displayLarge title.
                SkeletonBox(Modifier.fillMaxWidth(0.55f).height(48.dp), animated = false)
                Spacer(Modifier.height(6.dp))
                // Matches the single dot-separated metadata line.
                SkeletonBox(Modifier.width(220.dp).height(20.dp), animated = false)
                Spacer(Modifier.height(16.dp))
                // Matches the maxLines=3 overview, width-capped like the real Text.
                SkeletonBox(Modifier.fillMaxWidth(0.4f).widthIn(max = 560.dp).height(18.dp), animated = false)
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 48.dp)
                .padding(top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(5) { i ->
                SkeletonBox(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    animated = i < 2,
                )
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
    Column(Modifier.fillMaxSize()) {
        DetailHero(title = title, posterUrl = posterUrl(title.poster)) {
            // Movies get their Play/Start Over row inline in the hero, where Play's
            // deterministic focus (WI-11) lands. Shows keep Shuffle + season selection
            // in their own header row below the hero, unchanged from WI-11.
            if (!title.isShow) MovieActions(title, onPlay)
        }
        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 48.dp)) {
            if (title.isShow) {
                ShowSeasons(title, progress, vocab, onPlay)
            } else {
                MovieExtras(title, progress, vocab, onPlay)
            }
        }
    }
}

/**
 * Full-bleed backdrop hero (JetStream recipe): a Ken-Burns-idle poster image occupying the top
 * ~65% of the screen, scrimmed with a bottom vertical gradient (readability under the content
 * column) and a left horizontal gradient (readability behind the text column), both fading into
 * the Background token — not pure black — so the image dissolves into the UI. The content column
 * sits bottom-left inside the 48dp/27dp overscan safe zone. Requests the exact same URL + pixel
 * size (960x540) that home's cards/backdrop already requested, so Coil's cache (WI-02) makes it
 * appear during the nav enter-fade — a faked shared-element transition at zero new cost.
 */
@Composable
private fun DetailHero(
    title: Title,
    posterUrl: String?,
    actions: @Composable () -> Unit,
) {
    val imageLoader = LocalAppContainer.current.imageLoader
    val context = LocalContext.current

    // Imperceptible as "animation" but keeps the hero feeling alive while idle. Pure
    // graphicsLayer scale — RenderThread-cheap, never recomposes.
    val kenBurns = rememberInfiniteTransition(label = "kenBurns")
    val scale by kenBurns.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(25_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "kenBurnsScale",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.65f),
    ) {
        // Image + scrims live in their own inner Box, clipped to the hero's bounds, and drawn
        // as the FIRST child — the content Column below is a later sibling, so it always paints
        // above the scrims instead of being painted over by them. clipToBounds() also keeps the
        // Ken Burns scale (which is never clipped by graphicsLayer itself) from bleeding past the
        // hero's edges into the seasons/episode region below.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clipToBounds()
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Background),
                            startY = size.height * 0.4f,
                            endY = size.height,
                        ),
                    )
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Background, Color.Transparent),
                            startX = 0f,
                            endX = size.width * 0.5f,
                        ),
                    )
                },
        ) {
            if (posterUrl != null) {
                val request = remember(posterUrl) {
                    ImageRequest.Builder(context)
                        .data(posterUrl)
                        .size(960, 540)
                        .build()
                }
                AsyncImage(
                    model = request,
                    imageLoader = imageLoader,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { scaleX = scale; scaleY = scale },
                )
            } else {
                PosterFallback(title = title.displayTitle, modifier = Modifier.fillMaxSize())
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 48.dp, end = 48.dp, bottom = 27.dp),
        ) {
            Text(
                text = title.displayTitle,
                color = TextPrimary,
                style = MaterialTheme.typography.displayLarge.copy(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.5f),
                        offset = Offset(0f, 2f),
                        blurRadius = 8f,
                    ),
                ),
            )
            val meta = remember(title) {
                buildList {
                    title.year?.let { add(it.toString()) }
                    title.quality?.let { add(it) }
                    if (title.isShow) {
                        val episodeCount = title.seasons.filterNot { it.extras }.sumOf { it.episodes.size }
                        if (episodeCount > 0) {
                            add(if (episodeCount == 1) "1 episode" else "$episodeCount episodes")
                        }
                    }
                }.joinToString(" · ")
            }
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(meta, style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
            }
            title.overview?.let {
                Spacer(Modifier.height(16.dp))
                Text(
                    it,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 560.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            actions()
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
    // rememberSaveable (not plain remember): a detail->player->back round-trip disposes and
    // recomposes this destination, and the episode LazyListState below is already saveable — the
    // chosen season must survive the same round-trip instead of resetting to 0.
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var focusedSeason by rememberSaveable { mutableIntStateOf(0) }
    val current = seasons[selected.coerceIn(0, seasons.lastIndex)]
    // A course tab renders "Module N" / "Lesson N"; default is Season/Episode.
    val seasonWord = vocab?.season ?: "Season"
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    // Season-pill scrubbing was the laggiest interaction in the app: onFocusChanged used to
    // commit the season (and rebuild the whole episode list) on every pass-over keypress, and a
    // composition-scope LaunchedEffect(focusedSeason) key recomposed this whole body on every
    // pass-over too. snapshotFlow + collectLatest reproduces the 250ms dwell-commit with zero
    // composition-scope reads: a new pass-over cancels the previous delay before it commits.
    LaunchedEffect(Unit) {
        snapshotFlow { focusedSeason }.collectLatest { candidate ->
            delay(250)
            if (candidate != selected) selected = candidate
        }
    }

    // Only scroll-to-top when the season actually CHANGES after entry — firing unconditionally on
    // every composition (including the very first, right after a player round-trip) would wipe the
    // scroll offset the saveable `listState` just restored.
    var previousSelected by rememberSaveable { mutableIntStateOf(selected) }
    LaunchedEffect(selected) {
        if (selected != previousSelected) {
            previousSelected = selected
            listState.animateScrollToItem(0)
        }
    }

    // Deterministic initial focus: the first not-yet-watched episode in the opening season (the
    // resume point), or its first episode if every one is already watched. Gated to FIRST entry
    // only via a rememberSaveable flag — on a back-nav return the restored saved scroll must win,
    // and tvFocusRestorer already owns focus restoration for the list.
    val resumeFocus = remember { FocusRequester() }
    val seasonFirst = remember { FocusRequester() }
    val resumeIndex = remember(current, progress) {
        current.episodes.indexOfFirst { ep -> ep.fileId?.let { progress[it]?.watched != true } ?: true }
            .takeIf { it >= 0 } ?: 0
    }
    var hasRequestedResumeFocus by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (hasRequestedResumeFocus) return@LaunchedEffect
        hasRequestedResumeFocus = true
        // The hero occupies 65% of the screen, so only ~3-4 EpisodeRows compose in the initial
        // viewport — for any show watched past the first few episodes, the resume row would never
        // be composed and requestFocus() would throw and be swallowed. Scroll to it first, then
        // wait for it to actually be laid out before requesting focus.
        listState.scrollToItem(resumeIndex)
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.index == resumeIndex } }
            .filter { it }
            .first()
        runCatching { resumeFocus.requestFocus() }
    }

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
                modifier = Modifier.weight(1f).tvFocusRestorer { seasonFirst },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(seasons) { index, season ->
                    SeasonPill(
                        // Honour a pseudo-season label ("Featurettes") when present.
                        label = season.name ?: "$seasonWord ${season.season ?: (index + 1)}",
                        selected = index == selected.coerceIn(0, seasons.lastIndex),
                        onFocused = { focusedSeason = index },
                        modifier = if (index == 0) Modifier.focusRequester(seasonFirst) else Modifier,
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
            // A LazyListState may only be hosted by one lazy layout at a time, and during the
            // ~220ms Crossfade the outgoing AND incoming LazyColumns are composed simultaneously.
            // Only the child matching the current `selected` season gets the saveable `listState`;
            // the outgoing (or any other) child gets a throwaway state so they stop fighting over
            // it and the outgoing list stops jumping mid-fade.
            val childListState = if (seasonIdx == selected) listState else rememberLazyListState()
            LazyColumn(
                state = childListState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                // Fallback to the resume row (always attached in the selected season's list): a
                // failed restore lands on the episode you'd play next instead of eating the press.
                modifier = Modifier.fillMaxWidth().tvFocusRestorer { resumeFocus },
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
private fun SeasonPill(
    label: String,
    selected: Boolean,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
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
