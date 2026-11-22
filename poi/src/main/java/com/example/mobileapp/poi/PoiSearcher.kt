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
 * @property pois the prepared candidates, best class first, then nearest first.
 * @property stage the stage in which the search stopped.
 * @property radiusKm the search radius at which it stopped.
 * @property isComplete `true` when [PoiSearchParams.targetCount] POIs were found.
 * @property requestCount number of Overpass queries made.
 */
data class PoiSearchResult(
    val pois: List<Poi>,
    val params: PoiSearchParams,
    val stage: PoiSearchStage,
    val radiusKm: Double,
    val isComplete: Boolean,
    val requestCount: Int,
)

/**
 * Prepares candidate POIs for a round trip, following the staged search:
 *
 * - Stage 1 looks for A and B POIs, stage 2 adds C, stage 3 adds D, stage 4 adds villages.
 * - In every stage the radius restarts at the starting radius and grows ×1.5 up to the maximum;
 *   only the ring between the minimum radius and the current radius is searched.
 * - The search stops as soon as [PoiSearchParams.targetCount] POIs are found. Everything from
 *   earlier stages within the radius is kept, and the rest is picked randomly from the current
 *   stage's POIs (in stage 1 all are picked randomly).
 *
 * To keep Overpass queries small, each radius step only queries the *new* ring (previous radius →
 * current radius) for the *current* stage's tags, with an output limit. A finished stage reached the
 * maximum radius without hitting a limit, so its POIs are a complete set that later stages reuse
 * locally instead of querying again.
 */
class PoiSearcher(
    private val source: PoiSource,
    private val random: Random = Random.Default,
) {

    /** Called before each Overpass query, e.g. to show progress. */
    fun interface ProgressListener {
        fun onProgress(stage: PoiSearchStage, radiusKm: Double)
    }

    /** @throws PoiQueryException when a query fails. */
    suspend fun search(
        latitude: Double,
        longitude: Double,
        params: PoiSearchParams,
        progress: ProgressListener = ProgressListener { _, _ -> },
    ): PoiSearchResult {
        val target = params.targetCount
        val known = LinkedHashMap<String, Poi>() // complete sets from finished stages
        var requests = 0

        fun Poi.distanceKm() = distanceKmTo(latitude, longitude)

        for (stage in PoiSearchStage.entries) {
            val found = LinkedHashMap<String, Poi>()
            var searchedUpToKm = params.minRadiusKm

            for (radiusKm in params.radiusSteps) {
                if (radiusKm <= searchedUpToKm) continue // still inside the minimum radius
                progress.onProgress(stage, radiusKm)

                val knownWithin = known.values.filter { it.distanceKm() <= radiusKm }
                val needed = target - knownWithin.size - found.size
                val batch = source.findPois(
                    latitude, longitude, stage.rules,
                    outerRadiusMeters = (radiusKm * 1000).roundToInt(),
                    innerRadiusMeters = (searchedUpToKm * 1000).roundToInt(),
                    limit = (needed * OVERSAMPLING).coerceIn(MIN_QUERY_LIMIT, MAX_QUERY_LIMIT),
                )
                requests++
                batch.pois.forEach { poi -> if (poi.id !in known) found.putIfAbsent(poi.id, poi) }
                searchedUpToKm = radiusKm

                if (knownWithin.size + found.size >= target) {
                    val picked = pickRandomly(knownWithin, found.values.toList(), target)
                    return PoiSearchResult(
                        pois = picked.sortedForDisplay(latitude, longitude),
                        params = params,
                        stage = stage,
                        radiusKm = radiusKm,
                        isComplete = true,
                        requestCount = requests,
                    )
                }
            }
            known.putAll(found)
        }

        return PoiSearchResult(
            pois = known.values.toList().sortedForDisplay(latitude, longitude),
            params = params,
            stage = PoiSearchStage.entries.last(),
            radiusKm = params.maxRadiusKm,
            isComplete = false,
            requestCount = requests,
        )
    }

    /** Keeps all [earlier] POIs and fills up to [target] with random [current] ones. */
    private fun pickRandomly(earlier: List<Poi>, current: List<Poi>, target: Int): List<Poi> =
        if (earlier.size >= target) {
            earlier.shuffled(random).take(target)
        } else {
            earlier + current.shuffled(random).take(target - earlier.size)
        }

    private fun List<Poi>.sortedForDisplay(latitude: Double, longitude: Double) =
        sortedWith(compareBy<Poi> { it.poiClass }.thenBy { it.distanceKmTo(latitude, longitude) })

    private companion object {
        /** Ask for more than needed so the random pick has something to choose from. */
        const val OVERSAMPLING = 3
        const val MIN_QUERY_LIMIT = 30
        /** Keeps responses small for dense C/D tags (memorials, artworks, crosses…). */
        const val MAX_QUERY_LIMIT = 300
    }
}
