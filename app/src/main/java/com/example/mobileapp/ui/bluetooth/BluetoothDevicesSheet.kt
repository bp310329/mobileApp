package com.example.mobileapp.ui.bluetooth

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.example.mobileapp.R
import com.example.mobileapp.bluetooth.BluetoothConnectionManager.State
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Bottom sheet listing paired and nearby Bluetooth devices. Tapping one connects to it; the sheet
 * closes itself once that connection succeeds.
 *
 * The host must ensure Bluetooth is on and [com.example.mobileapp.bluetooth.BluetoothAccess.REQUIRED_PERMISSIONS] are granted.
 */
class BluetoothDevicesSheet : BottomSheetDialogFragment(R.layout.sheet_bluetooth_devices) {

    private val viewModel by lazy {
        ViewModelProvider(requireActivity())[BluetoothViewModel::class.java]
    }

    /** Address the user picked in this sheet; once connected to it, the sheet is dismissed. */
    private var pendingAddress: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        pendingAddress = savedInstanceState?.getString(KEY_PENDING_ADDRESS)

        val list = view.findViewById<RecyclerView>(R.id.deviceList)
        val progress = view.findViewById<View>(R.id.scanProgress)
        val scanButton = view.findViewById<View>(R.id.scanButton)
        val emptyText = view.findViewById<TextView>(R.id.emptyText)
        val connectedPanel = view.findViewById<View>(R.id.connectedPanel)
        val connectedText = view.findViewById<TextView>(R.id.connectedText)

        val adapter = BluetoothDeviceAdapter { device ->
            pendingAddress = device.address
            viewModel.connect(device)
        }
        list.adapter = adapter
        list.itemAnimator = null // avoid row flicker on status changes

        scanButton.setOnClickListener { viewModel.startScan() }
        view.findViewById<View>(R.id.disconnectButton).setOnClickListener {
            pendingAddress = null
            viewModel.disconnect()
        }

        if (savedInstanceState == null) viewModel.startScan()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(viewModel.devices, viewModel.connectionState, viewModel.isScanning) {
                            devices, state, scanning -> Triple(devices, state, scanning)
                    }.collect { (devices, state, scanning) ->
                        adapter.submitList(DeviceRow.from(devices, state))
                        progress.isVisible = scanning
                        scanButton.isEnabled = !scanning
                        emptyText.isVisible = devices.isEmpty()
                        emptyText.setText(
                            if (scanning) R.string.bluetooth_scanning else R.string.bluetooth_no_devices
                        )

                        connectedPanel.isVisible = state is State.Connected
                        if (state is State.Connected) {
                            connectedText.text =
                                getString(R.string.bluetooth_connected_to, state.device.displayName)
                            if (state.device.address == pendingAddress) dismiss()
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PENDING_ADDRESS, pendingAddress)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Keep scanning across rotation; stop once the sheet is really gone.
        if (activity?.isChangingConfigurations != true) viewModel.stopScan()
    }

    companion object {
        const val TAG = "BluetoothDevicesSheet"
        private const val KEY_PENDING_ADDRESS = "pendingAddress"
    }
}
