package com.example.mobileapp.poi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Thrown when the Overpass API can't be reached or returns an error/unreadable response. */
class PoiQueryException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Finds POIs (see [PoiCategories]) around a point using the OpenStreetMap Overpass API.
 *
 * @param endpoint Overpass interpreter URL; the public instance is rate-limited, so avoid
 *   firing queries in quick succession.
 */
class OverpassPoiSource(
    private val endpoint: String = DEFAULT_ENDPOINT,
) {

    /**
     * Queries all POIs within [radiusMeters] of the given point, sorted by class (A first) and name.
     *
     * @throws PoiQueryException on network, HTTP or parsing errors.
     */
    suspend fun findPois(latitude: Double, longitude: Double, radiusMeters: Int): List<Poi> {
        val query = buildQuery(latitude, longitude, radiusMeters)
        val json = runInterruptible(Dispatchers.IO) { post(query) }
        return parseResponse(json)
    }

    private fun post(query: String): String {
        val connection = try {
            URL(endpoint).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw PoiQueryException("Can't connect to Overpass API", e)
        }
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")

            val body = "data=" + URLEncoder.encode(query, "UTF-8")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val reason = when (code) {
                    429 -> "too many requests, try again in a moment"
                    504 -> "server busy or query timed out"
                    else -> "HTTP $code"
                }
                throw PoiQueryException("Overpass API error: $reason")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: PoiQueryException) {
            throw e
        } catch (e: IOException) {
            throw PoiQueryException("Overpass API request failed", e)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://overpass-api.de/api/interpreter"
        private const val USER_AGENT = "MobileApp-RoutePlanner/1.0 (Android)"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 100_000
        /** Server-side limit; must stay below [READ_TIMEOUT_MS]. */
        private const val QUERY_TIMEOUT_S = 90

        private const val MIN_RADIUS_M = 2_000
        private const val MAX_RADIUS_M = 50_000

        /**
         * Search radius for a round trip of [routeLengthKm]. A loop of length L stays roughly within
         * L/π of its start (circle diameter), and roads add detours, so ~0.35·L covers it.
         * Capped at [MAX_RADIUS_M] to keep the query manageable.
         */
        fun searchRadiusMeters(routeLengthKm: Int): Int =
            (routeLengthKm * 1000 * 0.35).toInt().coerceIn(MIN_RADIUS_M, MAX_RADIUS_M)

        /** Overpass QL fetching every element that matches a [PoiCategories] rule. */
        fun buildQuery(latitude: Double, longitude: Double, radiusMeters: Int): String {
            val around = String.format(Locale.ROOT, "(around:%d,%.6f,%.6f)", radiusMeters, latitude, longitude)
            val (simple, conditional) = PoiCategories.rules.partition { it.requiredTag == null }

            val statements = simple.groupBy { it.key }.map { (key, rules) ->
                val values = rules.map { it.value }.distinct().joinToString("|")
                "  nwr[\"$key\"~\"^($values)$\"]$around;"
            } + conditional.map { rule ->
                val (extraKey, extraValue) = rule.requiredTag!!
                "  nwr[\"${rule.key}\"=\"${rule.value}\"][\"$extraKey\"=\"$extraValue\"]$around;"
            }
            return buildString {
                appendLine("[out:json][timeout:$QUERY_TIMEOUT_S];")
                appendLine("(")
                statements.forEach(::appendLine)
                appendLine(");")
                append("out center tags;")
            }
        }

        /** Converts an Overpass JSON response into classified POIs. */
        fun parseResponse(json: String): List<Poi> = try {
            val elements = JSONObject(json).getJSONArray("elements")
            (0 until elements.length())
                .mapNotNull { i -> parseElement(elements.getJSONObject(i)) }
                .sortedWith(compareBy<Poi> { it.poiClass }.thenBy(nullsLast()) { it.name })
        } catch (e: JSONException) {
            throw PoiQueryException("Unreadable Overpass API response", e)
        }

        private fun parseElement(element: JSONObject): Poi? {
            val tagsJson = element.optJSONObject("tags") ?: return null
            val tags = tagsJson.keys().asSequence().associateWith { tagsJson.getString(it) }
            val (rule, poiClass) = PoiCategories.classify(tags) ?: return null

            // Nodes carry lat/lon directly; ways and relations get a "center" from `out center`.
            val position = if (element.has("lat")) element else element.optJSONObject("center") ?: return null
            return Poi(
                id = "${element.getString("type")}/${element.getLong("id")}",
                name = tags["name"],
                latitude = position.getDouble("lat"),
                longitude = position.getDouble("lon"),
                poiClass = poiClass,
                category = rule.tag,
                tags = tags,
            )
        }
    }
}
