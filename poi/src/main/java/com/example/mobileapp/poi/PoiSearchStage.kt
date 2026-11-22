package com.example.mobileapp.poi

/**
 * Stages of the POI search. Each stage adds one more group of POIs to those of earlier stages:
 * 1. A + B, 2. (A + B) then C, 3. (A + B + C) then D, 4. all of those then random villages.
 *
 * @property rules the tags newly queried in this stage.
 */
enum class PoiSearchStage(val number: Int, val rules: List<PoiTagRule>) {
    MAIN_AND_NATURE(1, PoiCategories.rules.filter { it.poiClass == PoiClass.A || it.poiClass == PoiClass.B }),
    LOCAL(2, PoiCategories.rules.filter { it.poiClass == PoiClass.C }),
    FILLER(3, PoiCategories.rules.filter { it.poiClass == PoiClass.D && it.key != PLACE_KEY }),
    VILLAGES(4, PoiCategories.rules.filter { it.key == PLACE_KEY }),
}

private const val PLACE_KEY = "place"
