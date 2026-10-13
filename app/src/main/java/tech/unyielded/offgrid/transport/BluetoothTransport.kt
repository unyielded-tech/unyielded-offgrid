package tech.unyielded.offgrid.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import tech.unyielded.offgrid.crypto.OffgridProtocol
import tech.unyielded.offgrid.crypto.SessionCrypto
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class BluetoothTransport(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onStatus(message: String)
        fun onDevicesUpdated(devices: List<BluetoothDevice>)
        fun onScanning(scanning: Boolean)
        fun onListening(listening: Boolean)
        fun onConnected(peerName: String, fingerprint: String, fingerprintFull: String)
        fun onDisconnected(reason: String)
        fun onMessageReceived(text: String)
        fun onMessageSent(text: String)
        fun onError(message: String)
        fun onHandshakeProgress(step: String)
    }

    companion object {
        private const val TAG = "OffgridTransport"
        val SERVICE_UUID: UUID = UUID.fromString("7f4a1001-6a2b-4e19-9c31-854105000001")
        private const val SERVICE_NAME = "UNYIELDED OFFGRID"
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val devices = mutableListOf<BluetoothDevice>()
    private val devicesLock = Any()

    private val activeSocket = AtomicReference<BluetoothSocket?>(null)
    private val serverSocket = AtomicReference<BluetoothServerSocket?>(null)
    private val sessionKeys = AtomicReference<SessionCrypto.SessionKeys?>(null)

    private val serverRunning = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val scanning = AtomicBoolean(false)
    private var receiverRegistered = false

    private var myKeyPair: SessionCrypto.KeyPairBytes? = null
    private var isInitiator = false

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        synchronized(devicesLock) {
                            if (devices.none { it.address == device.address }) {
                                devices.add(device)
                                post { listener.onDevicesUpdated(devices.toList()) }
                            }
                        }
                        post { listener.onStatus("Discovering… ${devices.size} found") }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    scanning.set(true)
                    post { listener.onScanning(true) }
                    post { listener.onStatus("Bluetooth discovery running…") }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    scanning.set(false)
                    post { listener.onScanning(false) }
                    val count = synchronized(devicesLock) { devices.size }
                    post {
                        listener.onStatus(
                            if (count == 0) "Scan finished. No devices found."
                            else "Scan finished. $count device(s) found."
                        )
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        val bt = adapter ?: run {
            post { listener.onError("This device does not support Bluetooth") }
            return
        }
        if (!bt.isEnabled) {
            post { listener.onError("Turn on Bluetooth first") }
            return
        }

        executor.execute {
            try {
                registerReceiverIfNeeded()
                if (bt.isDiscovering) bt.cancelDiscovery()

                synchronized(devicesLock) {
                    devices.clear()
                    post { listener.onDevicesUpdated(emptyList()) }
                }

                scanning.set(true)
                post { listener.onScanning(true) }
                post { listener.onStatus("Starting discovery…") }

                if (!bt.startDiscovery()) {
                    scanning.set(false)
                    post { listener.onScanning(false) }
                    post { listener.onError("Android could not start discovery") }
                }
            } catch (e: SecurityException) {
                scanning.set(false)
                post { listener.onScanning(false) }
                post { listener.onError("Bluetooth permission denied") }
            } catch (e: Exception) {
                scanning.set(false)
                post { listener.onScanning(false) }
                post { listener.onError("Discovery failed: ${e.message}") }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        try {
            adapter?.cancelDiscovery()
        } catch (_: Exception) {}
        scanning.set(false)
        post { listener.onScanning(false) }
        post { listener.onStatus("Scan stopped") }
    }

    @SuppressLint("MissingPermission")
    fun startListening() {
        val bt = adapter ?: run {
            post { listener.onError("Bluetooth not supported") }
            return
        }
        if (!bt.isEnabled) {
            post { listener.onError("Turn on Bluetooth first") }
            return
        }
        if (connected.get()) {
            post { listener.onError("Already connected") }
            return
        }
        if (serverRunning.get()) {
            post { listener.onError("Already waiting for a connection") }
            return
        }

        executor.execute {
            try {
                registerReceiverIfNeeded()
                val server = bt.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
                serverSocket.set(server)
                serverRunning.set(true)
                post { listener.onListening(true) }
                post { listener.onStatus("Waiting for another OFFGRID phone…") }

                try {
                    val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                        putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    post { listener.onStatus("Listener started. Make sure this phone is discoverable.") }
                }

                val incoming = server.accept()
                if (incoming != null && serverRunning.get()) {
                    serverRunning.set(false)
                    post { listener.onListening(false) }
                    closeServerSocket()
                    isInitiator = false
                    establishSecureSession(incoming)
                } else {
                    incoming?.close()
                }
            } catch (e: IOException) {
                if (serverRunning.get()) {
                    post { listener.onStatus("Listener stopped") }
                }
                serverRunning.set(false)
                post { listener.onListening(false) }
            } catch (e: SecurityException) {
                serverRunning.set(false)
                post { listener.onListening(false) }
                post { listener.onError("Bluetooth permission required") }
            } catch (e: Exception) {
                serverRunning.set(false)
                post { listener.onListening(false) }
                post { listener.onError("Could not start host mode: ${e.message}") }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        val bt = adapter ?: return
        if (!bt.isEnabled) {
            post { listener.onError("Turn on Bluetooth first") }
            return
        }
        if (connected.get()) {
            post { listener.onError("Disconnect first") }
            return
        }

        val name = try { device.name ?: "Unknown" } catch (_: Exception) { "Unknown" }
        post { listener.onStatus("Connecting to $name…") }
        stopDiscovery()

        executor.execute {
            var socket: BluetoothSocket? = null
            try {
                socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                socket.connect()
                isInitiator = true
                establishSecureSession(socket)
            } catch (e: Exception) {
                try { socket?.close() } catch (_: Exception) {}
                post {
                    listener.onError(
                        "Connection failed. On the other phone open Host mode and approve discoverability."
                    )
                }
            }
        }
    }

    private fun establishSecureSession(socket: BluetoothSocket) {
        if (!socket.isConnected) {
            try { socket.close() } catch (_: Exception) {}
            return
        }

        activeSocket.set(socket)
        post { listener.onHandshakeProgress("Generating ephemeral keys…") }

        try {
            val keyPair = SessionCrypto.generateEphemeralKeyPair()
            myKeyPair = keyPair

            val out = socket.outputStream
            val input = socket.inputStream

            post { listener.onHandshakeProgress("Exchanging public keys…") }
            OffgridProtocol.writeHandshakePubkey(out, keyPair.publicKeyEncoded)

            val peerFrame = OffgridProtocol.readFrame(input)
            if (peerFrame !is OffgridProtocol.Frame.HandshakePubkey) {
                throw OffgridProtocol.ProtocolException("Expected handshake public key, got $peerFrame")
            }

            post { listener.onHandshakeProgress("Deriving session keys…") }
            val keys = SessionCrypto.deriveSessionKeys(
                myPrivate = keyPair.privateKey,
                peerPublicEncoded = peerFrame.publicKey,
                myPublicEncoded = keyPair.publicKeyEncoded,
                isInitiator = isInitiator
            )
            sessionKeys.set(keys)

            val peerName = try {
                @SuppressLint("MissingPermission")
                socket.remoteDevice.name ?: "Connected device"
            } catch (_: Exception) {
                "Connected device"
            }

            connected.set(true)
            post {
                listener.onConnected(peerName, keys.fingerprint, keys.fingerprintFull)
            }
            post { listener.onStatus("Secure session established") }

            executor.execute { readLoop(socket) }

        } catch (e: Exception) {
            Log.e(TAG, "Handshake failed", e)
            closeActiveSocket()
            post { listener.onError("Secure handshake failed: ${e.message}") }
            post { listener.onDisconnected("Handshake failed") }
        }
    }

    private fun readLoop(socket: BluetoothSocket) {
        try {
            val input = socket.inputStream
            while (!Thread.currentThread().isInterrupted && connected.get()) {
                val frame = OffgridProtocol.readFrame(input)
                when (frame) {
                    is OffgridProtocol.Frame.EncryptedMessage -> {
                        val keys = sessionKeys.get() ?: break
                        try {
                            val plaintext = SessionCrypto.decrypt(
                                SessionCrypto.EncryptedFrame(frame.nonce, frame.ciphertext),
                                keys.encryptionKey
                            )
                            val text = String(plaintext, Charsets.UTF_8)
                            post { listener.onMessageReceived(text) }
                        } catch (e: Exception) {
                            Log.w(TAG, "Decryption failed — possible tampering", e)
                            post { listener.onError("Message authentication failed — connection may be compromised") }
                            disconnect("Authentication failure")
                            break
                        }
                    }
                    is OffgridProtocol.Frame.Disconnect -> {
                        disconnect("Peer disconnected")
                        break
                    }
                    is OffgridProtocol.Frame.ProtocolError -> {
                        post { listener.onError("Peer reported: ${frame.message}") }
                    }
                    is OffgridProtocol.Frame.HandshakePubkey -> {
                        post { listener.onError("Unexpected handshake frame") }
                        disconnect("Protocol violation")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            if (connected.get()) {
                Log.d(TAG, "Read loop ended: ${e.message}")
            }
        } finally {
            if (activeSocket.get() === socket) {
                closeActiveSocket()
                if (connected.getAndSet(false)) {
                    post { listener.onDisconnected("Connection closed") }
                }
            }
        }
    }

    fun sendMessage(text: String) {
        if (!connected.get()) {
            post { listener.onError("Not connected") }
            return
        }
        val keys = sessionKeys.get()
        val socket = activeSocket.get()
        if (keys == null || socket == null || !socket.isConnected) {
            post { listener.onError("No active secure session") }
            return
        }

        executor.execute {
            try {
                val plaintext = text.toByteArray(Charsets.UTF_8)
                val encrypted = SessionCrypto.encrypt(plaintext, keys.encryptionKey)
                OffgridProtocol.writeEncryptedMessage(socket.outputStream, encrypted)
                post { listener.onMessageSent(text) }
            } catch (e: Exception) {
                Log.e(TAG, "Send failed", e)
                post { listener.onError("Failed to send message") }
            }
        }
    }

    fun disconnect(reason: String = "Disconnected") {
        serverRunning.set(false)
        connected.set(false)

        try {
            activeSocket.get()?.outputStream?.let {
                OffgridProtocol.writeDisconnect(it)
            }
        } catch (_: Exception) {}

        closeServerSocket()
        closeActiveSocket()

        try { adapter?.cancelDiscovery() } catch (_: Exception) {}
        scanning.set(false)

        sessionKeys.getAndSet(null)?.clear()
        myKeyPair = null

        post { listener.onListening(false) }
        post { listener.onScanning(false) }
        post { listener.onDisconnected(reason) }
        post { listener.onStatus(reason) }
    }

    private fun closeActiveSocket() {
        try {
            activeSocket.getAndSet(null)?.close()
        } catch (_: Exception) {}
        sessionKeys.getAndSet(null)?.clear()
    }

    private fun closeServerSocket() {
        try {
            serverSocket.getAndSet(null)?.close()
        } catch (_: Exception) {}
    }

    fun shutdown() {
        disconnect("App closed")
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(discoveryReceiver)
            } catch (_: Exception) {}
            receiverRegistered = false
        }
        executor.shutdownNow()
    }

    private fun registerReceiverIfNeeded() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(discoveryReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }

    @SuppressLint("MissingPermission")
    fun safeDeviceName(device: BluetoothDevice): String {
        return try {
            device.name?.takeIf { it.isNotBlank() } ?: "Unknown device"
        } catch (_: SecurityException) {
            "Bluetooth device"
        }
    }

    @SuppressLint("MissingPermission")
    fun safeDeviceAddress(device: BluetoothDevice): String {
        return try {
            device.address ?: "—"
        } catch (_: SecurityException) {
            "—"
        }
    }
}
