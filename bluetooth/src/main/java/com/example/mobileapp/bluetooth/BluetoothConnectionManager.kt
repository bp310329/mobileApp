package com.example.mobileapp.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Holds at most one classic Bluetooth connection over the Serial Port Profile (RFCOMM), the
 * profile used by serial modules such as HC-05 or an ESP32 running BluetoothSerial.
 *
 * Work runs in [scope]; the owner must call [disconnect] when it no longer needs the connection.
 */
class BluetoothConnectionManager(context: Context, private val scope: CoroutineScope) {

    sealed interface State {
        data object Disconnected : State
        data class Connecting(val device: BluetoothDeviceInfo) : State
        data class Connected(val device: BluetoothDeviceInfo) : State
        data class Failed(val device: BluetoothDeviceInfo, val reason: String?) : State
    }

    private val context = context.applicationContext

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    /** Raw bytes received from the connected device. */
    val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    private val lock = Any()
    /** Bumped on every connect/disconnect so a superseded attempt can't overwrite [state]. */
    private var generation = 0
    private var socket: BluetoothSocket? = null
    private var job: Job? = null

    /** Connects to [device], dropping any current connection first. Progress is in [state]. */
    @SuppressLint("MissingPermission") // checked via BluetoothAccess.hasPermissions
    fun connect(device: BluetoothDeviceInfo) {
        val gen = synchronized(lock) {
            closeLocked()
            _state.value = State.Connecting(device)
            ++generation
        }
        val adapter = BluetoothAccess.adapter(context)
        if (adapter == null || !BluetoothAccess.hasPermissions(context)) {
            update(gen, State.Failed(device, "Bluetooth unavailable or permission missing"))
            return
        }

        val newJob = scope.launch(Dispatchers.IO) {
            val connected = try {
                // Discovery heavily slows down connecting, so make sure it is off.
                adapter.cancelDiscovery()
                openSocket(gen, adapter.getRemoteDevice(device.address)) ?: return@launch
            } catch (e: Exception) {
                // IOException, SecurityException, or reflection errors from the fallback.
                Log.w(TAG, "Connecting to ${device.address} failed", e)
                fail(gen, device, e)
                return@launch
            }

            update(gen, State.Connected(device.copy(isPaired = true)))
            readUntilClosed(connected)
            // The remote side closed the connection or the link was lost.
            update(gen, State.Disconnected)
            release(gen)
        }
        synchronized(lock) { if (gen == generation) job = newJob }
    }

    /** Closes the current connection, or cancels an attempt in progress. */
    fun disconnect() {
        synchronized(lock) {
            closeLocked()
            generation++
            _state.value = State.Disconnected
        }
    }

    /** Writes [data] to the connected device; returns false when not connected or on I/O error. */
    suspend fun send(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val current = synchronized(lock) { socket?.takeIf { _state.value is State.Connected } }
            ?: return@withContext false
        try {
            current.outputStream.apply {
                write(data)
                flush()
            }
            true
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Opens an RFCOMM socket to [device]. Tries the standard SDP lookup of the SPP UUID first and,
     * if that fails, connects straight to RFCOMM channel [FALLBACK_CHANNEL]. The fallback is needed
     * for servers that don't register an SDP record (e.g. a simple Raspberry Pi RFCOMM server).
     *
     * Blocking. Returns null when this attempt was superseded by connect()/disconnect().
     */
    @SuppressLint("MissingPermission") // checked in connect()
    private fun openSocket(gen: Int, device: BluetoothDevice): BluetoothSocket? {
        try {
            return tryConnect(gen, device.createRfcommSocketToServiceRecord(SPP_UUID))
        } catch (e: IOException) {
            if (!isCurrent(gen)) return null
            Log.i(TAG, "SDP connect failed (${e.message}); trying RFCOMM channel $FALLBACK_CHANNEL")
        }
        // Hidden API, reached via reflection; the standard workaround for SDP-less servers.
        val fallback = device.javaClass
            .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            .invoke(device, FALLBACK_CHANNEL) as BluetoothSocket
        return tryConnect(gen, fallback)
    }

    /** Connects [socket], registered so disconnect() can abort it. Null if superseded. */
    private fun tryConnect(gen: Int, socket: BluetoothSocket): BluetoothSocket? {
        if (!register(gen, socket)) return null
        try {
            socket.connect() // blocks; unblocked by close() from disconnect()
            return socket
        } catch (e: IOException) {
            socket.closeQuietly()
            throw e
        }
    }

    private fun isCurrent(gen: Int) = synchronized(lock) { gen == generation }

    private fun readUntilClosed(socket: BluetoothSocket) {
        val buffer = ByteArray(1024)
        try {
            val input = socket.inputStream
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) _incoming.tryEmit(buffer.copyOf(count))
            }
        } catch (_: IOException) {
            // Socket closed locally or connection lost.
        }
    }

    private fun fail(gen: Int, device: BluetoothDeviceInfo, error: Exception) {
        update(gen, State.Failed(device, error.message))
        release(gen)
    }

    /** Stores [newSocket] as current if [gen] is still current; otherwise closes it. */
    private fun register(gen: Int, newSocket: BluetoothSocket): Boolean = synchronized(lock) {
        if (gen == generation) {
            socket = newSocket
            true
        } else {
            newSocket.closeQuietly()
            false
        }
    }

    private fun update(gen: Int, newState: State) = synchronized(lock) {
        if (gen == generation) _state.value = newState
    }

    private fun release(gen: Int) = synchronized(lock) {
        if (gen == generation) {
            socket?.closeQuietly()
            socket = null
            job = null
        }
    }

    private fun closeLocked() {
        job?.cancel()
        job = null
        socket?.closeQuietly()
        socket = null
    }

    private fun BluetoothSocket.closeQuietly() {
        try {
            close()
        } catch (_: IOException) {
        }
    }

    private companion object {
        /** Well-known Serial Port Profile service UUID. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** RFCOMM channel used when the SDP lookup fails. */
        const val FALLBACK_CHANNEL = 1

        const val TAG = "BluetoothConnection"
    }
}
