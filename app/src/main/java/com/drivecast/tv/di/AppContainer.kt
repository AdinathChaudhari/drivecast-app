package com.drivecast.tv.di

import android.content.Context
import android.graphics.Bitmap
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
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

    // 1GB-RAM Fire TV Sticks: RGB_565 halves bitmap memory for posters (no alpha
    // channel needed), memory/disk caches are capped, and no Transformation is ever
    // added here (any Transformation silently forces ARGB_8888 back on).
    val imageLoader: ImageLoader = ImageLoader.Builder(context.applicationContext)
        .okHttpClient(okHttp)
        .bitmapConfig(Bitmap.Config.RGB_565)
        .allowRgb565(true)
        .crossfade(200)
        .memoryCache {
            MemoryCache.Builder(context.applicationContext)
                .maxSizePercent(0.20)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.applicationContext.cacheDir.resolve("img"))
                .maxSizeBytes(128L * 1024 * 1024)
                .build()
        }
        .respectCacheHeaders(false)
        .build()
}
