package com.example.mobileapp.poi

/**
 * A point of interest found in OpenStreetMap.
 *
 * @property id OSM identity, e.g. `node/123` or `way/456`.
 * @property name the `name` tag, or `null` for unnamed places (e.g. many viewpoints or springs).
 * @property latitude position of the node, or the center of a way/relation.
 * @property category the matched tag, e.g. `historic=castle`.
 * @property tags all OSM tags of the element (useful later for ranking, e.g. `wikipedia`, `description`).
 */
data class Poi(
    val id: String,
    val name: String?,
    val latitude: Double,
    val longitude: Double,
    val poiClass: PoiClass,
    val category: String,
    val tags: Map<String, String>,
)
