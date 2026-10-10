package tech.unyielded.offgrid

import android.Manifest
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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

private val Ink = Color(0xFF080A0D)
private val Panel = Color(0xFF12161B)
private val PanelLight = Color(0xFF1A2027)
private val Lime = Color(0xFFC5FF54)
private val White = Color(0xFFF4F6F2)
private val Muted = Color(0xFF929BA6)
private val Border = Color(0xFF29313A)
private val Red = Color(0xFFFF7373)

private val OFFGRID_UUID: UUID =
    UUID.fromString("7f4a1001-6a2b-4e19-9c31-854105000001")

private data class ChatMessage(
    val text: String,
    val mine: Boolean,
    val system: Boolean = false
)

class MainActivity : ComponentActivity() {

    private val devices = mutableStateListOf<BluetoothDevice>()
    private val messages = mutableStateListOf<ChatMessage>()

    private var status by mutableStateOf("Ready to connect")
    private var scanning by mutableStateOf(false)
    private var listening by mutableStateOf(false)
    private var connected by mutableStateOf(false)
    private var peerName by mutableStateOf("")
    private var draft by mutableStateOf("")
    private var receiverRegistered = false

    private val executor = Executors.newCachedThreadPool()
    private val outputLock = Any()

    @Volatile
    private var activeSocket: BluetoothSocket? = null

    @Volatile
    private var serverSocket: BluetoothServerSocket? = null

    @Volatile
    private var serverRunning = false

    private var writer: PrintWriter? = null
    private var pendingPermissionAction: (() -> Unit)? = null

    private val bluetoothManager by lazy {
        getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    private val adapter: BluetoothAdapter?
        get() = bluetoothManager.adapter

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            if (result.isNotEmpty() && result.values.all { it }) {
                val action = pendingPermissionAction
                pendingPermissionAction = null
                action?.invoke()
            } else {
                pendingPermissionAction = null
                status = "Bluetooth permissions are required."
            }
        }

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE
                            )
                        }

                    if (device != null) {
                        try {
                            if (devices.none { it.address == device.address }) {
                                devices.add(device)
                            }
                            status = "Discovering devices… ${devices.size} found"
                        } catch (_: SecurityException) {
                            status = "Bluetooth permission is required."
                        }
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    scanning = true
                    status = "Real Bluetooth discovery is running…"
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    scanning = false
                    status = if (devices.isEmpty()) {
                        "Scan finished. No devices discovered."
                    } else {
                        "Scan finished. ${devices.size} device(s) found."
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Lime,
                    onPrimary = Ink,
                    background = Ink,
                    surface = Panel,
                    onSurface = White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Ink
                ) {
                    OffgridScreen()
                }
            }
        }
    }

    @Composable
    private fun OffgridScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(17.dp)
        ) {
            // Brand
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "UNYIELDED",
                        color = Lime,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 4.sp
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "// OFFGRID",
                        color = White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-1).sp
                    )
                    Text(
                        "PRIVATE. LOCAL. INDEPENDENT.",
                        color = Muted,
                        fontSize = 9.sp,
                        letterSpacing = 1.5.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .background(PanelLight, RoundedCornerShape(9.dp))
                        .border(1.dp, Border, RoundedCornerShape(9.dp))
                        .padding(horizontal = 11.dp, vertical = 9.dp)
                ) {
                    Text(
                        "BETA 02",
                        color = Lime,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Hero
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(21.dp),
                colors = CardDefaults.cardColors(containerColor = Panel)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Border, RoundedCornerShape(21.dp))
                        .padding(19.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "YOUR NETWORK. YOUR RULES.",
                        color = Lime,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                    Text(
                        "Communication\nwithout the cloud.",
                        color = White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = 32.sp
                    )
                    Text(
                        "Connect nearby Android devices and exchange messages over Bluetooth. No internet connection is needed.",
                        color = Muted,
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )
                }
            }

            // Requested banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(17.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF171C16)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            Color(0xFF465438),
                            RoundedCornerShape(17.dp)
                        )
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(41.dp)
                            .background(Lime, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "A",
                            color = Ink,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "AYAZ SUPPORTS CJP",
                            color = Lime,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "A message from the creator",
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }
                    Text("✦", color = Lime, fontSize = 20.sp)
                }
            }

            // Connection controls
            SectionTitle("CONNECTION")

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(15.dp),
                colors = CardDefaults.cardColors(containerColor = Panel)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(15.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (connected) Lime else Muted,
                                    CircleShape
                                )
                        )
                        Spacer(Modifier.size(9.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (connected) "CONNECTED" else "NOT CONNECTED",
                                color = if (connected) Lime else White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                if (connected) peerName else status,
                                color = Muted,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Button(
                        onClick = {
                            runWithBluetoothPermissions {
                                startListening()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(49.dp),
                        enabled = !listening && !connected,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PanelLight,
                            contentColor = White,
                            disabledContainerColor = PanelLight,
                            disabledContentColor = Muted
                        )
                    ) {
                        Text(
                            if (listening) "WAITING FOR CONNECTION…" else "HOST / WAIT FOR PHONE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        "Host mode starts a Bluetooth listener and asks Android to make this phone discoverable.",
                        color = Muted,
                        fontSize = 11.sp,
                        lineHeight = 17.sp
                    )
                }
            }

            // Scanner
            SectionTitle("NEARBY DEVICES")

            Button(
                onClick = {
                    if (scanning) {
                        stopDiscovery()
                    } else {
                        runWithBluetoothPermissions {
                            startDiscovery()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(51.dp),
                enabled = !connected,
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Lime,
                    contentColor = Ink
                )
            ) {
                Text(
                    if (scanning) "STOP SCANNING" else "SCAN NEARBY DEVICES  →",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Text(status, color = Muted, fontSize = 12.sp)

            if (devices.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(15.dp),
                    colors = CardDefaults.cardColors(containerColor = Panel)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("⌁", color = Lime, fontSize = 32.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Nothing on the radar yet",
                            color = White,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Enable Bluetooth. The other phone may need to be discoverable.",
                            color = Muted,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            } else {
                devices.forEach { device ->
                    DeviceRow(
                        device = device,
                        onConnect = {
                            runWithBluetoothPermissions {
                                connectToDevice(device)
                            }
                        }
                    )
                }
            }

            // Chat
            SectionTitle("OFFLINE CHAT")

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(17.dp),
                colors = CardDefaults.cardColors(containerColor = Panel)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        if (connected) "Chat with $peerName" else "Connect to start chatting",
                        color = White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )

                    if (messages.isEmpty()) {
                        Text(
                            "Your messages will appear here after a Bluetooth connection is established.",
                            color = Muted,
                            fontSize = 12.sp,
                            lineHeight = 19.sp
                        )
                    } else {
                        messages.forEach { message ->
                            MessageBubble(message)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(Ink, RoundedCornerShape(12.dp))
                                .border(1.dp, Border, RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 13.dp)
                        ) {
                            if (draft.isEmpty()) {
                                Text("Write a message…", color = Muted, fontSize = 13.sp)
                            }
                            BasicTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                enabled = connected,
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    color = White,
                                    fontSize = 13.sp
                                ),
                                singleLine = true
                            )
                        }

                        Button(
                            onClick = { sendMessage() },
                            enabled = connected && draft.isNotBlank(),
                            shape = RoundedCornerShape(11.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Lime,
                                contentColor = Ink
                            )
                        ) {
                            Text("SEND", fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                        }
                    }

                    Button(
                        onClick = { disconnect() },
                        enabled = connected || listening,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(11.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PanelLight,
                            contentColor = White
                        )
                    ) {
                        Text("DISCONNECT", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }

                    Text(
                        "V2 prototype: messages use the Bluetooth connection. App-level end-to-end encryption has not been added yet.",
                        color = Muted,
                        fontSize = 10.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                "OFFLINE FIRST  •  BUILT BY UNYIELDED",
                modifier = Modifier.fillMaxWidth(),
                color = Muted,
                fontSize = 9.sp,
                letterSpacing = 1.1.sp
            )
        }
    }

    @Composable
    private fun SectionTitle(title: String) {
        Text(
            title,
            color = White,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.3.sp
        )
    }

    @Composable
    private fun DeviceRow(
        device: BluetoothDevice,
        onConnect: () -> Unit
    ) {
        val name = safeDeviceName(device)
        val address = safeDeviceAddress(device)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Panel)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Border, RoundedCornerShape(14.dp))
                    .padding(13.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    name,
                    color = White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(address, color = Muted, fontSize = 10.sp)

                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PanelLight,
                        contentColor = Lime
                    )
                ) {
                    Text("CONNECT TO DEVICE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    @Composable
    private fun MessageBubble(message: ChatMessage) {
        if (message.system) {
            Text(
                message.text,
                modifier = Modifier.fillMaxWidth(),
                color = Muted,
                fontSize = 10.sp
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (message.mine) {
                    Arrangement.End
                } else {
                    Arrangement.Start
                }
            ) {
                Column(
                    modifier = Modifier
                        .background(
                            if (message.mine) Color(0xFF26351B) else PanelLight,
                            RoundedCornerShape(13.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    Text(
                        if (message.mine) "YOU" else "PEER",
                        color = Lime,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        message.text,
                        color = White,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
            }
        }
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun runWithBluetoothPermissions(action: () -> Unit) {
        if (adapter == null) {
            status = "This phone does not support Bluetooth."
            return
        }

        if (!hasPermissions()) {
            pendingPermissionAction = action
            permissionLauncher.launch(requiredPermissions())
        } else {
            action()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        val bluetooth = adapter ?: return

        try {
            if (!bluetooth.isEnabled) {
                status = "Turn on Bluetooth in Android settings first."
                return
            }

            registerDiscoveryReceiverIfNeeded()

            if (bluetooth.isDiscovering) {
                bluetooth.cancelDiscovery()
            }

            devices.clear()
            scanning = true
            status = "Starting Bluetooth discovery…"

            if (!bluetooth.startDiscovery()) {
                scanning = false
                status = "Android could not start discovery. Try again."
            }
        } catch (_: SecurityException) {
            scanning = false
            status = "Bluetooth permission was denied."
        } catch (_: Exception) {
            scanning = false
            status = "Bluetooth discovery failed."
        }
    }

    private fun registerDiscoveryReceiverIfNeeded() {
        if (receiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                discoveryReceiver,
                filter,
                Context.RECEIVER_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(discoveryReceiver, filter)
        }

        receiverRegistered = true
    }

    @SuppressLint("MissingPermission")
    private fun stopDiscovery() {
        try {
            adapter?.cancelDiscovery()
        } catch (_: SecurityException) {
            // Permission may have been revoked.
        }

        scanning = false
        status = "Scan stopped."
    }

    @SuppressLint("MissingPermission")
    private fun startListening() {
        val bluetooth = adapter ?: return

        try {
            if (!bluetooth.isEnabled) {
                status = "Turn on Bluetooth first."
                return
            }

            if (connected) {
                status = "Already connected."
                return
            }

            if (serverRunning) {
                status = "Already waiting for a connection."
                return
            }

            registerDiscoveryReceiverIfNeeded()

            val newServer = bluetooth.listenUsingRfcommWithServiceRecord(
                "UNYIELDED OFFGRID",
                OFFGRID_UUID
            )

            serverSocket = newServer
            serverRunning = true
            listening = true
            status = "Waiting for another OFFGRID phone…"

            // Android asks the user before making the phone discoverable.
            try {
                val discoverableIntent = Intent(
                    BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE
                ).apply {
                    putExtra(
                        BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,
                        300
                    )
                }
                startActivity(discoverableIntent)
            } catch (_: Exception) {
                status = "Listener started. Check Bluetooth visibility settings."
            }

            executor.execute {
                try {
                    val incoming = newServer.accept()
                    if (incoming != null && serverRunning) {
                        serverRunning = false
                        listening = false
                        closeServerSocket()
                        establishConnection(incoming)
                    } else {
                        incoming?.close()
                    }
                } catch (e: IOException) {
                    runOnUiThread {
                        if (serverRunning) {
                            status = "Listener stopped: ${e.message ?: "connection error"}"
                        }
                        listening = false
                        serverRunning = false
                    }
                } catch (_: SecurityException) {
                    runOnUiThread {
                        listening = false
                        serverRunning = false
                        status = "Bluetooth permission is required."
                    }
                }
            }
        } catch (e: SecurityException) {
            listening = false
            serverRunning = false
            status = "Bluetooth permission was denied."
        } catch (e: IOException) {
            listening = false
            serverRunning = false
            status = "Could not start the Bluetooth listener."
        } catch (_: Exception) {
            listening = false
            serverRunning = false
            status = "Could not start host mode."
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        val bluetooth = adapter ?: return

        if (!bluetooth.isEnabled) {
            status = "Turn on Bluetooth first."
            return
        }

        if (connected) {
            status = "Disconnect before connecting to another phone."
            return
        }

        status = "Connecting to ${safeDeviceName(device)}…"
        stopDiscovery()

        executor.execute {
            var candidate: BluetoothSocket? = null

            try {
                candidate = device.createRfcommSocketToServiceRecord(OFFGRID_UUID)
                candidate.connect()
                establishConnection(candidate)
            } catch (e: Exception) {
                try {
                    candidate?.close()
                } catch (_: Exception) {
                    // Ignore close failure.
                }

                runOnUiThread {
                    status =
                        "Connection failed. On the other phone, open Host / Wait for Phone and approve discoverability."
                }
            }
        }
    }

    private fun establishConnection(socket: BluetoothSocket) {
        if (!socket.isConnected) {
            try {
                socket.close()
            } catch (_: Exception) {
                // Ignore.
            }
            return
        }

        synchronized(outputLock) {
            activeSocket = socket
            writer = PrintWriter(
                BufferedWriter(
                    OutputStreamWriter(socket.outputStream, Charsets.UTF_8)
                ),
                true
            )
        }

        val remoteName = try {
            @SuppressLint("MissingPermission")
            socket.remoteDevice.name ?: "Connected device"
        } catch (_: Exception) {
            "Connected device"
        }

        runOnUiThread {
            connected = true
            listening = false
            scanning = false
            peerName = remoteName
            status = "Bluetooth connection established."
            messages.add(
                ChatMessage("Connected to $remoteName", mine = false, system = true)
            )
        }

        executor.execute {
            readMessages(socket)
        }
    }

    private fun readMessages(socket: BluetoothSocket) {
        try {
            val reader = BufferedReader(
                InputStreamReader(socket.inputStream, Charsets.UTF_8)
            )

            while (!Thread.currentThread().isInterrupted) {
                val line = reader.readLine() ?: break

                runOnUiThread {
                    messages.add(ChatMessage(line, mine = false))
                }
            }
        } catch (_: IOException) {
            // The peer disconnected or the Bluetooth link ended.
        } catch (_: Exception) {
            // The socket is no longer usable.
        } finally {
            if (activeSocket === socket) {
                closeActiveSocket()

                runOnUiThread {
                    connected = false
                    peerName = ""
                    status = "Bluetooth connection closed."
                    messages.add(
                        ChatMessage(
                            "Connection closed.",
                            mine = false,
                            system = true
                        )
                    )
                }
            }
        }
    }

    private fun sendMessage() {
        val text = draft.trim()
        if (text.isEmpty() || !connected) return

        val currentWriter = writer
        val currentSocket = activeSocket

        if (currentWriter == null || currentSocket == null || !currentSocket.isConnected) {
            connected = false
            status = "No active connection."
            return
        }

        draft = ""

        executor.execute {
            try {
                synchronized(outputLock) {
                    currentWriter.println(text)
                    if (currentWriter.checkError()) {
                        throw IOException("Could not write to the Bluetooth connection.")
                    }
                }

                runOnUiThread {
                    messages.add(ChatMessage(text, mine = true))
                }
            } catch (_: Exception) {
                runOnUiThread {
                    draft = text
                    status = "Message could not be sent. Check the connection."
                }
            }
        }
    }

    private fun closeActiveSocket() {
        synchronized(outputLock) {
            try {
                writer?.close()
            } catch (_: Exception) {
                // Ignore.
            }
            writer = null

            try {
                activeSocket?.close()
            } catch (_: Exception) {
                // Ignore.
            }
            activeSocket = null
        }
    }

    private fun closeServerSocket() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
            // Ignore.
        }
        serverSocket = null
    }

    private fun disconnect() {
        serverRunning = false
        listening = false

        closeServerSocket()
        closeActiveSocket()

        try {
            adapter?.cancelDiscovery()
        } catch (_: Exception) {
            // Ignore.
        }

        connected = false
        scanning = false
        peerName = ""
        status = "Disconnected."
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice): String {
        return try {
            device.name?.takeIf { it.isNotBlank() } ?: "Unknown device"
        } catch (_: SecurityException) {
            "Bluetooth device"
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceAddress(device: BluetoothDevice): String {
        return try {
            device.address ?: "Address unavailable"
        } catch (_: SecurityException) {
            "Address unavailable"
        }
    }

    override fun onDestroy() {
        serverRunning = false
        listening = false

        try {
            adapter?.cancelDiscovery()
        } catch (_: Exception) {
            // Ignore.
        }

        closeServerSocket()
        closeActiveSocket()

        if (receiverRegistered) {
            try {
                unregisterReceiver(discoveryReceiver)
            } catch (_: Exception) {
                // Ignore.
            }
            receiverRegistered = false
        }

        executor.shutdownNow()
        super.onDestroy()
    }
}
