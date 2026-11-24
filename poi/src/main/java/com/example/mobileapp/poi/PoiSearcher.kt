package com.example.mobileapp.poi

import kotlin.math.roundToInt
import kotlin.random.Random

/** Something that can query POIs in a ring around a point; [OverpassPoiSource] in production. */
interface PoiSource {
    suspend fun findPois(
        latitude: Double,
        longitude: Double,
        rules: List<PoiTagRule>,
        outerRadiusMeters: Int,
        innerRadiusMeters: Int = 0,
        limit: Int? = null,
    ): PoiBatch
}

/**
 * Outcome of a POI search.
 *
 * @property pois the prepared candidates, best class first, then nearest first (to the start).
 * @property stage the stage in which the search stopped.
 * @property centerLatitude center of the search ring when the search stopped; differs from the
 *   start when the search was [recentered].
 * @property recentered the ring was moved to an already found POI during the search.
 * @property isComplete `true` when [PoiSearchParams.targetCount] POIs were found.
 * @property requestCount number of Overpass queries made.
 */
data class PoiSearchResult(
    val pois: List<Poi>,
    val params: PoiSearchParams,
    val stage: PoiSearchStage,
    val centerLatitude: Double,
    val centerLongitude: Double,
    val recentered: Boolean,
    val isComplete: Boolean,
    val requestCount: Int,
)

/**
 * Prepares candidate POIs for a round trip, following the staged search:
 *
 * - Stage 1 looks for A and B POIs, stage 2 for C, stage 3 for D.
 * - Every stage searches the same fixed ring between [PoiSearchParams.minRadiusKm] and
 *   [PoiSearchParams.maxRadiusKm] around the current center.
 * - The search stops as soon as [PoiSearchParams.targetCount] POIs are found. Everything from
 *   earlier stages is kept, and the rest is picked randomly from the current stage's POIs.
 * - When more than [RECENTER_MIN_POI_COUNT] POIs are requested, a search has a
 *   [RECENTER_CHANCE] chance to move the ring's center, once, to a random already found POI
 *   after a stage that didn't find enough; later stages search around that POI. This makes the
 *   candidate cluster lean in a random direction instead of always forming a ring around the start.
 */
class PoiSearcher(
    private val source: PoiSource,
    private val random: Random = Random.Default,
) {

    /** Called before each Overpass query, e.g. to show progress. */
    fun interface ProgressListener {
        fun onProgress(stage: PoiSearchStage, recentered: Boolean)
    }

    /** @throws PoiQueryException when a query fails. */
    suspend fun search(
        latitude: Double,
        longitude: Double,
        params: PoiSearchParams,
        progress: ProgressListener = ProgressListener { _, _ -> },
    ): PoiSearchResult {
        val target = params.targetCount
        val known = LinkedHashMap<String, Poi>() // all POIs from finished stages
        var requests = 0
        var centerLat = latitude
        var centerLon = longitude
        var recentered = false
        var willRecenter = params.poiCount > RECENTER_MIN_POI_COUNT && random.nextDouble() < RECENTER_CHANCE

        fun result(pois: List<Poi>, stage: PoiSearchStage, isComplete: Boolean) = PoiSearchResult(
            pois = pois.sortedForDisplay(latitude, longitude),
            params = params,
            stage = stage,
            centerLatitude = centerLat,
            centerLongitude = centerLon,
            recentered = recentered,
            isComplete = isComplete,
            requestCount = requests,
        )

        for (stage in PoiSearchStage.entries) {
            if (willRecenter && known.isNotEmpty()) {
                val newCenter = known.values.random(random)
                centerLat = newCenter.latitude
                centerLon = newCenter.longitude
                recentered = true
                willRecenter = false
            }
            progress.onProgress(stage, recentered)

            val needed = target - known.size
            val batch = source.findPois(
                centerLat, centerLon, stage.rules,
                outerRadiusMeters = (params.maxRadiusKm * 1000).roundToInt(),
                innerRadiusMeters = (params.minRadiusKm * 1000).roundToInt(),
                limit = (needed * OVERSAMPLING).coerceIn(MIN_QUERY_LIMIT, MAX_QUERY_LIMIT),
            )
            requests++
            val found = batch.pois.filter { it.id !in known }.distinctBy { it.id }

            if (known.size + found.size >= target) {
                val picked = known.values.toList() + found.shuffled(random).take(needed)
                return result(picked, stage, isComplete = true)
            }
            found.forEach { known[it.id] = it }
        }

        return result(known.values.toList(), PoiSearchStage.entries.last(), isComplete = false)
    }

    private fun List<Poi>.sortedForDisplay(latitude: Double, longitude: Double) =
        sortedWith(compareBy<Poi> { it.poiClass }.thenBy { it.distanceKmTo(latitude, longitude) })

    companion object {
        /** Recentering is possible only when more POIs than this are requested. */
        const val RECENTER_MIN_POI_COUNT = 3
        /** Chance per search that the ring's center moves to a found POI. */
        const val RECENTER_CHANCE = 1.0 / 3

        /** Ask for more than needed so the random pick has something to choose from. */
        private const val OVERSAMPLING = 3
        private const val MIN_QUERY_LIMIT = 30
        /** Keeps responses small for dense C/D tags (memorials, artworks, crosses…). */
        private const val MAX_QUERY_LIMIT = 300
    }
}
