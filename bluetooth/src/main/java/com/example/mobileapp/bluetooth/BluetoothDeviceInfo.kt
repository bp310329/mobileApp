package com.example.mobileapp.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice

/** Immutable snapshot of a remote Bluetooth device, safe to keep in UI state. */
data class BluetoothDeviceInfo(
    val address: String,
    val name: String?,
    val isPaired: Boolean,
) {
    /** The advertised name, or the MAC address for devices that don't report one. */
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: address
}

// Callers only reach this after BluetoothAccess.hasPermissions() was checked.
@SuppressLint("MissingPermission")
internal fun BluetoothDevice.toInfo() = BluetoothDeviceInfo(
    address = address,
    name = name,
    isPaired = bondState == BluetoothDevice.BOND_BONDED,
)
