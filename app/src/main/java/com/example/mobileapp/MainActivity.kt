package com.example.mobileapp

import android.Manifest
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.example.mobileapp.map.AppMapView
import com.example.mobileapp.map.LocationProvider
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: AppMapView

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val statusBar = findViewById<MaterialCardView>(R.id.bluetoothStatusBar)
        val createRouteButton = findViewById<ExtendedFloatingActionButton>(R.id.createRouteButton)
        val centerButton = findViewById<FloatingActionButton>(R.id.centerButton)

        applyWindowInsets(statusBar, createRouteButton, centerButton)
        mapView = findViewById(R.id.mapView)
        mapView.attach(this, savedInstanceState)
        startUserLocation()

        // Placeholders – real behaviour comes later.
        statusBar.setOnClickListener {
            Toast.makeText(this, R.string.bluetooth_placeholder_toast, Toast.LENGTH_SHORT).show()
        }
        centerButton.setOnClickListener {
            if (mapView.enableUserLocation()) {
                mapView.followUser()
            } else {
                requestPermissionLauncher.launch(LocationProvider.REQUIRED_PERMISSIONS)
            }
        }
        createRouteButton.setOnClickListener {
            Toast.makeText(this, R.string.create_route_placeholder_toast, Toast.LENGTH_SHORT).show()
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
        cornerButton: FloatingActionButton,
    ) {
        val baseBottomMargin = (bottomButton.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        val (cornerBaseEnd, cornerBaseBottom) = (cornerButton.layoutParams as ViewGroup.MarginLayoutParams)
            .let { it.marginEnd to it.bottomMargin }
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
            cornerButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                marginEnd = cornerBaseEnd + bars.right
                bottomMargin = cornerBaseBottom + bars.bottom
            }
            insets
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}
