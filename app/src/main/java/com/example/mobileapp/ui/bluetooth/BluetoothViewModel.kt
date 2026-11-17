package com.example.mobileapp.ui.bluetooth

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.example.mobileapp.bluetooth.BluetoothConnectionManager
import com.example.mobileapp.bluetooth.BluetoothDeviceInfo
import com.example.mobileapp.bluetooth.BluetoothScanner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Activity-scoped Bluetooth state: the device list shown in [BluetoothDevicesSheet] and the
 * single active connection. Survives configuration changes, so a rotation doesn't drop the link.
 */
class BluetoothViewModel(application: Application) : AndroidViewModel(application) {

    private val scanner = BluetoothScanner(application)
    private val connection = BluetoothConnectionManager(application, viewModelScope)

    val connectionState: StateFlow<BluetoothConnectionManager.State> = connection.state

    private val _devices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    /** Paired devices first, then discovered ones in the order they were found. */
    val devices: StateFlow<List<BluetoothDeviceInfo>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var scanJob: Job? = null

    fun startScan() {
        scanJob?.cancel()
        _devices.value = scanner.pairedDevices().sortedBy { it.displayName.lowercase() }
        _isScanning.value = true
        val job = viewModelScope.launch {
            scanner.discover()
                .catch { e -> Log.w(TAG, "Bluetooth discovery failed", e) }
                .collect(::addOrUpdate)
        }
        scanJob = job
        job.invokeOnCompletion {
            // Ignore a superseded scan finishing after a new one started.
            if (scanJob === job) _isScanning.value = false
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _isScanning.value = false
    }

    fun connect(device: BluetoothDeviceInfo) {
        stopScan()
        connection.connect(device)
    }

    fun disconnect() = connection.disconnect()

    private fun addOrUpdate(found: BluetoothDeviceInfo) = _devices.update { list ->
        val index = list.indexOfFirst { it.address == found.address }
        when {
            index < 0 -> list + found
            found.name.isNullOrBlank() -> list
            else -> list.toMutableList().apply { this[index] = this[index].copy(name = found.name) }
        }
    }

    override fun onCleared() {
        connection.disconnect()
    }

    private companion object {
        const val TAG = "BluetoothViewModel"
    }
}
