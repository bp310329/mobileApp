package com.example.mobileapp.ui.route

import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.example.mobileapp.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/**
 * Bottom sheet where the user picks route length, number of POIs and vehicle before generating
 * a round-trip route. Tapping "Generate route" delivers the chosen [RouteSettings] to the host via
 * the Fragment Result API under [REQUEST_KEY] (read it with [RouteSettings.fromBundle]).
 */
class RouteSettingsSheet : BottomSheetDialogFragment(R.layout.sheet_route_settings) {

    private lateinit var lengthSlider: Slider
    private lateinit var poiSlider: Slider
    private lateinit var vehicleGroup: MaterialButtonToggleGroup

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        lengthSlider = view.findViewById(R.id.lengthSlider)
        poiSlider = view.findViewById(R.id.poiSlider)
        vehicleGroup = view.findViewById(R.id.vehicleGroup)
        val lengthValue = view.findViewById<TextView>(R.id.lengthValue)
        val poiValue = view.findViewById<TextView>(R.id.poiValue)

        lengthSlider.apply {
            valueFrom = RouteSettings.MIN_LENGTH_KM.toFloat()
            valueTo = RouteSettings.MAX_LENGTH_KM.toFloat()
            stepSize = RouteSettings.LENGTH_STEP_KM.toFloat()
            setLabelFormatter { getString(R.string.route_length_value, it.roundToInt()) }
            addOnChangeListener { _, value, _ ->
                lengthValue.text = getString(R.string.route_length_value, value.roundToInt())
            }
        }
        poiSlider.apply {
            valueFrom = RouteSettings.MIN_POI_COUNT.toFloat()
            valueTo = RouteSettings.MAX_POI_COUNT.toFloat()
            stepSize = 1f
            addOnChangeListener { _, value, _ ->
                poiValue.text = value.roundToInt().toString()
            }
        }

        val initial = RouteSettings.fromBundle(
            savedInstanceState?.getBundle(KEY_SETTINGS) ?: arguments?.getBundle(ARG_SETTINGS)
        )
        showSettings(initial)
        lengthValue.text = getString(R.string.route_length_value, initial.lengthKm)
        poiValue.text = initial.poiCount.toString()

        view.findViewById<View>(R.id.generateButton).setOnClickListener {
            parentFragmentManager.setFragmentResult(REQUEST_KEY, currentSettings().toBundle())
            dismiss()
        }
    }

    private fun showSettings(settings: RouteSettings) {
        lengthSlider.value = settings.lengthKm.toFloat()
        poiSlider.value = settings.poiCount.toFloat()
        vehicleGroup.check(
            when (settings.vehicle) {
                Vehicle.CAR -> R.id.vehicleCar
                Vehicle.MOTORCYCLE -> R.id.vehicleMotorcycle
                Vehicle.BIKE -> R.id.vehicleBike
            }
        )
    }

    private fun currentSettings() = RouteSettings(
        lengthKm = lengthSlider.value.roundToInt(),
        poiCount = poiSlider.value.roundToInt(),
        vehicle = when (vehicleGroup.checkedButtonId) {
            R.id.vehicleMotorcycle -> Vehicle.MOTORCYCLE
            R.id.vehicleBike -> Vehicle.BIKE
            else -> Vehicle.CAR
        },
    )

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::lengthSlider.isInitialized) outState.putBundle(KEY_SETTINGS, currentSettings().toBundle())
    }

    companion object {
        const val TAG = "RouteSettingsSheet"
        /** Fragment-result key under which the chosen [RouteSettings] bundle is delivered. */
        const val REQUEST_KEY = "routeSettings"

        private const val ARG_SETTINGS = "settings"
        private const val KEY_SETTINGS = "settings"

        /** Creates the sheet pre-filled with [initial] (e.g. the user's last choice). */
        fun newInstance(initial: RouteSettings) = RouteSettingsSheet().apply {
            arguments = Bundle().apply { putBundle(ARG_SETTINGS, initial.toBundle()) }
        }
    }
}
