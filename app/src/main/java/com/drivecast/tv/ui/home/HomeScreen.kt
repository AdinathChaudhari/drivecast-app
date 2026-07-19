package com.drivecast.tv.ui.home

import android.view.KeyEvent
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.drivecast.tv.LocalAppContainer
import com.drivecast.tv.api.ContinueItem
import com.drivecast.tv.api.SectionInfo
import com.drivecast.tv.api.Title
import com.drivecast.tv.ui.common.PosterCard
import com.drivecast.tv.ui.common.SkeletonBox
import com.drivecast.tv.ui.theme.Accent
import com.drivecast.tv.ui.theme.Background
import com.drivecast.tv.ui.theme.ErrorRed
import com.drivecast.tv.ui.theme.OnAccent
import com.drivecast.tv.ui.theme.SurfaceVariant
import com.drivecast.tv.ui.theme.TextPrimary
import com.drivecast.tv.ui.theme.TextSecondary
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.drivecast.tv.ui.common.PositionFocusedItemInLazyLayout
import com.drivecast.tv.ui.common.tvFocusRestorer
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
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val pendingDismiss = remember { mutableStateOf<ContinueItem?>(null) }

    // TTFD: drawn once real content is on screen, not on the skeleton/cache-seeded first frame.
    ReportDrawnWhen { state.titles?.isNotEmpty() == true }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = Background),
    ) {
        Box(Modifier.fillMaxSize()) {
            when {
                state.titles == null && state.error != null ->
                    CenterMessage(state.error!!) { vm.refresh() }
                else -> Crossfade(
                    targetState = state.titles == null,
                    animationSpec = tween(300),
                    label = "homeLoading",
                ) { loading ->
                    if (loading) {
                        HomeSkeleton()
                    } else {
                        HomeContent(
                            continueItems = state.continueItems,
                            titles = state.titles.orEmpty(),
                            sectionInfos = state.sections,
                            refreshing = state.refreshing,
                            onOpenTitle = onOpenTitle,
                            onPlayContinue = { item ->
                                val titleId = item.titleId
                                if (titleId != null) onPlay(titleId, item.fileId)
                            },
                            onDismissRequest = { pendingDismiss.value = it },
                            onRefresh = { vm.refresh() },
                            posterUrl = { key -> container.repository.posterUrl(key) },
                        )
                    }
                }
            }

            // A MutableState reference is stable across recompositions; only DismissDialogHost
            // itself reads `.value`, so opening/closing the dialog never recomposes HomeScreen
            // (and therefore never recomposes HomeContent/the grid).
            DismissDialogHost(
                pendingDismiss = pendingDismiss,
                onConfirm = { item ->
                    pendingDismiss.value = null
                    vm.dismissContinueItem(item.fileId)
                },
                onCancel = { pendingDismiss.value = null },
            )
        }
    }
}

@Composable
private fun HomeContent(
    continueItems: List<ContinueItem>,
    titles: List<Title>,
    sectionInfos: List<SectionInfo>,
    refreshing: Boolean,
    onOpenTitle: (String) -> Unit,
    onPlayContinue: (ContinueItem) -> Unit,
    onDismissRequest: (ContinueItem) -> Unit,
    onRefresh: () -> Unit,
    posterUrl: (String?) -> String?,
) {
    // ONE stable (String) -> Unit reference handed to every grid item lambda below, instead of
    // each item closing over `onOpenTitle` + its own `title` fresh every recomposition.
    val currentOnOpenTitle by rememberUpdatedState(onOpenTitle)
    val onTitleClick = remember { { id: String -> currentOnOpenTitle(id) } }

    val bySection = remember(titles) { titles.groupBy { sectionKeyOf(it) } }
    val tabs = remember(titles, sectionInfos) { buildTabs(bySection, sectionInfos) }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabIndex = selectedTab.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    val section = tabs.getOrNull(tabIndex)
    val sectionKey = section?.key ?: ENTERTAINMENT
    // Branch on the server-declared behavior, not the key; fall back to the key
    // for legacy servers that predate the behaviors refactor.
    val isEntertainment = section?.behavior?.let { it == ENTERTAINMENT } ?: (sectionKey == ENTERTAINMENT)

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
    val gridTitles = remember(sectionTitles, selectedCat, isEntertainment) {
        if (isEntertainment) sectionTitles.filter { matchesCategory(it, selectedCat) } else sectionTitles
    }

    val sectionContinue = remember(continueItems, sectionKey) {
        continueItems.filter { sectionKeyOf(it) == sectionKey }
    }

    // One scroll+focus position per tab (fixes scroll carryover across tabs); rememberSaveable
    // survives process death and, combined with tvFocusRestorer below, restores both scroll
    // offset and the focused card on back-navigation.
    val gridState = rememberSaveable(selectedTab, saver = LazyGridState.Saver) { LazyGridState() }

    PositionFocusedItemInLazyLayout(0.10f) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("drivecast", style = MaterialTheme.typography.headlineMedium, color = Accent)
                    if (refreshing) RefreshIndicator()
                }
                Button(onClick = onRefresh) { Text("Refresh") }
            }

            if (tabs.size > 1) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp).tvFocusRestorer(),
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

            if (tabs.isEmpty()) {
                NoTabsMessage(Modifier.weight(1f))
                return@Column
            }

            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(120.dp),
                contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize().weight(1f).tvFocusRestorer(),
            ) {
                if (sectionContinue.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            ShelfHeader(section?.continueLabel?.ifBlank { null } ?: "Continue Watching")
                            Spacer(Modifier.height(8.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.tvFocusRestorer(),
                            ) {
                                items(sectionContinue, key = { it.fileId }, contentType = { "continue" }) { item ->
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
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.tvFocusRestorer(),
                        ) {
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

                items(gridTitles, key = { it.id }, contentType = { "poster" }) { title ->
                    LibraryTile(
                        title = title,
                        section = section,
                        isEntertainment = isEntertainment,
                        posterUrl = posterUrl(title.poster),
                        onOpenTitle = onTitleClick,
                    )
                }
            }
        }
    }
}

// ---- Section / category helpers ----

private fun sectionKeyOf(title: Title): String =
    (title.section ?: ENTERTAINMENT).ifBlank { ENTERTAINMENT }

private fun sectionKeyOf(item: ContinueItem): String =
    (item.section ?: ENTERTAINMENT).ifBlank { ENTERTAINMENT }

/** The tab set: one per section that has titles, in server order. May be empty. */
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
        if (bySection[key]?.isNotEmpty() == true) {
            result += info(key)
            seen += key
        }
    }

    sectionInfos.forEach { consider(it.key) }
    bySection.keys.forEach { consider(it) }
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
    onOpenTitle: (String) -> Unit,
) {
    Column(Modifier.width(120.dp)) {
        PosterCard(
            title = title.displayTitle,
            posterUrl = posterUrl,
            onClick = { onOpenTitle(title.id) },
            widthDp = 120.dp,
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
        widthDp = 112.dp,
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

/**
 * Owns nothing itself — [pendingDismiss] is hoisted in [HomeScreen] as a [MutableState] so its
 * setter can be threaded down into [ContinueCard]'s onDismiss. Passing the MutableState
 * *reference* down (instead of its `.value`) keeps HomeScreen's own recomposition scope from ever
 * subscribing to it: only this composable reads `.value`, so opening/closing the confirm dialog
 * recomposes just this tiny subtree, never the grid above it.
 */
@Composable
private fun DismissDialogHost(
    pendingDismiss: MutableState<ContinueItem?>,
    onConfirm: (ContinueItem) -> Unit,
    onCancel: () -> Unit,
) {
    val item = pendingDismiss.value ?: return
    DismissDialog(
        title = item.displayName,
        onConfirm = { onConfirm(item) },
        onCancel = onCancel,
    )
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

/**
 * True cold start only (no [AppContainer.homeCache] to seed from): mirrors [HomeContent]'s real
 * layout at its exact final dimensions so there is no layout jump when the first fetch lands.
 * Shimmer is capped at the top 2 poster rows (the Continue Watching shelf + the first grid row) —
 * an infinite animation competes with first-frame work on Stick GPUs, so everything below is
 * static per [SkeletonBox]'s own guidance.
 */
@Composable
private fun HomeSkeleton() {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkeletonBox(Modifier.width(150.dp).height(32.dp), animated = false)
        }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(3) {
                SkeletonBox(
                    modifier = Modifier.width(96.dp).height(32.dp),
                    shape = RoundedCornerShape(50),
                    animated = false,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Column(Modifier.weight(1f).padding(horizontal = 48.dp)) {
            // Continue Watching shelf — the 1st of the "top 2 rows" that shimmer.
            SkeletonBox(Modifier.width(180.dp).height(20.dp), animated = false)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(6) {
                    SkeletonBox(modifier = Modifier.width(120.dp).aspectRatio(2f / 3f), animated = true)
                }
            }

            Spacer(Modifier.height(16.dp))
            // Grid row 1 — the 2nd of the "top 2 rows" that shimmer.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(6) {
                    SkeletonBox(modifier = Modifier.width(120.dp).aspectRatio(2f / 3f), animated = true)
                }
            }

            Spacer(Modifier.height(16.dp))
            // Grid row 2 — below the fold, static.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(6) {
                    SkeletonBox(modifier = Modifier.width(120.dp).aspectRatio(2f / 3f), animated = false)
                }
            }
        }
    }
}

/** A small graphicsLayer-driven spin — no material3 dependency, no layout-affecting animation. */
@Composable
private fun RefreshIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "refreshSpin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing)),
        label = "refreshAngle",
    )
    Canvas(
        modifier = modifier
            .size(20.dp)
            .graphicsLayer { rotationZ = angle },
    ) {
        drawArc(
            color = Accent,
            startAngle = 0f,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
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

/** Shown when the server has zero tabs (a fresh install with no sections created yet). */
@Composable
private fun NoTabsMessage(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "No tabs yet — create one in drivecast on your Mac, then assign drives to it.",
            color = TextSecondary,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
