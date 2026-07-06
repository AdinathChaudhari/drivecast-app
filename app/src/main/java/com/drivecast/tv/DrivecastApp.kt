package com.drivecast.tv

import android.app.Application
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
}

/** Provides the [AppContainer] to the Compose tree. */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}
