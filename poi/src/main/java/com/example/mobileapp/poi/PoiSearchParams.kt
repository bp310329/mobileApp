package com.example.mobileapp.poi

import kotlin.math.min
import kotlin.math.sqrt

/**
 * How many POIs to prepare and where to look for them, derived from the user's route settings.
 *
 * With L = route length (km), n = preferred POI count and F = √(n / 5):
 * - target = 10 · n
 * - starting radius = clamp(0.06 · L · F, 0.5, 8) km
 * - maximum radius  = clamp(0.18 · L · F, 1.25, 30) km
 * - minimum radius  = min(0.1 · L, 10) km — POIs closer than this to the start are skipped.
 *
 * POIs are searched in the ring between [minRadiusKm] and the current radius, which starts at
 * [startRadiusKm] and grows ×[RADIUS_GROWTH] up to [maxRadiusKm] (see [radiusSteps]).
 */
data class PoiSearchParams(
    val targetCount: Int,
    val minRadiusKm: Double,
    val startRadiusKm: Double,
    val maxRadiusKm: Double,
) {
    /** Search radii for one stage: start, start·1.5, start·1.5², … ending exactly at the maximum. */
    val radiusSteps: List<Double>
        get() = buildList {
            var radius = min(startRadiusKm, maxRadiusKm)
            add(radius)
            while (radius < maxRadiusKm) {
                radius = min(radius * RADIUS_GROWTH, maxRadiusKm)
                add(radius)
            }
        }

    companion object {
        const val POIS_PER_REQUESTED_POI = 10
        const val RADIUS_GROWTH = 1.5

        fun from(routeLengthKm: Int, poiCount: Int): PoiSearchParams {
            val length = routeLengthKm.toDouble()
            val f = sqrt(poiCount / 5.0)
            val start = (0.06 * length * f).coerceIn(0.5, 8.0)
            val max = (0.18 * length * f).coerceIn(1.25, 30.0)
            var minimum = min(0.1 * length, 10.0)
            // For few POIs on a long route the formulas give min ≥ max (e.g. 1 POI, 50 km:
            // min 5 km, max 4 km), which would leave nothing to search. Fall back to half of max.
            if (minimum >= max) minimum = max / 2
            return PoiSearchParams(
                targetCount = poiCount * POIS_PER_REQUESTED_POI,
                minRadiusKm = minimum,
                startRadiusKm = start,
                maxRadiusKm = max,
            )
        }
    }
}
