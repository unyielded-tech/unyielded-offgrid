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
        fun onVoiceReceived(bytes: ByteArray)
        fun onImageReceived(bytes: ByteArray)
        fun onPeerIdentity(shortId: String, displayName: String)
        fun onError(message: String)
        fun onHandshakeProgress(step: String)
    }

    companion object {
        private const val TAG = "OffgridTransport"
        val SERVICE_UUID: UUID = UUID.fromString("7f4a1001-6a2b-4e19-9c31-854105000001")
        private const val SERVICE_NAME = "UNYIELDED OFFGRID"
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
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
    private var myShortId: String? = null
    private var myDisplayName: String? = null

    fun setMyIdentity(shortId: String, displayName: String) {
        myShortId = shortId
        myDisplayName = displayName
    }

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
                        listener.onStatus(if (count == 0) "Scan finished. No devices found." else "Scan finished. $count device(s) found.")
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        val bt = adapter ?: run { post { listener.onError("Bluetooth not supported") }; return }
        if (!bt.isEnabled) { post { listener.onError("Turn on Bluetooth first") }; return }
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
        try { adapter?.cancelDiscovery() } catch (_: Exception) {}
        scanning.set(false)
        post { listener.onScanning(false) }
        post { listener.onStatus("Scan stopped") }
    }

    @SuppressLint("MissingPermission")
    fun startListening() {
        val bt = adapter ?: run { post { listener.onError("Bluetooth not supported") }; return }
        if (!bt.isEnabled) { post { listener.onError("Turn on Bluetooth first") }; return }
        if (connected.get()) { post { listener.onError("Already connected") }; return }
        if (serverRunning.get()) { post { listener.onError("Already waiting") }; return }

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
                    post { listener.onStatus("Listener started. Make phone discoverable.") }
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
                if (serverRunning.get()) post { listener.onStatus("Listener stopped") }
                serverRunning.set(false)
                post { listener.onListening(false) }
            } catch (e: SecurityException) {
                serverRunning.set(false)
                post { listener.onListening(false) }
                post { listener.onError("Bluetooth permission required") }
            } catch (e: Exception) {
                serverRunning.set(false)
                post { listener.onListening(false) }
                post { listener.onError("Could not start host: ${e.message}") }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        val bt = adapter ?: return
        if (!bt.isEnabled) { post { listener.onError("Turn on Bluetooth first") }; return }
        if (connected.get()) { post { listener.onError("Disconnect first") }; return }

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
                post { listener.onError("Connection failed. Other phone must be in Host mode.") }
            }
        }
    }

    private fun establishSecureSession(socket: BluetoothSocket) {
        if (!socket.isConnected) {
            try { socket.close() } catch (_: Exception) {}
            return
        }
        activeSocket.set(socket)
        post { listener.onHandshakeProgress("Generating keys…") }

        try {
            val keyPair = SessionCrypto.generateEphemeralKeyPair()
            myKeyPair = keyPair
            val out = socket.outputStream
            val input = socket.inputStream

            post { listener.onHandshakeProgress("Exchanging keys…") }
            OffgridProtocol.writeHandshakePubkey(out, keyPair.publicKeyEncoded)

            val peerFrame = OffgridProtocol.readFrame(input)
            if (peerFrame !is OffgridProtocol.Frame.HandshakePubkey) {
                throw OffgridProtocol.ProtocolException("Expected handshake key")
            }

            post { listener.onHandshakeProgress("Deriving session…") }
            val keys = SessionCrypto.deriveSessionKeys(
                keyPair.privateKey, peerFrame.publicKey, keyPair.publicKeyEncoded, isInitiator
            )
            sessionKeys.set(keys)

            // Send our identity
            val sid = myShortId
            val dname = myDisplayName
            if (sid != null && dname != null) {
                OffgridProtocol.writeIdentityAnnounce(out, sid, dname)
            }

            val peerName = try {
                @SuppressLint("MissingPermission")
                socket.remoteDevice.name ?: "Connected device"
            } catch (_: Exception) { "Connected device" }

            connected.set(true)
            post { listener.onConnected(peerName, keys.fingerprint, keys.fingerprintFull) }
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
                    is OffgridProtocol.Frame.EncryptedText -> {
                        val keys = sessionKeys.get() ?: break
                        try {
                            val plain = SessionCrypto.decrypt(
                                SessionCrypto.EncryptedFrame(frame.nonce, frame.ciphertext), keys.encryptionKey
                            )
                            post { listener.onMessageReceived(String(plain, Charsets.UTF_8)) }
                        } catch (e: Exception) {
                            post { listener.onError("Message auth failed") }
                            disconnect("Auth failure")
                            break
                        }
                    }
                    is OffgridProtocol.Frame.EncryptedVoice -> {
                        val keys = sessionKeys.get() ?: break
                        try {
                            val plain = SessionCrypto.decrypt(
                                SessionCrypto.EncryptedFrame(frame.nonce, frame.ciphertext), keys.encryptionKey
                            )
                            post { listener.onVoiceReceived(plain) }
                        } catch (e: Exception) {
                            post { listener.onError("Voice auth failed") }
                        }
                    }
                    is OffgridProtocol.Frame.EncryptedImage -> {
                        val keys = sessionKeys.get() ?: break
                        try {
                            val plain = SessionCrypto.decrypt(
                                SessionCrypto.EncryptedFrame(frame.nonce, frame.ciphertext), keys.encryptionKey
                            )
                            post { listener.onImageReceived(plain) }
                        } catch (e: Exception) {
                            post { listener.onError("Image auth failed") }
                        }
                    }
                    is OffgridProtocol.Frame.IdentityAnnounce -> {
                        post { listener.onPeerIdentity(frame.shortId, frame.displayName) }
                    }
                    is OffgridProtocol.Frame.Disconnect -> {
                        disconnect("Peer disconnected")
                        break
                    }
                    is OffgridProtocol.Frame.ProtocolError -> {
                        post { listener.onError("Peer: ${frame.message}") }
                    }
                    is OffgridProtocol.Frame.HandshakePubkey -> {
                        post { listener.onError("Unexpected handshake") }
                        disconnect("Protocol violation")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            if (connected.get()) Log.d(TAG, "Read ended: ${e.message}")
        } finally {
            if (activeSocket.get() === socket) {
                closeActiveSocket()
                if (connected.getAndSet(false)) post { listener.onDisconnected("Connection closed") }
            }
        }
    }

    fun sendMessage(text: String) {
        sendEncrypted(text.toByteArray(Charsets.UTF_8), OffgridProtocol.Type.ENCRYPTED_TEXT) {
            post { listener.onMessageSent(text) }
        }
    }

    fun sendVoice(bytes: ByteArray) {
        sendEncrypted(bytes, OffgridProtocol.Type.ENCRYPTED_VOICE) {
            post { listener.onStatus("Voice message sent") }
        }
    }

    fun sendImage(bytes: ByteArray) {
        sendEncrypted(bytes, OffgridProtocol.Type.ENCRYPTED_IMAGE) {
            post { listener.onStatus("Image sent") }
        }
    }

    private fun sendEncrypted(plaintext: ByteArray, type: Byte, onSuccess: () -> Unit) {
        if (!connected.get()) { post { listener.onError("Not connected") }; return }
        val keys = sessionKeys.get()
        val socket = activeSocket.get()
        if (keys == null || socket == null || !socket.isConnected) {
            post { listener.onError("No active session") }
            return
        }
        executor.execute {
            try {
                val encrypted = SessionCrypto.encrypt(plaintext, keys.encryptionKey)
                when (type) {
                    OffgridProtocol.Type.ENCRYPTED_TEXT -> OffgridProtocol.writeEncryptedText(socket.outputStream, encrypted)
                    OffgridProtocol.Type.ENCRYPTED_VOICE -> OffgridProtocol.writeEncryptedVoice(socket.outputStream, encrypted)
                    OffgridProtocol.Type.ENCRYPTED_IMAGE -> OffgridProtocol.writeEncryptedImage(socket.outputStream, encrypted)
                }
                onSuccess()
            } catch (e: Exception) {
                Log.e(TAG, "Send failed", e)
                post { listener.onError("Failed to send") }
            }
        }
    }

    fun disconnect(reason: String = "Disconnected") {
        serverRunning.set(false)
        connected.set(false)
        try { activeSocket.get()?.outputStream?.let { OffgridProtocol.writeDisconnect(it) } } catch (_: Exception) {}
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
        try { activeSocket.getAndSet(null)?.close() } catch (_: Exception) {}
        sessionKeys.getAndSet(null)?.clear()
    }

    private fun closeServerSocket() {
        try { serverSocket.getAndSet(null)?.close() } catch (_: Exception) {}
    }

    fun shutdown() {
        disconnect("App closed")
        if (receiverRegistered) {
            try { context.unregisterReceiver(discoveryReceiver) } catch (_: Exception) {}
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

    private fun post(block: () -> Unit) = mainHandler.post(block)

    @SuppressLint("MissingPermission")
    fun safeDeviceName(device: BluetoothDevice): String {
        return try { device.name?.takeIf { it.isNotBlank() } ?: "Unknown device" } catch (_: SecurityException) { "Bluetooth device" }
    }

    @SuppressLint("MissingPermission")
    fun safeDeviceAddress(device: BluetoothDevice): String {
        return try { device.address ?: "—" } catch (_: SecurityException) { "—" }
    }
}
