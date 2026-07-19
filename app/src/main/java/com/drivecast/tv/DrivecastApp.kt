package com.drivecast.tv

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.compose.runtime.staticCompositionLocalOf
import com.drivecast.tv.di.AppContainer

/** Application subclass: owns the manual [AppContainer] for the process lifetime. */
class DrivecastApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Fire OS kills memory-heavy backgrounded apps first; clearing the poster
        // memory cache when we're backgrounded preserves a fast warm start instead
        // of getting killed outright.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            container.imageLoader.memoryCache?.clear()
        }
    }
}

/** Provides the [AppContainer] to the Compose tree. */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}
