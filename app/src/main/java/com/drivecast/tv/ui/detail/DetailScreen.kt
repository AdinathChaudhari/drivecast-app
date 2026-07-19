package com.drivecast.tv.ui.detail

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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drivecast.tv.LocalAppContainer
import com.drivecast.tv.api.Episode
import com.drivecast.tv.api.SectionInfo
import com.drivecast.tv.api.Title
import com.drivecast.tv.api.WatchedProgress
import com.drivecast.tv.ui.common.PosterCard
import com.drivecast.tv.ui.theme.Accent
import com.drivecast.tv.ui.theme.Background
import com.drivecast.tv.ui.theme.ErrorRed
import com.drivecast.tv.ui.theme.OnAccent
import com.drivecast.tv.ui.theme.SurfaceVariant
import com.drivecast.tv.ui.theme.TextPrimary
import com.drivecast.tv.ui.theme.TextSecondary
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.drivecast.tv.ui.common.tvFocusRestorer
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import kotlin.random.Random

@Composable
fun DetailScreen(
    titleId: String,
    onPlay: (titleId: String, fileId: String, startOver: Boolean, shuffle: Boolean, seed: Long) -> Unit,
) {
    val container = LocalAppContainer.current
    var loading by remember { mutableStateOf(true) }
    var title by remember { mutableStateOf<Title?>(null) }
    var progress by remember { mutableStateOf<Map<String, WatchedProgress>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(titleId) {
        loading = true
        error = null
        runCatching {
            title = container.repository.title(titleId)
            progress = runCatching { container.repository.watchedMap().progress }.getOrDefault(emptyMap())
        }.onFailure { error = it.message }
        if (title == null && error == null) error = "Title not found."
        loading = false
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = Background),
    ) {
        when {
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            error != null || title == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(error ?: "Title not found.", color = ErrorRed)
            }
            else -> DetailContent(title!!, progress, container.repository::posterUrl,
                container.repository.lastSections, onPlay)
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
            width = 220,
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
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Button(onClick = { onPlay(title.id, fileId, false, false, 0L) }) { Text("Play") }
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
            items(group.episodes) { episode ->
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
    val current = seasons[selected.coerceIn(0, seasons.lastIndex)]
    // A course tab renders "Module N" / "Lesson N"; default is Season/Episode.
    val seasonWord = vocab?.season ?: "Season"
    val listState = rememberLazyListState()

    LaunchedEffect(selected) { listState.scrollToItem(0) }

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
                        onFocused = { selected = index },
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().weight(1f).tvFocusRestorer(),
        ) {
            items(current.episodes) { episode ->
                EpisodeRow(
                    episode = episode,
                    watched = episode.fileId?.let { progress[it]?.watched } ?: false,
                    percent = episode.fileId?.let { progress[it]?.percent } ?: 0.0,
                    episodeWord = vocab?.episode ?: "Episode",
                    onClick = { episode.fileId?.let { onPlay(title.id, it, false, false, 0L) } },
                )
            }
        }
    }
}

@Composable
private fun SeasonPill(label: String, selected: Boolean, onFocused: () -> Unit) {
    val colors = if (selected) {
        ButtonDefaults.colors(containerColor = Accent, contentColor = OnAccent)
    } else {
        ButtonDefaults.colors()
    }
    Button(
        onClick = onFocused,
        colors = colors,
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
) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
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
