package com.example.mobileapp.map

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
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
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.roundToInt

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
    /** Pins currently requested by the host; re-applied when the style (re)loads. */
    private var pins: List<LatLng> = emptyList()

    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) mapView.onLowMemory()
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = mapView.onLowMemory()
    }

    /** Maptoolkit logo (required attribution), bottom-left; opens maptoolkit.org when tapped. */
    private val logoView: ImageView
    /** Maptoolkit / OpenStreetMap copyright line (required attribution), bottom-right. */
    private val copyrightView: TextView
    /** Latest system-bar insets, used to keep MapLibre's attribution button clear of them. */
    private var attributionInsets = Insets.NONE

    init {
        // MapLibre must be initialised before a MapView is created.
        MapLibre.getInstance(context.applicationContext)
        mapView = MapView(context)
        addView(mapView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        logoView = createLogoView()
        copyrightView = createCopyrightView()
        addView(logoView)
        addView(copyrightView)
        applyAttributionInsets()
    }

    /**
     * Maptoolkit requires its logo (min. 24dp high, linking to maptoolkit.org) and the copyright
     * line to stay visible on every map: https://docs.maptoolkit.org/attribution/
     */
    private fun createLogoView() = ImageView(context).apply {
        setImageResource(R.drawable.maptoolkit_logo)
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_START
        contentDescription = context.getString(R.string.maptoolkit_logo_description)
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(LOGO_HEIGHT_DP), Gravity.BOTTOM or Gravity.START)
        setOnClickListener {
            val url = context.getString(R.string.maptoolkit_logo_url)
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure { Log.w(TAG, "No app can open $url", it) }
        }
    }

    private fun createCopyrightView() = TextView(context).apply {
        text = HtmlCompat.fromHtml(
            context.getString(R.string.map_copyright_html),
            HtmlCompat.FROM_HTML_MODE_COMPACT,
        )
        movementMethod = LinkMovementMethod.getInstance()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(COPYRIGHT_TEXT_COLOR)
        setLinkTextColor(COPYRIGHT_TEXT_COLOR)
        setBackgroundColor(COPYRIGHT_BACKGROUND_COLOR)
        setPadding(dp(4), dp(1), dp(4), dp(1))
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
    }

    /** Keeps the attribution clear of the system bars, since the map is drawn edge-to-edge. */
    private fun applyAttributionInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            logoView.updateLayoutParams<LayoutParams> {
                leftMargin = dp(LOGO_MARGIN_DP) + bars.left
                bottomMargin = dp(LOGO_MARGIN_DP) + bars.bottom
            }
            copyrightView.updateLayoutParams<LayoutParams> {
                rightMargin = bars.right
                bottomMargin = bars.bottom
            }
            attributionInsets = bars
            map?.let(::positionAttributionButton)
            insets
        }
    }

    /**
     * MapLibre's own "i" attribution button (also shows the Maptoolkit/OSM credits from the style)
     * must stay enabled; it is placed right of the Maptoolkit logo. MapLibre's logo is hidden so it
     * does not compete with the Maptoolkit one in the same corner.
     */
    private fun positionAttributionButton(map: MapLibreMap) {
        val logoWidth = dp(LOGO_HEIGHT_DP) * LOGO_ASPECT_RATIO
        map.uiSettings.apply {
            isLogoEnabled = false
            isAttributionEnabled = true
            attributionGravity = Gravity.BOTTOM or Gravity.START
            setAttributionMargins(
                attributionInsets.left + dp(LOGO_MARGIN_DP * 2) + logoWidth.toInt(),
                0,
                0,
                attributionInsets.bottom + dp(LOGO_MARGIN_DP),
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

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

    /**
     * Shows a plain (label-less) pin at each of [points], replacing previously shown pins.
     * An empty list removes all pins. Safe to call before the map/style has loaded.
     *
     * @param fitCamera when `true` (and there are pins), stops following the user and moves the
     *   camera so that all pins and the user's position are visible. [followUser] resumes following.
     */
    fun showPins(points: List<LatLng>, fitCamera: Boolean = false) {
        pins = points
        style?.let(::applyPins)
        if (fitCamera && points.isNotEmpty()) fitCameraTo(points)
    }

    private fun applyPins(style: Style) {
        if (style.getImage(PIN_IMAGE_ID) == null) {
            val drawable = checkNotNull(ContextCompat.getDrawable(context, R.drawable.map_pin))
            style.addImage(PIN_IMAGE_ID, drawable.toBitmap())
        }
        val features = FeatureCollection.fromFeatures(
            pins.map { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) }
        )
        val source = style.getSourceAs<GeoJsonSource>(PIN_SOURCE_ID)
        if (source != null) {
            source.setGeoJson(features)
            return
        }
        style.addSource(GeoJsonSource(PIN_SOURCE_ID, features))
        style.addLayer(
            SymbolLayer(PIN_LAYER_ID, PIN_SOURCE_ID).withProperties(
                PropertyFactory.iconImage(PIN_IMAGE_ID),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            )
        )
    }

    private fun fitCameraTo(points: List<LatLng>) {
        val map = map ?: return
        // Otherwise the next location fix would pull the camera back to the user.
        activeLocationComponent()?.cameraMode = CameraMode.NONE
        hasCenteredOnUser = true

        val all = points + listOfNotNull(lastLocation?.let { LatLng(it.latitude, it.longitude) })
        val update = if (all.size == 1) {
            CameraUpdateFactory.newLatLngZoom(all.first(), MapConfig.USER_LOCATION_ZOOM)
        } else {
            val padding = dp(PIN_FIT_PADDING_DP)
            CameraUpdateFactory.newLatLngBounds(
                LatLngBounds.Builder().includes(all).build(),
                attributionInsets.left + padding,
                attributionInsets.top + padding,
                attributionInsets.right + padding,
                attributionInsets.bottom + padding,
            )
        }
        map.animateCamera(update, MapConfig.CAMERA_TRANSITION_MS.toInt())
    }

    private fun configureMap(map: MapLibreMap) {
        this.map = map
        positionAttributionButton(map)
        map.cameraPosition = CameraPosition.Builder()
            .target(MapConfig.DEFAULT_CENTER)
            .zoom(MapConfig.DEFAULT_ZOOM)
            .build()
        map.setStyle(Style.Builder().fromUri(MapConfig.STYLE_URL)) { loadedStyle ->
            style = loadedStyle
            if (pins.isNotEmpty()) applyPins(loadedStyle)
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

        /** Maptoolkit's minimum logo height on Android. */
        const val LOGO_HEIGHT_DP = 24
        const val LOGO_MARGIN_DP = 8
        /** Width / height of res/drawable-xxhdpi/maptoolkit_logo.png (227 x 72 px). */
        const val LOGO_ASPECT_RATIO = 227f / 72f

        const val COPYRIGHT_TEXT_COLOR = 0xFF333333.toInt()
        const val COPYRIGHT_BACKGROUND_COLOR = 0xB3FFFFFF.toInt()

        const val PIN_IMAGE_ID = "app-pin"
        const val PIN_SOURCE_ID = "app-pins"
        const val PIN_LAYER_ID = "app-pins-layer"
        /** Margin around the pins when the camera is fitted to them; also clears the pin icon height. */
        const val PIN_FIT_PADDING_DP = 64
    }
}
