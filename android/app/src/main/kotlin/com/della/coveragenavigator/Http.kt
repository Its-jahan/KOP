// Network adapters: OSM tiles (MapLibre), Overpass road graph, Nominatim geocoding.
// All requests carry a real User-Agent identifying this app (OSMF usage policies).
package com.della.coveragenavigator

import della.core.FailureInput
import della.core.GeographicExtent
import della.core.overpassQuery
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Locale
import java.util.concurrent.TimeUnit

object Net {
    val userAgent: String =
        "DellaCoverageNavigator/${BuildConfig.VERSION_NAME} (Android; ${BuildConfig.APPLICATION_ID}; personal coverage navigation test)"

    /** Shared client; MapLibre tile requests use it too (see NavigatorApp). */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
            }
            .build()
    }
}

val OVERPASS_ENDPOINTS = listOf(
    "https://overpass-api.de/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
)

class OverpassResult(val body: String, val endpoint: String, val attempts: List<String>)

class OverpassException(val failure: FailureInput, val attempts: List<String>, message: String) : Exception(message)

object Overpass {
    /** Expansion of the zone bbox so legal approaches/exits just outside the zone are present. */
    const val BBOX_EXPAND_M = 600.0

    /**
     * Try each endpoint in order with retries; Overpass instances often answer 429/504.
     * A 200 response carrying a `remark` (runtime error / timeout) is NOT accepted as data,
     * because it may be silently truncated.
     */
    fun fetch(bbox: GeographicExtent, onProgress: (String) -> Unit, isCancelled: () -> Boolean): OverpassResult {
        val query = overpassQuery(bbox)
        val attempts = ArrayList<String>()
        var lastFailure: FailureInput = FailureInput.GraphNetworkError
        for (round in 0 until 2) {
            for (ep in OVERPASS_ENDPOINTS) {
                if (isCancelled()) throw OverpassException(FailureInput.GraphTimeout, attempts, "cancelled")
                onProgress("${hostOf(ep)} (${round + 1}/2)")
                try {
                    val req = Request.Builder().url(ep).post(FormBody.Builder().add("data", query).build()).build()
                    Net.client.newCall(req).execute().use { resp ->
                        val code = resp.code
                        if (code != 200) {
                            attempts.add("${hostOf(ep)}: HTTP $code")
                            lastFailure = FailureInput.GraphHttp(code)
                            return@use
                        }
                        val body = resp.body?.string() ?: ""
                        val remark = remarkOf(body)
                        if (remark != null) {
                            attempts.add("${hostOf(ep)}: remark: ${remark.take(120)}")
                            lastFailure = FailureInput.GraphTimeout
                            return@use
                        }
                        attempts.add("${hostOf(ep)}: OK ${body.length} bytes")
                        return OverpassResult(body, ep, attempts)
                    }
                } catch (e: InterruptedIOException) {
                    attempts.add("${hostOf(ep)}: timeout")
                    lastFailure = FailureInput.GraphTimeout
                } catch (e: IOException) {
                    attempts.add("${hostOf(ep)}: ${e.javaClass.simpleName}")
                    lastFailure = FailureInput.GraphNetworkError
                }
                try {
                    Thread.sleep(if (round == 0) 1500L else 4000L)
                } catch (e: InterruptedException) {
                    throw OverpassException(FailureInput.GraphTimeout, attempts, "interrupted")
                }
            }
        }
        throw OverpassException(lastFailure, attempts, attempts.joinToString("; "))
    }

    private fun remarkOf(body: String): String? {
        // Cheap check before parsing: remarks are top-level keys near the end of the document.
        val i = body.lastIndexOf("\"remark\"")
        if (i < 0) return null
        return try {
            val o = della.core.ContractJson.parse(body) as? JsonObject
            (o?.get("remark") as? JsonPrimitive)?.content
        } catch (e: Exception) {
            "unparseable response"
        }
    }

    fun hostOf(url: String): String = try {
        url.toHttpUrl().host
    } catch (e: IllegalArgumentException) {
        url
    }
}

data class PlaceResult(val displayName: String, val lat: Double, val lon: Double, val south: Double?, val north: Double?, val west: Double?, val east: Double?)

object Nominatim {
    /** Explicit, user-submitted search only; result positions the map and never selects a zone. */
    fun search(query: String, language: String): List<PlaceResult> {
        val url = "https://nominatim.openstreetmap.org/search".toHttpUrl().newBuilder()
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("q", query)
            .addQueryParameter("limit", "8")
            .build()
        val req = Request.Builder().url(url).header("Accept-Language", "$language,en;q=0.7").get().build()
        Net.client.newCall(req).execute().use { resp ->
            if (resp.code != 200) throw IOException("HTTP ${resp.code}")
            val arr = della.core.ContractJson.parse(resp.body?.string() ?: "[]") as? JsonArray ?: return emptyList()
            return arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                fun d(k: String) = (o[k] as? JsonPrimitive)?.content?.toDoubleOrNull()
                val bb = (o["boundingbox"] as? JsonArray)?.map { (it as? JsonPrimitive)?.content?.toDoubleOrNull() ?: (it as? JsonPrimitive)?.doubleOrNull }
                val lat = d("lat") ?: return@mapNotNull null
                val lon = d("lon") ?: return@mapNotNull null
                PlaceResult(
                    displayName = (o["display_name"] as? JsonPrimitive)?.content ?: String.format(Locale.ROOT, "%.5f, %.5f", lat, lon),
                    lat = lat, lon = lon,
                    south = bb?.getOrNull(0), north = bb?.getOrNull(1), west = bb?.getOrNull(2), east = bb?.getOrNull(3),
                )
            }
        }
    }
}
