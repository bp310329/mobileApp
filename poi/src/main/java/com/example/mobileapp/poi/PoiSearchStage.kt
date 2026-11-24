package com.example.mobileapp.poi

/**
 * Stages of the POI search. Each stage queries one more group of POIs, added to those of earlier
 * stages: 1. A + B, 2. C, 3. D.
 *
 * @property rules the tags newly queried in this stage.
 */
enum class PoiSearchStage(val number: Int, val rules: List<PoiTagRule>) {
    MAIN_AND_NATURE(1, PoiCategories.rules.filter { it.poiClass == PoiClass.A || it.poiClass == PoiClass.B }),
    LOCAL(2, PoiCategories.rules.filter { it.poiClass == PoiClass.C }),
    FILLER(3, PoiCategories.rules.filter { it.poiClass == PoiClass.D }),
}
