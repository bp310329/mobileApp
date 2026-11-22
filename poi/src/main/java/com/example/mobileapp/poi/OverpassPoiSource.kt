package com.example.mobileapp.poi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * Result of one Overpass query.
 *
 * @property limitReached the server stopped at the requested limit, so more POIs exist in the area.
 */
data class PoiBatch(val pois: List<Poi>, val limitReached: Boolean)

/**
 * Queries POIs (see [PoiCategories]) from the OpenStreetMap Overpass API.
 *
 * @param endpoint Overpass interpreter URL. The public instance is rate-limited; busy/rate-limit
 *   responses are retried a few times with increasing delays.
 */
class OverpassPoiSource(
    private val endpoint: String = DEFAULT_ENDPOINT,
) : PoiSource {

    /**
     * Finds elements matching [rules] in the ring between [innerRadiusMeters] (exclusive) and
     * [outerRadiusMeters] around the point. Returns at most [limit] elements (all when `null`);
     * which ones the server picks when limited is arbitrary (OSM id order), not nearest-first.
     *
     * @throws PoiQueryException on network, HTTP or parsing errors.
     */
    override suspend fun findPois(
        latitude: Double,
        longitude: Double,
        rules: List<PoiTagRule>,
        outerRadiusMeters: Int,
        innerRadiusMeters: Int,
        limit: Int?,
    ): PoiBatch {
        val query = buildQuery(latitude, longitude, rules, outerRadiusMeters, innerRadiusMeters, limit)
        var attempt = 0
        while (true) {
            try {
                val json = runInterruptible(Dispatchers.IO) { post(query) }
                return parseResponse(json, limit)
            } catch (e: ServerBusyException) {
                if (attempt >= RETRY_DELAYS_MS.size) throw PoiQueryException(e.message!!, e)
                delay(RETRY_DELAYS_MS[attempt++])
            }
        }
    }

    /** HTTP 429/502/503/504: server overloaded, no free slot or query timed out; worth retrying. */
    private class ServerBusyException(message: String) : IOException(message)

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

            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                429 -> throw ServerBusyException("Overpass API: too many requests, try again in a moment")
                502, 503, 504 -> throw ServerBusyException("Overpass API: server busy or query timed out")
                else -> throw PoiQueryException("Overpass API error: HTTP $code")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            if (e is PoiQueryException || e is ServerBusyException) throw e
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
        private val RETRY_DELAYS_MS = longArrayOf(3_000, 8_000, 15_000)

        /**
         * Overpass QL for elements matching [rules] within [outerRadiusMeters] but not within
         * [innerRadiusMeters], returning tags (name, description, …) and position (`center` for
         * ways/relations), at most [limit] elements.
         */
        fun buildQuery(
            latitude: Double,
            longitude: Double,
            rules: List<PoiTagRule>,
            outerRadiusMeters: Int,
            innerRadiusMeters: Int = 0,
            limit: Int? = null,
        ): String {
            require(rules.isNotEmpty()) { "No rules to query" }
            val point = String.format(Locale.ROOT, "%.6f,%.6f", latitude, longitude)
            val outer = "(around:$outerRadiusMeters,$point)"
            val (simple, conditional) = rules.partition { it.requiredTag == null }

            val statements = simple.groupBy { it.key }.map { (key, keyRules) ->
                val values = keyRules.map { it.value }.distinct().joinToString("|")
                "  nwr[\"$key\"~\"^($values)$\"]$outer;"
            } + conditional.map { rule ->
                val (extraKey, extraValue) = rule.requiredTag!!
                "  nwr[\"${rule.key}\"=\"${rule.value}\"][\"$extraKey\"=\"$extraValue\"]$outer;"
            }
            return buildString {
                appendLine("[out:json][timeout:$QUERY_TIMEOUT_S];")
                appendLine("(")
                statements.forEach(::appendLine)
                appendLine(")->.ring;")
                if (innerRadiusMeters > 0) {
                    // Cut out the inner disc: filter the found set only, cheap compared to a 2nd search.
                    appendLine("nwr.ring(around:$innerRadiusMeters,$point)->.inner;")
                    appendLine("(.ring; - .inner;)->.ring;")
                }
                append(".ring out center tags")
                if (limit != null) append(" $limit")
                append(";")
            }
        }

        /** Converts an Overpass JSON response into classified POIs. */
        fun parseResponse(json: String, limit: Int? = null): PoiBatch = try {
            val elements = JSONObject(json).getJSONArray("elements")
            val pois = (0 until elements.length()).mapNotNull { i -> parseElement(elements.getJSONObject(i)) }
            PoiBatch(pois, limitReached = limit != null && elements.length() >= limit)
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
                category = rule.tag,
                description = tags["description"] ?: tags["description:en"],
                latitude = position.getDouble("lat"),
                longitude = position.getDouble("lon"),
                poiClass = poiClass,
                tags = tags,
            )
        }
    }
}
