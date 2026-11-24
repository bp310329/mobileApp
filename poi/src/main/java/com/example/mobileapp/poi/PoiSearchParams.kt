package com.example.mobileapp.poi

/**
 * How many POIs to prepare and where to look for them, derived from the user's route settings.
 *
 * With L = route length (km) and n = preferred POI count:
 * - target = 10 · n
 * - n = 1: minimum radius = L / 2 − 10 %, maximum radius = L / 2 + 10 %
 * - n > 1: minimum radius = L / (n + 1) − 30 %, maximum radius = L / (n + 1) + 30 %
 *
 * POIs are searched in the fixed ring between [minRadiusKm] and [maxRadiusKm] around the center.
 */
data class PoiSearchParams(
    val targetCount: Int,
    val poiCount: Int,
    val minRadiusKm: Double,
    val maxRadiusKm: Double,
) {
    companion object {
        const val POIS_PER_REQUESTED_POI = 10
        private const val SINGLE_POI_SPREAD = 0.1
        private const val MULTI_POI_SPREAD = 0.3

        fun from(routeLengthKm: Int, poiCount: Int): PoiSearchParams {
            require(poiCount >= 1) { "poiCount must be at least 1" }
            val length = routeLengthKm.toDouble()
            val (base, spread) = if (poiCount == 1) {
                length / 2 to SINGLE_POI_SPREAD
            } else {
                length / (poiCount + 1) to MULTI_POI_SPREAD
            }
            return PoiSearchParams(
                targetCount = poiCount * POIS_PER_REQUESTED_POI,
                poiCount = poiCount,
                minRadiusKm = base * (1 - spread),
                maxRadiusKm = base * (1 + spread),
            )
        }
    }
}
