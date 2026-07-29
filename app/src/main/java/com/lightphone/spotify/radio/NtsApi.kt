package com.lightphone.spotify.radio

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * What is playing on NTS right now.
 *
 * Two unrelated sources, which is NTS's design rather than a choice made here:
 *
 *  - **Live channels** come from the public `api/v2/live`, which returns both channels with the show
 *    on air and its artwork.
 *  - **Mixtapes** have no REST endpoint. Their current track title lives in the Firestore database
 *    behind the NTS iOS app, queried with the app's own API key. That key is shipped in a public
 *    client, so it is not a secret being leaked here, but it is also not a documented API and can stop
 *    working without notice — every failure path below degrades to "no title" rather than an error.
 *
 * Endpoints and the Firestore query shape are from
 * [vandamd/nts-radio](https://github.com/vandamd/nts-radio).
 */
class NtsApi {

    data class NowPlaying(
        val title: String,
        val artworkUrl: String? = null,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Current show per live channel, keyed 1 and 2. Empty on any failure. */
    suspend fun liveNowPlaying(): Map<Int, NowPlaying> = withContext(Dispatchers.IO) {
        // NTS caches aggressively; the minute bucket is what their own client uses to defeat it.
        val bucket = System.currentTimeMillis() / 60_000
        val request = Request.Builder().url("$API/live?cache=$bucket").build()
        val body = runCatching {
            http.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return@withContext emptyMap()

        runCatching {
            val results = JSONObject(body).optJSONArray("results") ?: return@runCatching emptyMap()
            buildMap {
                for (i in 0 until results.length()) {
                    val result = results.optJSONObject(i) ?: continue
                    val channel = result.optString("channel_name").toIntOrNull() ?: continue
                    val now = result.optJSONObject("now") ?: continue
                    val title = now.optString("broadcast_title").takeIf { it.isNotBlank() } ?: continue
                    put(
                        channel,
                        NowPlaying(
                            title = decodeEntities(title),
                            artworkUrl = now.optJSONObject("embeds")
                                ?.optJSONObject("details")
                                ?.optJSONObject("media")
                                ?.let {
                                    it.optString("picture_medium_large").takeIf { s -> s.isNotBlank() }
                                        ?: it.optString("picture_large").takeIf { s -> s.isNotBlank() }
                                },
                        ),
                    )
                }
            }
        }.getOrElse {
            Log.w(TAG, "live metadata parse failed", it)
            emptyMap()
        }
    }

    /** Current title for a mixtape, or null when Firestore has nothing (or has changed shape). */
    suspend fun mixtapeNowPlaying(alias: String): NowPlaying? = withContext(Dispatchers.IO) {
        val query = """
            {"structuredQuery":{
              "from":[{"collectionId":"mixtape_titles"}],
              "limit":1,
              "orderBy":[{"direction":"DESCENDING","field":{"fieldPath":"started_at"}}],
              "where":{"fieldFilter":{
                "field":{"fieldPath":"mixtape_alias"},
                "op":"EQUAL",
                "value":{"stringValue":"$alias"}}}}}
        """.trimIndent()
        val request = Request.Builder()
            .url(FIRESTORE)
            .post(query.toRequestBody(JSON))
            .build()
        val body = runCatching {
            http.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return@withContext null

        runCatching {
            // Firestore runQuery returns an array of {document:{fields:{...}}} wrappers.
            val array = org.json.JSONArray(body)
            val fields = array.optJSONObject(0)?.optJSONObject("document")?.optJSONObject("fields")
            fields?.optJSONObject("title")?.optString("stringValue")
                ?.takeIf { it.isNotBlank() }
                ?.let { NowPlaying(title = it) }
        }.getOrElse {
            Log.w(TAG, "mixtape metadata parse failed", it)
            null
        }
    }

    /**
     * NTS returns show titles HTML-escaped. Only the handful of entities that actually appear are
     * handled; anything else is left alone rather than mangled by a half-guess.
     */
    private fun decodeEntities(value: String): String = value
        .replace("&amp;", "&")
        .replace("&apos;", "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("&#(\\d+);")) { m ->
            m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
        }

    private companion object {
        const val TAG = "NtsApi"
        const val API = "https://www.nts.live/api/v2"

        /** From the NTS iOS app, via vandamd/nts-radio. Public client key, not a secret. */
        const val FIRESTORE_KEY = "AIzaSyA4Qp5AvHC8Rev72-10-_DY614w_bxUCJU"
        const val FIRESTORE =
            "https://firestore.googleapis.com/v1/projects/nts-ios-app/databases/(default)/" +
                "documents:runQuery?key=$FIRESTORE_KEY"

        val JSON = "application/json".toMediaType()
    }
}
