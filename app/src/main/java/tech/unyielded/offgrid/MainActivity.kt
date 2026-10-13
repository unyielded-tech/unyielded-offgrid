package tech.unyielded.offgrid

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import tech.unyielded.offgrid.transport.BluetoothTransport
import tech.unyielded.offgrid.ui.DeviceCard
import tech.unyielded.offgrid.ui.FingerprintDisplay
import tech.unyielded.offgrid.ui.MessageBubble
import tech.unyielded.offgrid.ui.OffgridCard
import tech.unyielded.offgrid.ui.OffgridColors
import tech.unyielded.offgrid.ui.OffgridDarkScheme
import tech.unyielded.offgrid.ui.OffgridDimens
import tech.unyielded.offgrid.ui.PrimaryButton
import tech.unyielded.offgrid.ui.ScanningIndicator
import tech.unyielded.offgrid.ui.SecondaryButton
import tech.unyielded.offgrid.ui.SectionLabel
import tech.unyielded.offgrid.ui.StatusDot

private data class ChatMessage(
    val text: String,
    val mine: Boolean,
    val system: Boolean = false
)

private enum class Screen {
    HOME, DISCOVERY, CHAT, SECURITY, SETTINGS
}

class MainActivity : ComponentActivity() {

    private val messages = mutableStateListOf<ChatMessage>()
    private val discoveredDevices = mutableStateListOf<BluetoothDevice>()

    private var status by mutableStateOf("Ready")
    private var scanning by mutableStateOf(false)
    private var listening by mutableStateOf(false)
    private var connected by mutableStateOf(false)
    private var peerName by mutableStateOf("")
    private var fingerprint by mutableStateOf("")
    private var fingerprintFull by mutableStateOf("")
    private var handshakeStep by mutableStateOf("")
    private var currentScreen by mutableStateOf(Screen.HOME)
    private var draft by mutableStateOf("")
    private var verified by mutableStateOf(false)

    private var transport: BluetoothTransport? = null
    private var pendingPermissionAction: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.isNotEmpty() && result.values.all { it }) {
                val action = pendingPermissionAction
                pendingPermissionAction = null
                action?.invoke()
            } else {
                pendingPermissionAction = null
                status = "Bluetooth permissions are required"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        transport = BluetoothTransport(this, object : BluetoothTransport.Listener {
            override fun onStatus(message: String) { status = message }
            override fun onDevicesUpdated(devices: List<BluetoothDevice>) {
                discoveredDevices.clear()
                discoveredDevices.addAll(devices)
            }
            override fun onScanning(s: Boolean) { scanning = s }
            override fun onListening(l: Boolean) { listening = l }
            override fun onConnected(name: String, fp: String, fpFull: String) {
                connected = true
                peerName = name
                fingerprint = fp
                fingerprintFull = fpFull
                verified = false
                messages.add(ChatMessage("Secure connection established with $name", mine = false, system = true))
                currentScreen = Screen.CHAT
            }
            override fun onDisconnected(reason: String) {
                connected = false
                peerName = ""
                fingerprint = ""
                fingerprintFull = ""
                verified = false
                messages.add(ChatMessage(reason, mine = false, system = true))
            }
            override fun onMessageReceived(text: String) {
                messages.add(ChatMessage(text, mine = false))
            }
            override fun onMessageSent(text: String) {
                messages.add(ChatMessage(text, mine = true))
            }
            override fun onError(message: String) {
                status = message
            }
            override fun onHandshakeProgress(step: String) {
                handshakeStep = step
                status = step
            }
        })

        setContent {
            MaterialTheme(colorScheme = OffgridDarkScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = OffgridColors.Ink
                ) {
                    OffgridApp()
                }
            }
        }
    }

    override fun onDestroy() {
        transport?.shutdown()
        super.onDestroy()
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
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun runWithPermissions(action: () -> Unit) {
        if (!hasPermissions()) {
            pendingPermissionAction = action
            permissionLauncher.launch(requiredPermissions())
        } else {
            action()
        }
    }

    @Composable
    private fun OffgridApp() {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar()

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AnimatedContent(
                    targetState = currentScreen,
                    transitionSpec = {
                        (slideInHorizontally { it / 4 } + fadeIn()) togetherWith
                                (slideOutHorizontally { -it / 4 } + fadeOut())
                    },
                    label = "screen"
                ) { screen ->
                    when (screen) {
                        Screen.HOME -> HomeScreen()
                        Screen.DISCOVERY -> DiscoveryScreen()
                        Screen.CHAT -> ChatScreen()
                        Screen.SECURITY -> SecurityScreen()
                        Screen.SETTINGS -> SettingsScreen()
                    }
                }
            }

            if (currentScreen != Screen.CHAT || !connected) {
                BottomNav()
            }
        }
    }

    @Composable
    private fun TopBar() {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (currentScreen != Screen.HOME) {
                IconButton(onClick = {
                    currentScreen = if (connected) Screen.CHAT else Screen.HOME
                }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = OffgridColors.TextSecondary
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "UNYIELDED",
                    color = OffgridColors.Accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 3.sp
                )
                Text(
                    "// OFFGRID",
                    color = OffgridColors.TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.5).sp
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(OffgridColors.Surface)
                    .border(1.dp, OffgridColors.Border, RoundedCornerShape(20.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                StatusDot(connected, if (connected) OffgridColors.Accent else OffgridColors.TextTertiary)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (connected) "SECURE" else "OFFLINE",
                    color = if (connected) OffgridColors.Accent else OffgridColors.TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    @Composable
    private fun BottomNav() {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(OffgridColors.Surface)
                .border(1.dp, OffgridColors.Border)
                .padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            NavItem("Home", Icons.Default.Radar, Screen.HOME)
            NavItem("Discover", Icons.Default.Link, Screen.DISCOVERY)
            if (connected) {
                NavItem("Chat", Icons.Default.Lock, Screen.CHAT)
                NavItem("Security", Icons.Default.Shield, Screen.SECURITY)
            }
            NavItem("About", Icons.Default.Settings, Screen.SETTINGS)
        }
    }

    @Composable
    private fun NavItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, screen: Screen) {
        val selected = currentScreen == screen
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clickable { currentScreen = screen }
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (selected) OffgridColors.Accent else OffgridColors.TextTertiary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                color = if (selected) OffgridColors.Accent else OffgridColors.TextTertiary,
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }

    @Composable
    private fun HomeScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OffgridDimens.ScreenPadding)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            OffgridCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "NO CLOUD. NO COMPROMISE.",
                        color = OffgridColors.Accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        "Private communication,\ndirectly between nearby devices.",
                        color = OffgridColors.TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = 30.sp
                    )
                    Text(
                        "Messages never leave your phones. No accounts, no servers, no internet required.",
                        color = OffgridColors.TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
            }

            OffgridCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(connected)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                if (connected) "CONNECTED TO $peerName" else "NOT CONNECTED",
                                color = OffgridColors.TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                status,
                                color = OffgridColors.TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    if (connected && fingerprint.isNotEmpty()) {
                        Text(
                            "Fingerprint: $fingerprint",
                            color = OffgridColors.Accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            SectionLabel("Quick Actions")

            PrimaryButton(
                text = if (listening) "WAITING FOR PEER…" else "HOST / WAIT FOR PHONE",
                onClick = {
                    runWithPermissions { transport?.startListening() }
                },
                enabled = !listening && !connected
            )

            SecondaryButton(
                text = if (scanning) "STOP SCANNING" else "SCAN NEARBY DEVICES",
                onClick = {
                    if (scanning) {
                        transport?.stopDiscovery()
                    } else {
                        runWithPermissions {
                            currentScreen = Screen.DISCOVERY
                            transport?.startDiscovery()
                        }
                    }
                },
                enabled = !connected
            )

            if (connected) {
                PrimaryButton(
                    text = "OPEN CHAT",
                    onClick = { currentScreen = Screen.CHAT }
                )
                SecondaryButton(
                    text = "VIEW SECURITY",
                    onClick = { currentScreen = Screen.SECURITY }
                )
                SecondaryButton(
                    text = "DISCONNECT",
                    onClick = { transport?.disconnect() }
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "V3  •  AES-256-GCM  •  EPHEMERAL ECDH  •  BUILT BY UNYIELDED",
                color = OffgridColors.TextTertiary,
                fontSize = 9.sp,
                letterSpacing = 0.8.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }

    @Composable
    private fun DiscoveryScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = OffgridDimens.ScreenPadding)
        ) {
            Spacer(Modifier.height(8.dp))
            SectionLabel("Nearby Devices")
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (scanning) {
                    ScanningIndicator()
                    Spacer(Modifier.width(8.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (scanning) "Scanning…" else "Ready to scan",
                        color = OffgridColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Text(
                        status,
                        color = OffgridColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            PrimaryButton(
                text = if (scanning) "STOP SCAN" else "START SCAN",
                onClick = {
                    if (scanning) {
                        transport?.stopDiscovery()
                    } else {
                        runWithPermissions { transport?.startDiscovery() }
                    }
                },
                enabled = !connected
            )

            Spacer(Modifier.height(8.dp))

            SecondaryButton(
                text = if (listening) "WAITING…" else "HOST MODE",
                onClick = {
                    runWithPermissions { transport?.startListening() }
                },
                enabled = !listening && !connected
            )

            Spacer(Modifier.height(20.dp))

            if (discoveredDevices.isEmpty()) {
                OffgridCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "No devices yet",
                            color = OffgridColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Enable Bluetooth on both phones.\nThe other phone may need to be discoverable.",
                            color = OffgridColors.TextSecondary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(discoveredDevices, key = { it.address }) { device ->
                        DeviceCard(
                            name = transport?.safeDeviceName(device) ?: "Device",
                            address = transport?.safeDeviceAddress(device) ?: "—",
                            onConnect = {
                                runWithPermissions {
                                    transport?.connectToDevice(device)
                                }
                            },
                            enabled = !connected
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ChatScreen() {
        val listState = rememberLazyListState()

        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(OffgridColors.Surface)
                    .border(1.dp, OffgridColors.Border)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    if (connected) peerName else "Not connected",
                    color = OffgridColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(connected)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (connected) {
                            if (verified) "Verified • $fingerprint" else "Encrypted • $fingerprint"
                        } else status,
                        color = if (verified) OffgridColors.Accent else OffgridColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            "Messages are end-to-end encrypted with AES-256-GCM.\nCompare fingerprints in person to detect MITM.",
                            color = OffgridColors.TextTertiary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 40.dp)
                        )
                    }
                }
                items(messages) { msg ->
                    MessageBubble(msg.text, msg.mine, msg.system)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(OffgridColors.Surface)
                    .border(1.dp, OffgridColors.Border)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(OffgridColors.Ink)
                        .border(1.dp, OffgridColors.Border, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    if (draft.isEmpty()) {
                        Text("Message…", color = OffgridColors.TextTertiary, fontSize = 14.sp)
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        enabled = connected,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(
                            color = OffgridColors.TextPrimary,
                            fontSize = 14.sp
                        ),
                        cursorBrush = SolidColor(OffgridColors.Accent),
                        singleLine = true
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (connected && draft.isNotBlank()) OffgridColors.Accent
                            else OffgridColors.SurfaceElevated
                        )
                        .clickable(enabled = connected && draft.isNotBlank()) {
                            val text = draft.trim()
                            if (text.isNotEmpty()) {
                                draft = ""
                                transport?.sendMessage(text)
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        "SEND",
                        color = if (connected && draft.isNotBlank()) OffgridColors.Ink else OffgridColors.TextTertiary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }

    @Composable
    private fun SecurityScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OffgridDimens.ScreenPadding)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            SectionLabel("Connection Security")

            OffgridCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecurityRow("Status", if (connected) "Connected" else "Disconnected")
                    SecurityRow("Encryption", if (connected) "AES-256-GCM" else "—")
                    SecurityRow("Key Exchange", if (connected) "Ephemeral ECDH (P-256)" else "—")
                    SecurityRow("Protocol", "OFFGRID V3")
                    SecurityRow("Forward Secrecy", "Session only (ephemeral keys)")
                }
            }

            if (connected && fingerprint.isNotEmpty()) {
                SectionLabel("Security Fingerprint")
                Text(
                    "Compare this code with your peer in person. If they match, no man-in-the-middle is present.",
                    color = OffgridColors.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                FingerprintDisplay(fingerprint)

                Spacer(Modifier.height(4.dp))
                Text(
                    "Full: $fingerprintFull",
                    color = OffgridColors.TextTertiary,
                    fontSize = 10.sp
                )

                Spacer(Modifier.height(8.dp))
                if (!verified) {
                    PrimaryButton(
                        text = "I VERIFIED THE FINGERPRINT",
                        onClick = { verified = true }
                    )
                    Text(
                        "Only mark as verified after comparing the codes face-to-face or over a trusted channel.",
                        color = OffgridColors.TextTertiary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                } else {
                    OffgridCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(true)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Fingerprint verified by you",
                                color = OffgridColors.Accent,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            } else {
                OffgridCard {
                    Text(
                        "Connect to another device to establish a secure session and see the fingerprint.",
                        color = OffgridColors.TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }

            SectionLabel("Threat Model")
            OffgridCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "• Protects against passive eavesdropping on the Bluetooth link.\n" +
                        "• Detects active MITM only when fingerprints are compared.\n" +
                        "• No long-term identity keys — each session is independent.\n" +
                        "• Does not protect against compromised devices or malware.",
                        color = OffgridColors.TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }

    @Composable
    private fun SecurityRow(label: String, value: String) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = OffgridColors.TextSecondary, fontSize = 13.sp)
            Text(value, color = OffgridColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }

    @Composable
    private fun SettingsScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OffgridDimens.ScreenPadding)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            SectionLabel("About")

            OffgridCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "UNYIELDED // OFFGRID",
                        color = OffgridColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text("Version 0.3.0-v3", color = OffgridColors.TextSecondary, fontSize = 13.sp)
                    Text(
                        "Private, offline communication between nearby Android devices. No internet, no servers, no accounts.",
                        color = OffgridColors.TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            SectionLabel("Privacy")
            OffgridCard {
                Text(
                    "All messages stay on the two devices that are connected. Nothing is uploaded, logged, or stored on any server. Session keys are ephemeral and destroyed when the connection ends.",
                    color = OffgridColors.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }

            SectionLabel("Permissions")
            OffgridCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Bluetooth Scan & Connect — required to discover and talk to nearby phones.",
                        color = OffgridColors.TextSecondary,
                        fontSize = 12.sp
                    )
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        Text(
                            "Location (fine) — required by Android for Bluetooth discovery on older versions. Location data is never collected or stored.",
                            color = OffgridColors.TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            if (connected || listening) {
                SecondaryButton(
                    text = "DISCONNECT / RESET",
                    onClick = { transport?.disconnect() }
                )
            }

            Text(
                "Open source • Built by UNYIELDED\ngithub.com/unyielded-tech/unyielded-offgrid",
                color = OffgridColors.TextTertiary,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
