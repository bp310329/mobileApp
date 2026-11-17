package com.example.mobileapp.ui.bluetooth

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.mobileapp.R
import com.example.mobileapp.bluetooth.BluetoothDeviceInfo
import com.example.mobileapp.bluetooth.BluetoothConnectionManager.State

/** One row of the device list, with that device's connection status already resolved. */
data class DeviceRow(val device: BluetoothDeviceInfo, val status: Status) {
    enum class Status { NONE, PAIRED, CONNECTING, CONNECTED, FAILED }

    companion object {
        fun from(devices: List<BluetoothDeviceInfo>, state: State): List<DeviceRow> {
            val (active, activeStatus) = when (state) {
                is State.Connecting -> state.device.address to Status.CONNECTING
                is State.Connected -> state.device.address to Status.CONNECTED
                is State.Failed -> state.device.address to Status.FAILED
                State.Disconnected -> null to Status.NONE
            }
            return devices.map { device ->
                val status = when {
                    device.address == active -> activeStatus
                    device.isPaired -> Status.PAIRED
                    else -> Status.NONE
                }
                DeviceRow(device, status)
            }
        }
    }
}

class BluetoothDeviceAdapter(
    private val onClick: (BluetoothDeviceInfo) -> Unit,
) : ListAdapter<DeviceRow, BluetoothDeviceAdapter.ViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_bluetooth_device, parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.deviceName)
        private val address: TextView = view.findViewById(R.id.deviceAddress)
        private val status: TextView = view.findViewById(R.id.deviceStatus)
        private val progress: View = view.findViewById(R.id.deviceProgress)

        fun bind(row: DeviceRow) {
            name.text = row.device.displayName
            address.text = row.device.address
            address.isVisible = row.device.displayName != row.device.address

            val statusText = when (row.status) {
                DeviceRow.Status.NONE -> null
                DeviceRow.Status.PAIRED -> R.string.bluetooth_device_paired
                DeviceRow.Status.CONNECTING -> null // shown as a spinner
                DeviceRow.Status.CONNECTED -> R.string.bluetooth_device_connected
                DeviceRow.Status.FAILED -> R.string.bluetooth_device_failed
            }
            status.isVisible = statusText != null
            statusText?.let(status::setText)
            progress.isVisible = row.status == DeviceRow.Status.CONNECTING

            // Re-tapping the device that is connecting/connected does nothing.
            val busy = row.status == DeviceRow.Status.CONNECTING ||
                row.status == DeviceRow.Status.CONNECTED
            itemView.isEnabled = !busy
            itemView.setOnClickListener { onClick(row.device) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<DeviceRow>() {
        override fun areItemsTheSame(old: DeviceRow, new: DeviceRow) =
            old.device.address == new.device.address

        override fun areContentsTheSame(old: DeviceRow, new: DeviceRow) = old == new
    }
}
