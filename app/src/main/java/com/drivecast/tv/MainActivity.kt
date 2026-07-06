package com.drivecast.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.drivecast.tv.ui.awake.KeepAwakeHost
import com.drivecast.tv.ui.detail.DetailScreen
import com.drivecast.tv.ui.home.HomeScreen
import com.drivecast.tv.ui.player.PlayerScreen
import com.drivecast.tv.ui.setup.SetupScreen
import com.drivecast.tv.ui.theme.DrivecastTheme
import kotlinx.coroutines.flow.first
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.Surface

class MainActivity : ComponentActivity() {
    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as DrivecastApp).container
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                DrivecastTheme {
                    DrivecastRoot()
                }
            }
        }
    }
}

@UnstableApi
@Composable
private fun DrivecastRoot() {
    val navController = rememberNavController()
    Box(Modifier.fillMaxSize()) {
        DrivecastNav(navController)
        // Global "Are you still watching?" overlay, above every screen.
        KeepAwakeHost(
            onRequestExitPlayer = {
                val route = navController.currentDestination?.route
                if (route != null && route.startsWith("player/")) {
                    navController.popBackStack()
                }
            },
        )
    }
}

@UnstableApi
@Composable
private fun DrivecastNav(navController: NavHostController) {
    val container = LocalAppContainer.current
    var startDest by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val cfg = container.configStore.config.first()
        startDest = if (cfg.isConfigured) {
            container.repository.configure(cfg.baseUrl!!, cfg.token!!)
            "home"
        } else {
            "setup"
        }
    }

    val start = startDest
    if (start == null) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        }
        return
    }

    NavHost(navController = navController, startDestination = start) {
        composable("setup") {
            SetupScreen(onPaired = {
                navController.navigate("home") {
                    popUpTo("setup") { inclusive = true }
                }
            })
        }
        composable("home") {
            HomeScreen(
                onOpenTitle = { titleId -> navController.navigate("detail/$titleId") },
                onPlay = { titleId, fileId -> navController.navigate("player/$titleId/$fileId/false") },
            )
        }
        composable(
            route = "detail/{titleId}",
            arguments = listOf(navArgument("titleId") { type = NavType.StringType }),
        ) { entry ->
            val titleId = entry.arguments?.getString("titleId").orEmpty()
            DetailScreen(
                titleId = titleId,
                onPlay = { t, f, over -> navController.navigate("player/$t/$f/$over") },
            )
        }
        composable(
            route = "player/{titleId}/{fileId}/{startOver}",
            arguments = listOf(
                navArgument("titleId") { type = NavType.StringType },
                navArgument("fileId") { type = NavType.StringType },
                navArgument("startOver") { type = NavType.BoolType },
            ),
        ) { entry ->
            val titleId = entry.arguments?.getString("titleId").orEmpty()
            val fileId = entry.arguments?.getString("fileId").orEmpty()
            val startOver = entry.arguments?.getBoolean("startOver") ?: false
            PlayerScreen(
                titleId = titleId,
                fileId = fileId,
                startOver = startOver,
                onExit = { navController.popBackStack() },
            )
        }
    }
}
