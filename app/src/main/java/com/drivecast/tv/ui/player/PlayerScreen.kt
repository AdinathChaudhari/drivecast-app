package com.drivecast.tv.ui.player

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.drivecast.tv.LocalAppContainer
import com.drivecast.tv.ui.theme.TextPrimary
import com.drivecast.tv.ui.theme.TextSecondary
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text

private const val VLC_PACKAGE = "org.videolan.vlc"

private fun isVlcInstalled(context: android.content.Context): Boolean =
    try {
        context.packageManager.getPackageInfo(VLC_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

/**
 * Playback entry point. Hands off to VLC when it's installed (better codec
 * coverage than this device's hardware decoder); otherwise, or if the hand-off
 * turns out to be impossible, falls back to the internal ExoPlayer.
 */
@UnstableApi
@Composable
fun PlayerScreen(
    titleId: String,
    fileId: String,
    startOver: Boolean,
    shuffle: Boolean,
    seed: Long,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    var useVlc by remember { mutableStateOf(isVlcInstalled(context)) }

    if (useVlc) {
        VlcPlayerHost(
            titleId = titleId,
            fileId = fileId,
            startOver = startOver,
            shuffle = shuffle,
            seed = seed,
            onExit = onExit,
            onVlcUnavailable = { useVlc = false },
        )
    } else {
        InternalPlayerScreen(titleId, fileId, startOver, shuffle, seed, onExit)
    }
}

// ---- External (VLC) hand-off ----

@Composable
private fun VlcPlayerHost(
    titleId: String,
    fileId: String,
    startOver: Boolean,
    shuffle: Boolean,
    seed: Long,
    onExit: () -> Unit,
    onVlcUnavailable: () -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: ExternalPlayerViewModel = viewModel(
        factory = ExternalPlayerViewModel.factory(container, titleId, fileId, startOver, shuffle, seed)
    )
    val state by vm.ui.collectAsStateWithLifecycle()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val position = data?.getLongExtra("extra_position", -1L) ?: -1L
        val duration = data?.getLongExtra("extra_duration", -1L) ?: -1L
        vm.onVlcResult(position, duration)
    }

    LaunchedEffect(Unit) {
        vm.launches.collect { req ->
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setPackage(VLC_PACKAGE)
                setDataAndType(Uri.parse(req.uri), "video/*")
                putExtra("title", req.title)
                putExtra("from_start", req.fromStart)
                if (!req.fromStart && req.positionMs > 0) putExtra("position", req.positionMs)
                req.subtitleUrl?.let { putExtra("subtitles_location", it) }
            }
            try {
                launcher.launch(intent)
                vm.onVlcLaunched()
            } catch (_: ActivityNotFoundException) {
                // VLC vanished between the install check and the launch. Fall
                // back to the internal player if nothing has played yet.
                if (!state.playing) onVlcUnavailable() else vm.onLaunchFailed()
            }
        }
    }

    LaunchedEffect(state.finished) {
        if (state.finished) onExit()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = Color.Black),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val upNext = state.upNext
            when {
                state.error != null -> ExternalMessage(state.error!!, onExit)
                upNext != null -> UpNextExternal(
                    title = upNext.title,
                    secondsLeft = upNext.secondsLeft,
                    onPlayNow = { vm.playNextNow() },
                    onCancel = { vm.cancelUpNext() },
                )
                else -> PlayingInVlc(state.nowPlayingTitle)
            }
        }
    }
}

@Composable
private fun PlayingInVlc(title: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Text("Playing in VLC…", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        title?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun UpNextExternal(
    title: String,
    secondsLeft: Int,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Up next in ${secondsLeft}s", color = TextSecondary, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Text(title, color = TextPrimary, style = MaterialTheme.typography.titleLarge, maxLines = 2)
        Spacer(Modifier.height(20.dp))
        Row {
            Button(onClick = onPlayNow) { Text("Play now") }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun ExternalMessage(message: String, onExit: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onExit) { Text("Back") }
    }
}

// ---- Internal (ExoPlayer) player ----

@UnstableApi
@Composable
private fun InternalPlayerScreen(
    titleId: String,
    fileId: String,
    startOver: Boolean,
    shuffle: Boolean,
    seed: Long,
    onExit: () -> Unit,
) {
    val container = LocalAppContainer.current
    val app = LocalContext.current.applicationContext as Application
    val vm: PlayerViewModel = viewModel(
        factory = PlayerViewModel.factory(app, container, titleId, fileId, startOver, shuffle, seed)
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
