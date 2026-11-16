package com.example.mobileapp.map

import org.maplibre.android.geometry.LatLng

/** Static configuration of the map (style source, initial camera). */
object MapConfig {
    const val STYLE_URL = "https://styles.maptoolkit.org/summer.json"

    val DEFAULT_CENTER = LatLng(52.2297, 21.0122) // Warsaw
    const val DEFAULT_ZOOM = 11.0

    /** Zoom used when the camera first jumps to the user's location. */
    const val USER_LOCATION_ZOOM = 16.0
    const val CAMERA_TRANSITION_MS = 750L
}
