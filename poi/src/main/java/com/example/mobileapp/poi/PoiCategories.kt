package com.example.mobileapp.poi

/**
 * One OpenStreetMap tag that makes an element a POI of [poiClass], e.g. `historic=castle` → A.
 *
 * @property requiredTag an extra `key to value` the element must also have, e.g. an observation
 *   tower is `man_made=tower` + `tower:type=observation`.
 */
data class PoiTagRule(
    val key: String,
    val value: String,
    val poiClass: PoiClass,
    val requiredTag: Pair<String, String>? = null,
) {
    /** `key=value`, used as the POI's category label. */
    val tag: String get() = "$key=$value"

    fun matches(tags: Map<String, String>): Boolean =
        tags[key] == value && (requiredTag == null || tags[requiredTag.first] == requiredTag.second)
}

/** Which OSM tags count as POIs, and how a tagged element is ranked into a [PoiClass]. */
object PoiCategories {

    /** All POI tags, grouped by class. An element matching several rules gets the best class. */
    val rules: List<PoiTagRule> = buildList {
        // A – main attractions
        addAll(PoiClass.A, "historic", "castle", "palace", "fort")
        addAll(PoiClass.A, "tourism", "museum", "attraction", "theme_park", "zoo", "aquarium")
        add(PoiTagRule("heritage", "1", PoiClass.A)) // UNESCO World Heritage

        // B – important recreational and nature places
        addAll(PoiClass.B, "leisure", "park", "nature_reserve")
        addAll(PoiClass.B, "boundary", "protected_area", "national_park")
        addAll(PoiClass.B, "tourism", "viewpoint")
        addAll(PoiClass.B, "natural", "cave_entrance", "beach", "arch", "gorge", "glacier")
        addAll(PoiClass.B, "waterway", "waterfall")
        add(PoiTagRule("waterfall", "yes", PoiClass.B))
        add(PoiTagRule("man_made", "tower", PoiClass.B, requiredTag = "tower:type" to "observation"))

        // C – local attractions
        addAll(
            PoiClass.C, "historic",
            "ruins", "wayside_shrine", "monument", "memorial", "manor", "archaeological_site",
            "church", "monastery",
        )
        addAll(PoiClass.C, "natural", "peak", "spring", "volcano")
        addAll(PoiClass.C, "man_made", "lighthouse", "windmill", "watermill")
        addAll(PoiClass.C, "tourism", "gallery")
        add(PoiTagRule("natural", "tree", PoiClass.C, requiredTag = "denotation" to "natural_monument"))

        // D – filler points
        addAll(PoiClass.D, "tourism", "artwork", "picnic_site")
        addAll(PoiClass.D, "historic", "wayside_cross", "boundary_stone", "milestone")
        addAll(PoiClass.D, "man_made", "monument", "cross")
        addAll(PoiClass.D, "natural", "rock", "stone")
        addAll(PoiClass.D, "place", "village", "hamlet")
    }

    /** Memorial subtypes that are too small to be a local attraction. */
    private val minorMemorials = setOf("plaque", "blue_plaque", "stolperstein")

    /**
     * The best-matching rule for [tags], or `null` when the element is not a POI.
     * The returned class may be adjusted further by [classify].
     */
    fun bestRule(tags: Map<String, String>): PoiTagRule? =
        rules.filter { it.matches(tags) }.minByOrNull { it.poiClass }

    /**
     * Ranks an OSM element. Besides the plain tag rules:
     * - artworks notable enough for Wikipedia/Wikidata are main attractions (A), others are fillers (D);
     * - memorial plaques and stolpersteine are fillers (D), not local attractions.
     */
    fun classify(tags: Map<String, String>): Pair<PoiTagRule, PoiClass>? {
        val rule = bestRule(tags) ?: return null
        val poiClass = when {
            rule.tag == "tourism=artwork" && tags.isNotable() -> PoiClass.A
            rule.tag == "historic=memorial" && tags["memorial"] in minorMemorials -> PoiClass.D
            else -> rule.poiClass
        }
        return rule to poiClass
    }

    private fun Map<String, String>.isNotable() = containsKey("wikipedia") || containsKey("wikidata")

    private fun MutableList<PoiTagRule>.addAll(poiClass: PoiClass, key: String, vararg values: String) {
        values.forEach { add(PoiTagRule(key, it, poiClass)) }
    }
}
