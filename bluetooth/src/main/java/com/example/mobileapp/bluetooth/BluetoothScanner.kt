package com.example.mobileapp.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Finds classic Bluetooth devices: already paired ones and those discoverable nearby. */
class BluetoothScanner(context: Context) {

    private val context = context.applicationContext

    /** Devices paired with this phone. Empty without permission or Bluetooth hardware. */
    @SuppressLint("MissingPermission") // checked via BluetoothAccess.hasPermissions
    fun pairedDevices(): List<BluetoothDeviceInfo> {
        if (!BluetoothAccess.hasPermissions(context)) return emptyList()
        val adapter = BluetoothAccess.adapter(context) ?: return emptyList()
        return adapter.bondedDevices.orEmpty().map { it.toInfo() }
    }

    /**
     * Runs a discovery and emits each device found (the same device may be emitted again, e.g.
     * once its name is resolved). Completes when the system ends discovery (about 12 seconds);
     * cancelling collection stops discovery early.
     */
    @SuppressLint("MissingPermission") // checked via BluetoothAccess.hasPermissions
    fun discover(): Flow<BluetoothDeviceInfo> = callbackFlow {
        val adapter = BluetoothAccess.adapter(context)
        when {
            adapter == null -> close(IllegalStateException("Bluetooth is not supported"))
            !BluetoothAccess.hasPermissions(context) ->
                close(SecurityException("Missing Bluetooth permissions"))
            !adapter.isEnabled -> close(IllegalStateException("Bluetooth is turned off"))
        }
        if (isClosedForSend || adapter == null) return@callbackFlow

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_NAME_CHANGED -> {
                        IntentCompat.getParcelableExtra(
                            intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
                        )?.let { trySend(it.toInfo()) }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> close()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        // Sent by the system Bluetooth stack, i.e. from outside this app.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)

        // Restart so a discovery left running elsewhere doesn't end this one prematurely.
        if (adapter.isDiscovering) adapter.cancelDiscovery()
        if (!adapter.startDiscovery()) close(IllegalStateException("Could not start discovery"))

        awaitClose {
            adapter.cancelDiscovery()
            context.unregisterReceiver(receiver)
        }
    }
}
