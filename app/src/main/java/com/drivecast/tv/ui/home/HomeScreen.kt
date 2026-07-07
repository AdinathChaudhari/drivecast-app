package com.drivecast.tv.ui.home

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drivecast.tv.LocalAppContainer
import com.drivecast.tv.api.ContinueItem
import com.drivecast.tv.api.SectionInfo
import com.drivecast.tv.api.Title
import com.drivecast.tv.ui.common.PosterCard
import com.drivecast.tv.ui.theme.Accent
import com.drivecast.tv.ui.theme.Background
import com.drivecast.tv.ui.theme.ErrorRed
import com.drivecast.tv.ui.theme.OnAccent
import com.drivecast.tv.ui.theme.SurfaceVariant
import com.drivecast.tv.ui.theme.TextPrimary
import com.drivecast.tv.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import androidx.tv.foundation.lazy.grid.TvGridCells
import androidx.tv.foundation.lazy.grid.TvGridItemSpan
import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid
import androidx.tv.foundation.lazy.grid.items
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text

private const val ENTERTAINMENT = "entertainment"

@Composable
fun HomeScreen(
    onOpenTitle: (titleId: String) -> Unit,
    onPlay: (titleId: String, fileId: String) -> Unit,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var continueItems by remember { mutableStateOf<List<ContinueItem>>(emptyList()) }
    var titles by remember { mutableStateOf<List<Title>>(emptyList()) }
    var sectionInfos by remember { mutableStateOf<List<SectionInfo>>(emptyList()) }
    var pendingDismiss by remember { mutableStateOf<ContinueItem?>(null) }

    suspend fun load() {
        loading = true
        error = null
        runCatching {
            val lib = container.repository.refresh()
            titles = lib.titles
            sectionInfos = runCatching { container.repository.sections() }.getOrDefault(emptyList())
            continueItems = runCatching { container.repository.continueWatching() }.getOrDefault(emptyList())
        }.onFailure {
            error = "Couldn't load the library. ${it.message ?: ""}".trim()
        }
        loading = false
    }

    LaunchedEffect(Unit) { load() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = Background),
    ) {
        Box(Modifier.fillMaxSize()) {
            when {
                loading -> CenterProgress()
                error != null -> CenterMessage(error!!) { scope.launch { load() } }
                else -> HomeContent(
                    continueItems = continueItems,
                    titles = titles,
                    sectionInfos = sectionInfos,
                    onOpenTitle = onOpenTitle,
                    onPlayContinue = { item ->
                        val titleId = item.titleId
                        if (titleId != null) onPlay(titleId, item.fileId)
                    },
                    onDismissRequest = { pendingDismiss = it },
                    onRefresh = { scope.launch { load() } },
                    posterUrl = { key -> container.repository.posterUrl(key) },
                )
            }

            pendingDismiss?.let { item ->
                DismissDialog(
                    title = item.displayName,
                    onConfirm = {
                        pendingDismiss = null
                        scope.launch {
                            container.repository.removeContinue(item.fileId)
                            continueItems = runCatching { container.repository.continueWatching() }
                                .getOrDefault(emptyList())
                        }
                    },
                    onCancel = { pendingDismiss = null },
                )
            }
        }
    }
}

@Composable
private fun HomeContent(
    continueItems: List<ContinueItem>,
    titles: List<Title>,
    sectionInfos: List<SectionInfo>,
    onOpenTitle: (String) -> Unit,
    onPlayContinue: (ContinueItem) -> Unit,
    onDismissRequest: (ContinueItem) -> Unit,
    onRefresh: () -> Unit,
    posterUrl: (String?) -> String?,
) {
    val bySection = remember(titles) { titles.groupBy { sectionKeyOf(it) } }
    val tabs = remember(titles, sectionInfos) { buildTabs(bySection, sectionInfos) }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabIndex = selectedTab.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    val section = tabs.getOrNull(tabIndex)
    val sectionKey = section?.key ?: ENTERTAINMENT
    val isEntertainment = sectionKey == ENTERTAINMENT

    // Category filter (entertainment tab only). null == "All".
    var selectedCat by remember(tabIndex) { mutableStateOf<String?>(null) }

    val sectionTitles = remember(bySection, sectionKey) {
        (bySection[sectionKey] ?: emptyList()).sortedWith(
            compareByDescending<Title> { it.addedAt ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.displayTitle.lowercase() }
        )
    }
    val chips = remember(sectionTitles, isEntertainment) {
        if (isEntertainment) visibleChips(sectionTitles) else emptyList()
    }
    val gridTitles = if (isEntertainment) {
        sectionTitles.filter { matchesCategory(it, selectedCat) }
    } else {
        sectionTitles
    }

    val sectionContinue = continueItems.filter { sectionKeyOf(it) == sectionKey }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("drivecast", style = MaterialTheme.typography.headlineMedium, color = Accent)
            Button(onClick = onRefresh) { Text("Refresh") }
        }

        if (tabs.size > 1) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                tabs.forEachIndexed { index, tab ->
                    PillButton(
                        selected = index == tabIndex,
                        label = tabLabel(tab),
                        onClick = { selectedTab = index },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        TvLazyVerticalGrid(
            columns = TvGridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().weight(1f),
        ) {
            if (sectionContinue.isNotEmpty()) {
                item(span = { TvGridItemSpan(maxLineSpan) }) {
                    Column {
                        ShelfHeader(section?.continueLabel?.ifBlank { null } ?: "Continue Watching")
                        Spacer(Modifier.height(8.dp))
                        TvLazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(sectionContinue) { item ->
                                ContinueCard(
                                    item = item,
                                    posterUrl = posterUrl(item.poster),
                                    onClick = { onPlayContinue(item) },
                                    onDismiss = { onDismissRequest(item) },
                                )
                            }
                        }
                    }
                }
            }

            if (chips.size > 1) {
                item(span = { TvGridItemSpan(maxLineSpan) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        chips.forEach { chip ->
                            PillButton(
                                selected = selectedCat == chip.category,
                                label = chip.label,
                                onClick = { selectedCat = chip.category },
                            )
                        }
                    }
                }
            }

            items(gridTitles, key = { it.id }) { title ->
                LibraryTile(
                    title = title,
                    section = section,
                    isEntertainment = isEntertainment,
                    posterUrl = posterUrl(title.poster),
                    onClick = { onOpenTitle(title.id) },
                )
            }
        }
    }
}

// ---- Section / category helpers ----

private fun sectionKeyOf(title: Title): String =
    (title.section ?: ENTERTAINMENT).ifBlank { ENTERTAINMENT }

private fun sectionKeyOf(item: ContinueItem): String =
    (item.section ?: ENTERTAINMENT).ifBlank { ENTERTAINMENT }

/** The tab set: one per section that has titles (entertainment always shown), in server order. */
private fun buildTabs(
    bySection: Map<String, List<Title>>,
    sectionInfos: List<SectionInfo>,
): List<SectionInfo> {
    val infoByKey = sectionInfos.associateBy { it.key }
    fun info(key: String) = infoByKey[key] ?: SectionInfo(key = key, label = prettyKey(key))

    val result = mutableListOf<SectionInfo>()
    val seen = mutableSetOf<String>()

    fun consider(key: String) {
        if (key in seen) return
        if (key == ENTERTAINMENT || bySection[key]?.isNotEmpty() == true) {
            result += info(key)
            seen += key
        }
    }

    sectionInfos.forEach { consider(it.key) }
    bySection.keys.forEach { consider(it) }
    if (ENTERTAINMENT !in seen) result.add(0, info(ENTERTAINMENT))
    return result
}

private fun prettyKey(key: String): String =
    key.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

private fun tabLabel(tab: SectionInfo): String {
    val label = tab.label?.ifBlank { null } ?: prettyKey(tab.key)
    val icon = tab.icon?.ifBlank { null }
    return if (icon != null) "$icon $label" else label
}

private fun categoryOf(title: Title): String =
    title.category?.ifBlank { null } ?: if (title.isShow) "show" else "movie"

private data class CategoryChip(val label: String, val category: String?)

private val KNOWN_CATEGORIES = setOf("movie", "show", "documentary")

private fun matchesCategory(title: Title, selected: String?): Boolean = when (selected) {
    null -> true
    "other" -> categoryOf(title) !in KNOWN_CATEGORIES
    else -> categoryOf(title) == selected
}

/** Chips for the categories actually present; "All" always, "Other" only if any uncategorised. */
private fun visibleChips(titles: List<Title>): List<CategoryChip> {
    val present = titles.map { categoryOf(it) }.toSet()
    val hasOther = titles.any { categoryOf(it) !in KNOWN_CATEGORIES }
    return buildList {
        add(CategoryChip("All", null))
        if ("movie" in present) add(CategoryChip("Movies", "movie"))
        if ("show" in present) add(CategoryChip("TV Shows", "show"))
        if ("documentary" in present) add(CategoryChip("Documentaries", "documentary"))
        if (hasOther) add(CategoryChip("Other", "other"))
    }
}

private fun subtitleFor(title: Title, section: SectionInfo?, isEntertainment: Boolean): String {
    if (isEntertainment) {
        return if (title.isShow) plural(title.seasons.size, "season") else title.year?.toString().orEmpty()
    }
    val seasonWord = section?.season?.ifBlank { null } ?: "Season"
    val episodeWord = section?.episode?.ifBlank { null } ?: "Episode"
    val seasons = title.seasons.size
    val episodes = title.seasons.sumOf { it.episodes.size }
    return "${plural(seasons, seasonWord)} · ${plural(episodes, episodeWord)}"
}

private fun plural(n: Int, word: String): String = "$n $word" + if (n == 1) "" else "s"

// ---- Tiles & chrome ----

@Composable
private fun LibraryTile(
    title: Title,
    section: SectionInfo?,
    isEntertainment: Boolean,
    posterUrl: String?,
    onClick: () -> Unit,
) {
    Column(Modifier.width(160.dp)) {
        PosterCard(
            title = title.displayTitle,
            posterUrl = posterUrl,
            onClick = onClick,
            width = 160,
        ) {
            if (isEntertainment && title.isShow) {
                TileBadge("TV", Modifier.align(Alignment.TopStart).padding(6.dp))
            }
            title.quality?.ifBlank { null }?.let { quality ->
                TileBadge(quality, Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            title.displayTitle,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val subtitle = subtitleFor(title, section, isEntertainment)
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TileBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color(0xCC000000), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, color = TextPrimary, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PillButton(selected: Boolean, label: String, onClick: () -> Unit) {
    val colors = if (selected) {
        ButtonDefaults.colors(containerColor = Accent, contentColor = OnAccent)
    } else {
        ButtonDefaults.colors()
    }
    Button(onClick = onClick, colors = colors) { Text(label) }
}

@Composable
private fun ShelfHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
}

@Composable
private fun ContinueCard(
    item: ContinueItem,
    posterUrl: String?,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    PosterCard(
        title = item.displayName,
        posterUrl = posterUrl,
        onClick = onClick,
        modifier = Modifier.onKeyEvent { e ->
            val native = e.nativeKeyEvent
            when {
                native.keyCode == KeyEvent.KEYCODE_MENU && e.type == KeyEventType.KeyUp -> {
                    onDismiss(); true
                }
                native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER && native.isLongPress -> {
                    onDismiss(); true
                }
                else -> false
            }
        },
    ) {
        // Progress bar overlay at the bottom of the poster.
        val fraction = (item.percent / 100.0).coerceIn(0.0, 1.0).toFloat()
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(6.dp)
                .background(SurfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(Accent),
            )
        }
    }
}

@Composable
private fun DismissDialog(title: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            colors = SurfaceDefaults.colors(containerColor = SurfaceVariant),
            modifier = Modifier.width(480.dp),
        ) {
            Column(Modifier.padding(28.dp)) {
                Text(
                    "Remove from Continue Watching?",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Text(title, color = TextSecondary)
                Spacer(Modifier.height(24.dp))
                Row {
                    Button(onClick = onConfirm) { Text("Remove") }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = onCancel) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun CenterProgress() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CenterMessage(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = ErrorRed, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}
