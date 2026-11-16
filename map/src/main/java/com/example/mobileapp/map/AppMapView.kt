package com.example.mobileapp.map

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Self-contained map component: initialises MapLibre, hosts the [MapView], applies the style and
 * initial camera, and forwards the host lifecycle to the map.
 *
 * Usage from the host activity/fragment:
 * ```
 * appMapView.attach(this, savedInstanceState)
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
    private var attached = false

    // TODO: user location
    // private lateinit var fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient

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
        check(!attached) { "AppMapView is already attached" }
        attached = true

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

    private fun configureMap(map: MapLibreMap) {
        map.setStyle(Style.Builder().fromUri(MapConfig.STYLE_URL))
        map.cameraPosition = CameraPosition.Builder()
            .target(MapConfig.DEFAULT_CENTER)
            .zoom(MapConfig.DEFAULT_ZOOM)
            .build()
    }

    override fun onStart(owner: LifecycleOwner) = mapView.onStart()

    override fun onResume(owner: LifecycleOwner) = mapView.onResume()

    override fun onPause(owner: LifecycleOwner) = mapView.onPause()

    override fun onStop(owner: LifecycleOwner) = mapView.onStop()

    override fun onDestroy(owner: LifecycleOwner) {
        owner.lifecycle.removeObserver(this)
        context.applicationContext.unregisterComponentCallbacks(memoryCallbacks)
        mapView.onDestroy()
    }
}
