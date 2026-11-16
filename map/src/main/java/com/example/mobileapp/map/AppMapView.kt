package com.example.mobileapp.map

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.location.Location
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.widget.FrameLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Self-contained map component: initialises MapLibre, hosts the [MapView], applies the style and
 * initial camera, forwards the host lifecycle to the map and shows the user's live position.
 *
 * Usage from the host activity/fragment:
 * ```
 * appMapView.attach(this, savedInstanceState)
 * // once location permission is granted:
 * appMapView.enableUserLocation()
 * // ...and in onSaveInstanceState:
 * appMapView.onSaveInstanceState(outState)
 * ```
 */
class AppMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr), DefaultLifecycleObserver {

    private val mapView: MapView

    /** Device location source; also usable by the host, e.g. via [LocationProvider.currentLocation]. */
    val locationProvider = LocationProvider(context)

    private var owner: LifecycleOwner? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null

    /** Collects location updates while the host is at least STARTED; non-null once enabled. */
    private var trackingJob: Job? = null
    private var hasCenteredOnUser = false
    /** Latest fix, kept so a position that arrives before the style loads isn't lost. */
    private var lastLocation: Location? = null

    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) mapView.onLowMemory()
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = mapView.onLowMemory()
    }

    init {
        // MapLibre must be initialised before a MapView is created.
        MapLibre.getInstance(context.applicationContext)
        mapView = MapView(context)
        addView(mapView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Binds the map to [owner]'s lifecycle. Call once, from the host's `onCreate`. */
    fun attach(owner: LifecycleOwner, savedInstanceState: Bundle?) {
        check(this.owner == null) { "AppMapView is already attached" }
        this.owner = owner

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(::configureMap)
        context.applicationContext.registerComponentCallbacks(memoryCallbacks)
        owner.lifecycle.addObserver(this)
    }

    /** Must be forwarded from the host's `onSaveInstanceState`. */
    fun onSaveInstanceState(outState: Bundle) {
        mapView.onSaveInstanceState(outState)
    }

    /** Runs [callback] once the underlying map is ready. */
    fun getMapAsync(callback: (MapLibreMap) -> Unit) {
        mapView.getMapAsync(callback)
    }

    /**
     * Starts showing the user's real-time position (marker + camera follow).
     * The host must have obtained [LocationProvider.REQUIRED_PERMISSIONS] first.
     *
     * @return `false` when no location permission is granted.
     */
    fun enableUserLocation(): Boolean {
        if (!locationProvider.hasLocationPermission()) return false
        val owner = checkNotNull(owner) { "Call attach() before enableUserLocation()" }
        if (trackingJob != null) return true

        style?.let(::activateLocationComponent)
        // Updates run only while the host is visible and stop automatically in onStop.
        trackingJob = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                locationProvider.locationUpdates()
                    .catch { e -> Log.w(TAG, "Location updates failed", e) }
                    .collect { location -> onLocationUpdate(location) }
            }
        }
        return true
    }

    /** Re-centers the camera on the user and resumes following them. */
    fun followUser() {
        val component = activeLocationComponent() ?: return
        component.setCameraMode(
            CameraMode.TRACKING,
            MapConfig.CAMERA_TRANSITION_MS,
            MapConfig.USER_LOCATION_ZOOM,
            null,
            null,
            null,
        )
    }

    private fun configureMap(map: MapLibreMap) {
        this.map = map
        map.cameraPosition = CameraPosition.Builder()
            .target(MapConfig.DEFAULT_CENTER)
            .zoom(MapConfig.DEFAULT_ZOOM)
            .build()
        map.setStyle(Style.Builder().fromUri(MapConfig.STYLE_URL)) { loadedStyle ->
            style = loadedStyle
            if (trackingJob != null) activateLocationComponent(loadedStyle)
        }
    }

    /**
     * Uses MapLibre's location component purely as the renderer (location puck + camera tracking).
     * Positions come from [LocationProvider] (fused provider) and are pushed in via
     * [LocationComponent.forceLocationUpdate], so MapLibre's own location engine stays disabled.
     */
    @SuppressLint("MissingPermission") // permission verified in enableUserLocation()
    private fun activateLocationComponent(style: Style) {
        val component = map?.locationComponent ?: return
        if (!component.isLocationComponentActivated) {
            component.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style)
                    .useDefaultLocationEngine(false)
                    .build()
            )
        }
        component.isLocationComponentEnabled = true
        component.renderMode = RenderMode.NORMAL
        lastLocation?.let(::onLocationUpdate)
    }

    private fun onLocationUpdate(location: Location) {
        lastLocation = location
        val component = activeLocationComponent() ?: return
        component.forceLocationUpdate(location)
        if (!hasCenteredOnUser) {
            hasCenteredOnUser = true
            followUser()
        }
    }

    private fun activeLocationComponent(): LocationComponent? =
        map?.locationComponent?.takeIf { it.isLocationComponentActivated }

    override fun onStart(owner: LifecycleOwner) = mapView.onStart()

    override fun onResume(owner: LifecycleOwner) = mapView.onResume()

    override fun onPause(owner: LifecycleOwner) = mapView.onPause()

    override fun onStop(owner: LifecycleOwner) = mapView.onStop()

    override fun onDestroy(owner: LifecycleOwner) {
        owner.lifecycle.removeObserver(this)
        context.applicationContext.unregisterComponentCallbacks(memoryCallbacks)
        trackingJob = null
        map = null
        style = null
        mapView.onDestroy()
    }

    private companion object {
        const val TAG = "AppMapView"
    }
}
