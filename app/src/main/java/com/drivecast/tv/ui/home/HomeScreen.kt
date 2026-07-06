package com.drivecast.tv.ui.home

import android.view.KeyEvent
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.drivecast.tv.LocalAppContainer
import com.drivecast.tv.api.ContinueItem
import com.drivecast.tv.data.SectionRow
import com.drivecast.tv.ui.common.PosterCard
import com.drivecast.tv.ui.theme.Accent
import com.drivecast.tv.ui.theme.Background
import com.drivecast.tv.ui.theme.ErrorRed
import com.drivecast.tv.ui.theme.SurfaceVariant
import com.drivecast.tv.ui.theme.TextPrimary
import com.drivecast.tv.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text

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
    var sections by remember { mutableStateOf<List<SectionRow>>(emptyList()) }
    var pendingDismiss by remember { mutableStateOf<ContinueItem?>(null) }

    suspend fun load() {
        loading = true
        error = null
        runCatching {
            val lib = container.repository.refresh()
            sections = container.repository.sectionsFrom(lib)
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
                    sections = sections,
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
    sections: List<SectionRow>,
    onOpenTitle: (String) -> Unit,
    onPlayContinue: (ContinueItem) -> Unit,
    onDismissRequest: (ContinueItem) -> Unit,
    onRefresh: () -> Unit,
    posterUrl: (String?) -> String?,
) {
    TvLazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 48.dp, end = 48.dp, top = 32.dp, bottom = 48.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(28.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("drivecast", style = MaterialTheme.typography.headlineMedium, color = Accent)
                Button(onClick = onRefresh) { Text("Refresh") }
            }
        }

        if (continueItems.isNotEmpty()) {
            item {
                ShelfHeader("Continue Watching")
                Spacer(Modifier.height(8.dp))
                TvLazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(continueItems) { item ->
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

        items(sections) { section ->
            Column {
                ShelfHeader(section.name)
                Spacer(Modifier.height(8.dp))
                TvLazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(section.titles) { title ->
                        PosterCard(
                            title = title.displayTitle,
                            posterUrl = posterUrl(title.poster),
                            onClick = { onOpenTitle(title.id) },
                        )
                    }
                }
            }
        }
    }
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
            .background(androidx.compose.ui.graphics.Color(0xCC000000)),
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
