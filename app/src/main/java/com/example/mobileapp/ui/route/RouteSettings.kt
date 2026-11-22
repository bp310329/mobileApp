package com.example.mobileapp.ui.route

import android.os.Bundle

/** Vehicle the recreational route is planned for. */
enum class Vehicle { CAR, MOTORCYCLE, BIKE }

/**
 * User preferences for generating a round-trip (A-to-A) recreational route.
 *
 * @property lengthKm preferred total route length, [MIN_LENGTH_KM]..[MAX_LENGTH_KM].
 * @property poiCount preferred number of points of interest on the route, [MIN_POI_COUNT]..[MAX_POI_COUNT].
 */
data class RouteSettings(
    val lengthKm: Int = DEFAULT_LENGTH_KM,
    val poiCount: Int = DEFAULT_POI_COUNT,
    val vehicle: Vehicle = Vehicle.CAR,
) {
    init {
        require(lengthKm in MIN_LENGTH_KM..MAX_LENGTH_KM) { "lengthKm out of range: $lengthKm" }
        require(poiCount in MIN_POI_COUNT..MAX_POI_COUNT) { "poiCount out of range: $poiCount" }
    }

    fun toBundle() = Bundle().apply {
        putInt(KEY_LENGTH_KM, lengthKm)
        putInt(KEY_POI_COUNT, poiCount)
        putString(KEY_VEHICLE, vehicle.name)
    }

    companion object {
        const val MIN_LENGTH_KM = 5
        const val MAX_LENGTH_KM = 150
        const val LENGTH_STEP_KM = 5
        const val DEFAULT_LENGTH_KM = 50

        const val MIN_POI_COUNT = 1
        const val MAX_POI_COUNT = 10
        const val DEFAULT_POI_COUNT = 5

        private const val KEY_LENGTH_KM = "lengthKm"
        private const val KEY_POI_COUNT = "poiCount"
        private const val KEY_VEHICLE = "vehicle"

        /** Reads settings written by [toBundle]; falls back to defaults for missing/invalid values. */
        fun fromBundle(bundle: Bundle?): RouteSettings {
            if (bundle == null) return RouteSettings()
            val defaults = RouteSettings()
            return RouteSettings(
                lengthKm = bundle.getInt(KEY_LENGTH_KM, defaults.lengthKm)
                    .coerceIn(MIN_LENGTH_KM, MAX_LENGTH_KM),
                poiCount = bundle.getInt(KEY_POI_COUNT, defaults.poiCount)
                    .coerceIn(MIN_POI_COUNT, MAX_POI_COUNT),
                vehicle = bundle.getString(KEY_VEHICLE)
                    ?.let { name -> Vehicle.entries.firstOrNull { it.name == name } }
                    ?: defaults.vehicle,
            )
        }
    }
}
