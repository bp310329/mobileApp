package com.example.mobileapp.poi

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A point of interest found in OpenStreetMap.
 *
 * @property id OSM identity, e.g. `node/123` or `way/456`.
 * @property name the `name` tag, or `null` for unnamed places (e.g. many viewpoints or springs).
 * @property category the matched tag, e.g. `historic=castle`.
 * @property description the `description` tag, if mapped.
 * @property latitude position of the node, or the center of a way/relation.
 * @property tags all OSM tags of the element (useful later for ranking, e.g. `wikipedia`).
 */
data class Poi(
    val id: String,
    val name: String?,
    val category: String,
    val description: String?,
    val latitude: Double,
    val longitude: Double,
    val poiClass: PoiClass,
    val tags: Map<String, String>,
) {
    /** Great-circle distance from this POI to the given point, in kilometers. */
    fun distanceKmTo(latitude: Double, longitude: Double): Double =
        haversineKm(this.latitude, this.longitude, latitude, longitude)
}

private const val EARTH_RADIUS_KM = 6371.0088

internal fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_KM * asin(sqrt(a.coerceIn(0.0, 1.0)))
}
