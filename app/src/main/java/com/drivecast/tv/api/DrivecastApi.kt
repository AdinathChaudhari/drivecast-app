package com.drivecast.tv.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit interface for the drivecast JSON endpoints. Discovery (/api/ping) and
 * the subtitle HEAD probe are done with raw OkHttp elsewhere because they need
 * short timeouts / raw response headers.
 */
interface DrivecastApi {

    /** Validation target during setup. 403 -> remote disabled, 401 -> bad token. */
    @GET("api/remote")
    suspend fun remote(): Response<RemoteInfo>

    @GET("api/library")
    suspend fun library(): LibraryResponse

    @GET("api/title/{id}")
    suspend fun title(@Path("id") id: String): Response<Title>

    @GET("api/continue")
    suspend fun continueWatching(): ContinueResponse

    @DELETE("api/continue/{fileId}")
    suspend fun removeContinue(@Path("fileId") fileId: String): RemoveResponse

    @GET("api/watched-map")
    suspend fun watchedMap(): WatchedMap

    @POST("api/progress")
    suspend fun progress(@Body body: ProgressBody): OkResponse
}
