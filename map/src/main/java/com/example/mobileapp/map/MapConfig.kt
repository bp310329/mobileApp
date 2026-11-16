package com.example.mobileapp.map

import org.maplibre.android.geometry.LatLng

/** Static configuration of the map (style source, initial camera). */
object MapConfig {
    const val STYLE_URL = "https://styles.maptoolkit.org/summer.json"

    val DEFAULT_CENTER = LatLng(52.2297, 21.0122) // Warsaw
    const val DEFAULT_ZOOM = 11.0
}
