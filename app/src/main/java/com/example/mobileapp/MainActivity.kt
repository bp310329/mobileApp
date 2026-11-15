package com.example.mobileapp

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // MapLibre must be initialised before the MapView is inflated.
        MapLibre.getInstance(this)

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val statusBar = findViewById<MaterialCardView>(R.id.bluetoothStatusBar)
        val createRouteButton = findViewById<ExtendedFloatingActionButton>(R.id.createRouteButton)

        applyWindowInsets(statusBar, createRouteButton)
        setupMap(savedInstanceState)

        // Placeholders – real behaviour comes later.
        statusBar.setOnClickListener {
            Toast.makeText(this, R.string.bluetooth_placeholder_toast, Toast.LENGTH_SHORT).show()
        }
        createRouteButton.setOnClickListener {
            Toast.makeText(this, R.string.create_route_placeholder_toast, Toast.LENGTH_SHORT).show()
        }
    }

    /** The map stays edge-to-edge; only the overlay controls are moved away from the system bars. */
    private fun applyWindowInsets(topBar: MaterialCardView, bottomButton: ExtendedFloatingActionButton) {
        val baseBottomMargin = (bottomButton.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
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
            insets
        }
    }

    private fun setupMap(savedInstanceState: Bundle?) {
        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)

        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromUri(MAPTOOLKIT_STYLE_URL))
            map.cameraPosition = CameraPosition.Builder()
                .target(DEFAULT_CENTER)
                .zoom(DEFAULT_ZOOM)
                .build()
        }
    }

    // MapView must follow the activity lifecycle.
    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        mapView.onDestroy()
        super.onDestroy()
    }

    private companion object {
        const val MAPTOOLKIT_STYLE_URL = "https://styles.maptoolkit.org/summer.json"

        val DEFAULT_CENTER = LatLng(52.2297, 21.0122) // Warsaw
        const val DEFAULT_ZOOM = 11.0
    }
}
