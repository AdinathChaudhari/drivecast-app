package com.drivecast.tv.data

import com.drivecast.tv.api.ContinueItem
import com.drivecast.tv.api.DrivecastApi
import com.drivecast.tv.api.LibraryResponse
import com.drivecast.tv.api.ProgressBody
import com.drivecast.tv.api.RemoteInfo
import com.drivecast.tv.api.Title
import com.drivecast.tv.api.WatchedMap
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit

/** One horizontal shelf on Home: a section name and the titles under it. */
data class SectionRow(val name: String, val titles: List<Title>)

/** A probed subtitle track: the streamable URL plus the MIME the player needs. */
data class SubtitleTrack(val url: String, val mimeType: String)

/**
 * The single source of truth for library data. Holds the active server URL, an
 * in-memory copy of the last library fetch, and all HTTP calls. The shared
 * OkHttpClient (with the TokenInterceptor) authorizes every request, so poster
 * and stream URLs are handed out plain — the token is added on the wire.
 */
class LibraryRepository(
    private val okHttp: OkHttpClient,
    private val json: Json,
    private val tokenHolder: TokenHolder,
) {
    private var baseUrl: String? = null
    private var api: DrivecastApi? = null

    @Volatile
    var lastLibrary: LibraryResponse? = null
        private set

    fun configure(baseUrl: String, token: String) {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        this.baseUrl = normalized
        tokenHolder.token = token
        api = Retrofit.Builder()
            .baseUrl(normalized)
            .client(okHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DrivecastApi::class.java)
    }

    private fun requireApi(): DrivecastApi =
        api ?: error("LibraryRepository not configured with a server yet")

    private fun requireBase(): String =
        baseUrl?.removeSuffix("/") ?: error("LibraryRepository not configured with a server yet")

    // ---- URLs handed to Coil / ExoPlayer (token added by the interceptor) ----

    fun posterUrl(key: String?): String? {
        val k = key?.ifBlank { null } ?: return null
        return "${requireBase()}/api/poster/$k"
    }

    fun streamUrl(fileId: String): String = "${requireBase()}/stream/$fileId"

    fun subtitleUrl(fileId: String): String = "${requireBase()}/api/subtitles/$fileId"

    // ---- reads ----

    /** Validate a pairing by hitting /api/remote. 200 = ok, 403 = remote off, 401 = bad token. */
    suspend fun validateRemote(): retrofit2.Response<RemoteInfo> = withContext(Dispatchers.IO) {
        requireApi().remote()
    }

    suspend fun refresh(): LibraryResponse = withContext(Dispatchers.IO) {
        val lib = requireApi().library()
        lastLibrary = lib
        lib
    }

    suspend fun continueWatching(): List<ContinueItem> = withContext(Dispatchers.IO) {
        requireApi().continueWatching().items
    }

    suspend fun watchedMap(): WatchedMap = withContext(Dispatchers.IO) {
        requireApi().watchedMap()
    }

    suspend fun title(id: String): Title? = withContext(Dispatchers.IO) {
        val resp = requireApi().title(id)
        if (resp.isSuccessful) resp.body() else null
    }

    /** Group the library's titles into ordered section shelves. */
    fun sectionsFrom(lib: LibraryResponse): List<SectionRow> {
        val order = mutableListOf<String>()
        val buckets = linkedMapOf<String, MutableList<Title>>()
        for (t in lib.titles) {
            val section = (t.section ?: "entertainment").ifBlank { "entertainment" }
            if (section !in buckets) {
                buckets[section] = mutableListOf()
                order.add(section)
            }
            buckets.getValue(section).add(t)
        }
        return order.map { SectionRow(prettySection(it), buckets.getValue(it)) }
    }

    // ---- writes ----

    suspend fun removeContinue(fileId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { requireApi().removeContinue(fileId).removed }.getOrDefault(false)
    }

    suspend fun postProgress(
        fileId: String,
        name: String?,
        position: Double,
        duration: Double?,
        ended: Boolean,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            requireApi().progress(
                ProgressBody(
                    fileId = fileId,
                    name = name,
                    position = position,
                    duration = duration,
                    ended = ended,
                )
            )
        }
        Unit
    }

    /**
     * HEAD-probe a file's subtitle track. Returns the track (URL + MIME derived
     * from Content-Type) when the server has one, or null on 404/any failure.
     * Never throws — playback must never block on subtitles.
     */
    suspend fun probeSubtitle(fileId: String): SubtitleTrack? = withContext(Dispatchers.IO) {
        val url = subtitleUrl(fileId)
        val request = Request.Builder().url(url).head().build()
        try {
            okHttp.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val contentType = resp.header("Content-Type")?.substringBefore(';')?.trim()
                SubtitleTrack(url = url, mimeType = mimeForSubtitle(contentType))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun mimeForSubtitle(contentType: String?): String = when (contentType) {
        "text/vtt" -> "text/vtt"
        "text/x-ssa", "application/x-ass", "text/x-ass" -> "text/x-ssa"
        else -> "application/x-subrip"
    }

    private fun prettySection(section: String): String = when (section) {
        "entertainment" -> "Movies & Shows"
        else -> section.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}

/** Holds the live access token so the shared interceptor can read it after re-pairing. */
class TokenHolder {
    @Volatile
    var token: String? = null
}
