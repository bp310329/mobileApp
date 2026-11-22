package com.example.mobileapp.poi

/**
 * Priority class of a point of interest, used when choosing which places a route should visit.
 * Declared best-first, so [ordinal] / natural ordering = priority (A before D).
 */
enum class PoiClass(val description: String) {
    /** Main attractions: castles, palaces, museums, major tourist attractions. */
    A("Main attraction"),

    /** Important recreational and nature places: parks, reserves, viewpoints, caves, waterfalls. */
    B("Recreation and nature"),

    /** Local attractions: ruins, shrines, monuments, memorials, peaks, springs. */
    C("Local attraction"),

    /** Filler points: small artworks, wayside crosses, boundary stones, rocks, villages. */
    D("Filler point"),
}
