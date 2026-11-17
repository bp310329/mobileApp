package com.example.mobileapp.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Availability and permission checks the host must pass before scanning or connecting. */
object BluetoothAccess {

    /** Runtime permissions needed for discovery and connecting; request them all together. */
    val REQUIRED_PERMISSIONS: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Before Android 12, discovery results are only delivered with location access.
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasPermissions(context: Context): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    fun isSupported(context: Context): Boolean = adapter(context) != null

    fun isEnabled(context: Context): Boolean = adapter(context)?.isEnabled == true

    /** System dialog asking the user to turn Bluetooth on; launch it for a result. */
    fun enableIntent(): Intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
}
