package com.drivecast.tv.ui.player

import android.app.Application
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.drivecast.tv.LocalAppContainer
import com.drivecast.tv.ui.theme.TextPrimary
import com.drivecast.tv.ui.theme.TextSecondary
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text

@UnstableApi
@Composable
fun PlayerScreen(
    titleId: String,
    fileId: String,
    startOver: Boolean,
    onExit: () -> Unit,
) {
    val container = LocalAppContainer.current
    val app = LocalContext.current.applicationContext as Application
    val vm: PlayerViewModel = viewModel(
        factory = PlayerViewModel.factory(app, container, titleId, fileId, startOver)
    )
    val state by vm.ui.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        onDispose { vm.reportStop() }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = Color.Black),
    ) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = vm.player
                        useController = true
                        keepScreenOn = true
                        setShowSubtitleButton(true)
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            state.error?.let { message ->
                ErrorOverlay(
                    message = message,
                    retriable = state.errorRetriable,
                    onRetry = { vm.retry() },
                    onExit = onExit,
                )
            }

            state.upNext?.let { upNext ->
                UpNextOverlay(
                    title = upNext.title,
                    secondsLeft = upNext.secondsLeft,
                    onPlayNow = { vm.playNextNow() },
                    onCancel = { vm.cancelUpNext() },
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }
    }
}

@Composable
private fun ErrorOverlay(
    message: String,
    retriable: Boolean,
    onRetry: () -> Unit,
    onExit: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(20.dp))
            Row {
                if (retriable) {
                    Button(onClick = onRetry) { Text("Retry") }
                    Spacer(Modifier.width(12.dp))
                }
                Button(onClick = onExit) { Text("Back") }
            }
        }
    }
}

@Composable
private fun UpNextOverlay(
    title: String,
    secondsLeft: Int,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(48.dp).width(360.dp),
        colors = SurfaceDefaults.colors(containerColor = Color(0xEE171B22)),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Up next in ${secondsLeft}s", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                title,
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
            )
            Spacer(Modifier.height(16.dp))
            Row {
                Button(onClick = onPlayNow) { Text("Play now") }
                Spacer(Modifier.width(12.dp))
                Button(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}
