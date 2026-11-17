package com.example.mobileapp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Standard Serial Port Profile UUID – the RPi server should advertise this (or listen on channel 1). */
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/** RFCOMM channel used when the SDP lookup fails (RPi server without a registered SDP record). */
private const val FALLBACK_CHANNEL = 1

/**
 * Bluetooth Classic (RFCOMM) client. The phone connects to the Raspberry Pi,
 * which runs an RFCOMM server and waits for data.
 */
@SuppressLint("MissingPermission") // permissions are checked in the UI before any call
class BluetoothClient {
    var connectedDeviceName by mutableStateOf<String?>(null)
        private set
    var isBusy by mutableStateOf(false)
        private set
    val log = mutableStateListOf<String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var socket: BluetoothSocket? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(message: String) {
        log.add(0, "[${timeFormat.format(Date())}] $message")
    }

    fun connect(device: BluetoothDevice) {
        if (isBusy) return
        disconnect()
        isBusy = true
        val name = device.name ?: device.address
        scope.launch {
            log("Connecting to $name (${device.address})…")
            try {
                val s = openSocket(device)
                socket = s
                connectedDeviceName = name
                log("Connected to $name")
                startReading(s)
            } catch (e: Exception) {
                log("Connection failed: ${e.message}")
            } finally {
                isBusy = false
            }
        }
    }

    private suspend fun openSocket(device: BluetoothDevice): BluetoothSocket {
        // 1) Standard way: SDP lookup of the SPP UUID
        try {
            return withContext(Dispatchers.IO) {
                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                try {
                    s.connect(); s
                } catch (e: IOException) {
                    runCatching { s.close() }; throw e
                }
            }
        } catch (e: IOException) {
            log("SDP connect failed (${e.message}), trying RFCOMM channel $FALLBACK_CHANNEL…")
        }

        // 2) Fallback: connect directly to a fixed RFCOMM channel (hidden API, via reflection)
        return withContext(Dispatchers.IO) {
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            val s = method.invoke(device, FALLBACK_CHANNEL) as BluetoothSocket
            try {
                s.connect(); s
            } catch (e: IOException) {
                runCatching { s.close() }; throw e
            }
        }
    }

    /** Reads newline-terminated responses from the RPi (e.g. an ACK) and logs them. */
    private fun startReading(s: BluetoothSocket) {
        scope.launch {
            try {
                val reader = s.inputStream.bufferedReader(Charsets.UTF_8)
                while (true) {
                    val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break
                    log("Received: $line")
                }
            } catch (_: IOException) {
                // socket closed
            }
            if (socket === s) {
                socket = null
                connectedDeviceName = null
                runCatching { s.close() }
                log("Connection closed by remote device")
            }
        }
    }

    fun send(text: String) {
        val s = socket ?: run { log("Not connected"); return }
        scope.launch {
            val bytes = (text + "\n").toByteArray(Charsets.UTF_8)
            try {
                withContext(Dispatchers.IO) {
                    s.outputStream.write(bytes)
                    s.outputStream.flush()
                }
                log("Sent ${bytes.size} bytes")
            } catch (e: IOException) {
                log("Send failed: ${e.message}")
                disconnect()
            }
        }
    }

    fun disconnect() {
        val s = socket ?: return
        socket = null
        connectedDeviceName = null
        runCatching { s.close() }
        log("Disconnected")
    }

    fun release() {
        disconnect()
        scope.cancel()
    }
}
