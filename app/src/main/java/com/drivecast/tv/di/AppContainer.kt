package com.drivecast.tv.di

import android.content.Context
import coil.ImageLoader
import com.drivecast.tv.api.TokenInterceptor
import com.drivecast.tv.data.Discovery
import com.drivecast.tv.data.KeepAwakeController
import com.drivecast.tv.data.LibraryRepository
import com.drivecast.tv.data.ServerConfigStore
import com.drivecast.tv.data.TokenHolder
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container, built once by [com.drivecast.tv.DrivecastApp] and
 * reached from the UI via a CompositionLocal. One shared OkHttpClient (with the
 * token interceptor) backs the API, the Coil poster loader and the ExoPlayer
 * stream source, so a single token authorizes everything.
 */
class AppContainer(context: Context) {

    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val tokenHolder = TokenHolder()

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(TokenInterceptor { tokenHolder.token })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val configStore = ServerConfigStore(context.applicationContext)

    val discovery = Discovery()

    val repository = LibraryRepository(okHttp, json, tokenHolder)

    val keepAwake = KeepAwakeController(repository)

    val imageLoader: ImageLoader = ImageLoader.Builder(context.applicationContext)
        .okHttpClient(okHttp)
        .crossfade(true)
        .build()
}
