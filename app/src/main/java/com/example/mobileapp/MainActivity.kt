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
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.mobileapp.bluetooth.BluetoothAccess
import com.example.mobileapp.bluetooth.BluetoothConnectionManager
import com.example.mobileapp.map.AppMapView
import com.example.mobileapp.map.LocationProvider
import com.example.mobileapp.ui.bluetooth.BluetoothDevicesSheet
import com.example.mobileapp.ui.bluetooth.BluetoothViewModel
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: AppMapView
    private val bluetoothViewModel: BluetoothViewModel by viewModels()

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

        applyWindowInsets(statusBar, createRouteButton, centerButton)
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
        // Placeholder – real behaviour comes later.
        createRouteButton.setOnClickListener {
            Toast.makeText(this, R.string.create_route_placeholder_toast, Toast.LENGTH_SHORT).show()
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
