package com.example.mobileapp

import android.Manifest
import android.content.res.ColorStateList
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.mobileapp.bluetooth.BluetoothAccess
import com.example.mobileapp.bluetooth.BluetoothConnectionManager
import com.example.mobileapp.map.AppMapView
import com.example.mobileapp.map.LocationProvider
import com.example.mobileapp.map.toUserLocation
import com.example.mobileapp.poi.PoiSearchResult
import com.example.mobileapp.ui.bluetooth.BluetoothDevicesSheet
import com.example.mobileapp.ui.bluetooth.BluetoothViewModel
import com.example.mobileapp.ui.route.PoiListSheet
import com.example.mobileapp.ui.route.RouteSettings
import com.example.mobileapp.ui.route.RouteSettingsSheet
import com.example.mobileapp.ui.route.RouteViewModel
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: AppMapView
    private val bluetoothViewModel: BluetoothViewModel by viewModels()
    /** Last route preferences; pre-fills the route settings sheet. */
    private var routeSettings = RouteSettings()
    private val routeViewModel: RouteViewModel by viewModels()
    private var locationJob: Job? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                      permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (granted) {
            mapView.enableUserLocation()
            mapView.followUser()
        } else {
            Toast.makeText(this, R.string.location_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            openBluetoothDevices()
        } else {
            Toast.makeText(this, R.string.bluetooth_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (BluetoothAccess.isEnabled(this)) {
            openBluetoothDevices()
        } else {
            Toast.makeText(this, R.string.bluetooth_disabled, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val statusBar = findViewById<MaterialCardView>(R.id.bluetoothStatusBar)
        val createRouteButton = findViewById<ExtendedFloatingActionButton>(R.id.createRouteButton)
        val centerButton = findViewById<FloatingActionButton>(R.id.centerButton)
        val poiListButton = findViewById<FloatingActionButton>(R.id.poiListButton)

        applyWindowInsets(statusBar, createRouteButton, listOf(centerButton, poiListButton))
        mapView = findViewById(R.id.mapView)
        mapView.attach(this, savedInstanceState)
        startUserLocation()

        statusBar.setOnClickListener { openBluetoothDevices() }
        observeBluetoothStatus()

        centerButton.setOnClickListener {
            if (mapView.enableUserLocation()) {
                mapView.followUser()
            } else {
                requestPermissionLauncher.launch(LocationProvider.REQUIRED_PERMISSIONS)
            }
        }
        routeSettings = RouteSettings.fromBundle(savedInstanceState?.getBundle(KEY_ROUTE_SETTINGS))
        createRouteButton.setOnClickListener { openRouteSettings() }
        supportFragmentManager.setFragmentResultListener(RouteSettingsSheet.REQUEST_KEY, this) { _, result ->
            routeSettings = RouteSettings.fromBundle(result)
            generateRoute(routeSettings)
        }
        poiListButton.setOnClickListener { showPoiList() }
        observePoiSearch(poiListButton)
        if (BuildConfig.DEBUG) enableDebugLocationOverride()
    }

    /**
     * Debug builds only: long-pressing the map lets you pretend you are at that point (for the map
     * marker and route/POI search) or return to the real GPS position.
     */
    private fun enableDebugLocationOverride() {
        mapView.getMapAsync { map ->
            map.addOnMapLongClickListener { point ->
                val locationProvider = mapView.locationProvider
                val options = buildList {
                    add(getString(R.string.debug_location_set_here))
                    if (locationProvider.isLocationOverridden) add(getString(R.string.debug_location_use_real))
                }
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.debug_location_title)
                    .setItems(options.toTypedArray()) { _, which ->
                        if (which == 0) {
                            locationProvider.setLocationOverride(point.latitude, point.longitude)
                        } else {
                            locationProvider.clearLocationOverride()
                        }
                        // The marker moves via the location flow; bring the camera along.
                        if (mapView.enableUserLocation()) mapView.followUser()
                    }
                    .show()
                true
            }
        }
    }

    /** Opens the POI list sheet (search progress / results), unless it's already showing. */
    private fun showPoiList() {
        if (supportFragmentManager.findFragmentByTag(PoiListSheet.TAG) == null) {
            PoiListSheet().show(supportFragmentManager, PoiListSheet.TAG)
        }
    }

    /**
     * Pins every found POI on the map once the search finishes; clears them for a new search.
     * [poiListButton] is shown whenever there is a search to look at, so a closed list can be reopened.
     */
    private fun observePoiSearch(poiListButton: View) {
        lifecycleScope.launch {
            var shownResult: PoiSearchResult? = null
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                routeViewModel.poiSearch.collect { state ->
                    poiListButton.isVisible = state != RouteViewModel.PoiSearchState.Idle
                    when (state) {
                        is RouteViewModel.PoiSearchState.Done -> {
                            // Fit the camera only for a fresh result, not when re-collecting it.
                            val isNew = state.result !== shownResult
                            shownResult = state.result
                            mapView.showPins(
                                state.result.pois.map { LatLng(it.latitude, it.longitude) },
                                fitCamera = isNew,
                            )
                        }
                        is RouteViewModel.PoiSearchState.Searching,
                        RouteViewModel.PoiSearchState.Idle -> mapView.showPins(emptyList())
                        is RouteViewModel.PoiSearchState.Failed -> Unit
                    }
                }
            }
        }
    }

    private fun openRouteSettings() {
        if (supportFragmentManager.findFragmentByTag(RouteSettingsSheet.TAG) != null) return
        RouteSettingsSheet.newInstance(routeSettings).show(supportFragmentManager, RouteSettingsSheet.TAG)
    }

    /**
     * Step 1 of route generation: queries POIs around the user. Next steps (not implemented yet):
     * let the AI model pick the best POIs and build an A-to-A route through them.
     */
    private fun generateRoute(settings: RouteSettings) {
        val locationProvider = mapView.locationProvider
        if (!locationProvider.hasLocationPermission()) {
            requestPermissionLauncher.launch(LocationProvider.REQUIRED_PERMISSIONS)
            return
        }
        locationJob?.cancel()
        locationJob = lifecycleScope.launch {
            val location = locationProvider.currentLocation.value
                ?: locationProvider.getCurrentLocation()?.toUserLocation()
            if (location == null) {
                Toast.makeText(this@MainActivity, R.string.route_location_unavailable, Toast.LENGTH_LONG).show()
                return@launch
            }
            routeViewModel.searchPois(settings, location.latitude, location.longitude)
            // Testing aid: show search progress and the prepared POIs.
            showPoiList()
        }
    }

    /**
     * Opens the device picker, first walking through whatever is missing:
     * Bluetooth permissions, then turning Bluetooth on.
     */
    private fun openBluetoothDevices() {
        when {
            !BluetoothAccess.isSupported(this) ->
                Toast.makeText(this, R.string.bluetooth_not_supported, Toast.LENGTH_SHORT).show()
            !BluetoothAccess.hasPermissions(this) ->
                bluetoothPermissionLauncher.launch(BluetoothAccess.REQUIRED_PERMISSIONS)
            !BluetoothAccess.isEnabled(this) ->
                enableBluetoothLauncher.launch(BluetoothAccess.enableIntent())
            supportFragmentManager.findFragmentByTag(BluetoothDevicesSheet.TAG) == null ->
                BluetoothDevicesSheet().show(supportFragmentManager, BluetoothDevicesSheet.TAG)
        }
    }

    /** Mirrors the connection state in the top bar: text and coloured status dot. */
    private fun observeBluetoothStatus() {
        val statusText = findViewById<TextView>(R.id.bluetoothStatusText)
        val statusDot = findViewById<View>(R.id.bluetoothStatusDot)
        val icon = findViewById<ImageView>(R.id.bluetoothIcon)
        val idleIconTint = icon.imageTintList

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bluetoothViewModel.connectionState.collect { state ->
                    val (text, dotColor) = when (state) {
                        BluetoothConnectionManager.State.Disconnected ->
                            getString(R.string.bluetooth_status_disconnected) to
                                R.color.status_disconnected
                        is BluetoothConnectionManager.State.Connecting ->
                            getString(R.string.bluetooth_status_connecting, state.device.displayName) to
                                R.color.status_connecting
                        is BluetoothConnectionManager.State.Connected ->
                            getString(R.string.bluetooth_status_connected, state.device.displayName) to
                                R.color.status_connected
                        is BluetoothConnectionManager.State.Failed ->
                            getString(R.string.bluetooth_status_failed, state.device.displayName) to
                                R.color.status_disconnected
                    }
                    statusText.text = text
                    statusDot.backgroundTintList =
                        ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, dotColor))
                    icon.imageTintList = if (state is BluetoothConnectionManager.State.Connected) {
                        ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, dotColor))
                    } else {
                        idleIconTint
                    }
                }
            }
        }
    }

    /** Shows the live position right away if permitted, otherwise asks for location permission. */
    private fun startUserLocation() {
        if (!mapView.enableUserLocation()) {
            requestPermissionLauncher.launch(LocationProvider.REQUIRED_PERMISSIONS)
        }
    }

    /** The map stays edge-to-edge; only the overlay controls are moved away from the system bars. */
    private fun applyWindowInsets(
        topBar: MaterialCardView,
        bottomButton: ExtendedFloatingActionButton,
        cornerButtons: List<FloatingActionButton>,
    ) {
        val baseBottomMargin = (bottomButton.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        val cornerBaseMargins = cornerButtons.map { button ->
            (button.layoutParams as ViewGroup.MarginLayoutParams).let { it.marginEnd to it.bottomMargin }
        }
        val topBarContent = findViewById<View>(R.id.bluetoothStatusContent)
        val contentPadding = Rect(
            topBarContent.paddingLeft, topBarContent.paddingTop,
            topBarContent.paddingRight, topBarContent.paddingBottom
        )

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // The bar's background reaches the screen edges (including under the status bar);
            // only its content is padded away from the system bars.
            topBarContent.setPadding(
                contentPadding.left + bars.left,
                contentPadding.top + bars.top,
                contentPadding.right + bars.right,
                contentPadding.bottom
            )
            bottomButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = baseBottomMargin + bars.bottom
            }
            cornerButtons.zip(cornerBaseMargins) { button, (baseEnd, baseBottom) ->
                button.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    marginEnd = baseEnd + bars.right
                    bottomMargin = baseBottom + bars.bottom
                }
            }
            insets
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
        outState.putBundle(KEY_ROUTE_SETTINGS, routeSettings.toBundle())
    }

    private companion object {
        const val KEY_ROUTE_SETTINGS = "routeSettings"
    }
}
