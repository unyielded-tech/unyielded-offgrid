package tech.unyielded.offgrid

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radar
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import tech.unyielded.offgrid.call.CallController
import tech.unyielded.offgrid.call.CallProtocol
import tech.unyielded.offgrid.identity.ContactStore
import tech.unyielded.offgrid.identity.IdentityManager
import tech.unyielded.offgrid.media.ImageCompressor
import tech.unyielded.offgrid.media.VoiceRecorder
import tech.unyielded.offgrid.mesh.MeshCoordinator
import tech.unyielded.offgrid.mesh.MeshStatus
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
    val system: Boolean = false,
    val isVoice: Boolean = false,
    val isImage: Boolean = false,
    val mediaBytes: ByteArray? = null
)

private enum class Screen {
    SETUP, HOME, DISCOVERY, CHAT, SECURITY, CONTACTS, PROFILE
}

class MainActivity : ComponentActivity() {

    private val messages = mutableStateListOf<ChatMessage>()
    private val discoveredDevices = mutableStateListOf<BluetoothDevice>()
    private val contacts = mutableStateListOf<ContactStore.Contact>()

    private var status by mutableStateOf("Ready")
    private var scanning by mutableStateOf(false)
    private var listening by mutableStateOf(false)
    private var connected by mutableStateOf(false)
    private var peerName by mutableStateOf("")
    private var fingerprint by mutableStateOf("")
    private var fingerprintFull by mutableStateOf("")
    private var currentScreen by mutableStateOf(Screen.HOME)
    private var draft by mutableStateOf("")
    private var verified by mutableStateOf(false)
    private var setupName by mutableStateOf("")
    private var myShortId by mutableStateOf("")
    private var myDisplayName by mutableStateOf("")
    private var myFingerprint by mutableStateOf("")
    private var isRecording by mutableStateOf(false)
    private var addContactCode by mutableStateOf("")
    private var meshPending by mutableStateOf(0)
    private var meshEvent by mutableStateOf("Mesh idle")
    private var callState by mutableStateOf(CallController.State.IDLE)

    private var transport: BluetoothTransport? = null
    private var identityManager: IdentityManager? = null
    private var contactStore: ContactStore? = null
    private var voiceRecorder: VoiceRecorder? = null
    private var meshCoordinator: MeshCoordinator? = null
    private var callController: CallController? = null
    private var pendingPermissionAction: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.isNotEmpty() && result.values.all { it }) {
                val action = pendingPermissionAction
                pendingPermissionAction = null
                action?.invoke()
            } else {
                pendingPermissionAction = null
                status = "Permissions are required"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        identityManager = IdentityManager(this)
        contactStore = ContactStore(this)
        voiceRecorder = VoiceRecorder(this)

        val identity = identityManager?.getIdentity()
        if (identity != null) {
            myShortId = identity.shortId
            myDisplayName = identity.displayName
            myFingerprint = identity.fingerprint
            currentScreen = Screen.HOME
            initMesh(identity.shortId)
        } else {
            currentScreen = Screen.SETUP
        }

        contacts.clear()
        contacts.addAll(contactStore?.getAll() ?: emptyList())

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
                messages.add(ChatMessage("Secure connection with $name", mine = false, system = true))
                currentScreen = Screen.CHAT
                initCallController()
                meshCoordinator?.onConnected(null) { text -> transport?.sendMessage(text) }
            }
            override fun onDisconnected(reason: String) {
                callController?.release()
                callController = null
                callState = CallController.State.IDLE
                connected = false
                peerName = ""
                fingerprint = ""
                fingerprintFull = ""
                verified = false
                messages.add(ChatMessage(reason, mine = false, system = true))
            }
            override fun onMessageReceived(text: String) {
                if (callController?.onControlMessage(text) == true) {
                    return
                }
                val handled = meshCoordinator?.onIncomingText(text, null) { reply ->
                    transport?.sendMessage(reply)
                } == true
                if (!handled) {
                    messages.add(ChatMessage(text, mine = false))
                }
                refreshMeshUi()
            }
            override fun onMessageSent(text: String) {
                if (!CallProtocol.isCallControl(text)) {
                    messages.add(ChatMessage(text, mine = true))
                }
            }
            override fun onVoiceReceived(bytes: ByteArray) {
                // During a call, voice frames are live audio
                if (callState == CallController.State.IN_CALL) {
                    callController?.onAudioReceived(bytes)
                } else {
                    messages.add(ChatMessage("Voice message", mine = false, isVoice = true, mediaBytes = bytes))
                }
            }
            override fun onImageReceived(bytes: ByteArray) {
                messages.add(ChatMessage("Photo", mine = false, isImage = true, mediaBytes = bytes))
            }
            override fun onPeerIdentity(shortId: String, displayName: String) {
                peerName = displayName
                messages.add(ChatMessage("Peer: $displayName ($shortId)", mine = false, system = true))
                if (contactStore?.find(shortId) == null) {
                    contactStore?.add(ContactStore.Contact(shortId, displayName))
                    contacts.clear()
                    contacts.addAll(contactStore?.getAll() ?: emptyList())
                }
            }
            override fun onError(message: String) { status = message }
            override fun onHandshakeProgress(step: String) { status = step }
        })

        identity?.let { transport?.setMyIdentity(it.shortId, it.displayName) }

        setContent {
            MaterialTheme(colorScheme = OffgridDarkScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = OffgridColors.Ink) {
                    OffgridApp()
                }
            }
        }
    }

    private fun initMesh(shortId: String) {
        meshCoordinator = MeshCoordinator(this, shortId).also { mesh ->
            mesh.onStatus = { msg ->
                MeshStatus.lastEvent = msg
                MeshStatus.pendingCount = mesh.pendingCount()
                meshEvent = msg
                meshPending = mesh.pendingCount()
                status = msg
            }
            mesh.onDeliveredToMe = { fromId, bytes ->
                val text = try { String(bytes, Charsets.UTF_8) } catch (_: Exception) { "[mesh data]" }
                messages.add(ChatMessage("[Mesh] $fromId: $text", mine = false))
            }
            refreshMeshUi()
        }
    }

    private fun initCallController() {
        callController?.release()
        callController = CallController(
            this,
            sendControl = { transport?.sendMessage(it) },
            sendAudio = { transport?.sendVoice(it) }
        ).also { cc ->
            cc.onStateChanged = { callState = it }
            cc.onStatus = { status = it }
        }
        callState = CallController.State.IDLE
    }

    private fun refreshMeshUi() {
        meshPending = meshCoordinator?.pendingCount() ?: 0
        meshEvent = MeshStatus.lastEvent
    }

    override fun onDestroy() {
        callController?.release()
        voiceRecorder?.release()
        transport?.shutdown()
        super.onDestroy()
    }

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        list.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return list.toTypedArray()
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
        if (currentScreen == Screen.SETUP) {
            SetupScreen()
            return
        }
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar()
            // Incoming / in-call banner
            if (callState == CallController.State.INCOMING || callState == CallController.State.OUTGOING || callState == CallController.State.IN_CALL) {
                CallBanner()
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                        Screen.CONTACTS -> ContactsScreen()
                        Screen.PROFILE -> ProfileScreen()
                        Screen.SETUP -> SetupScreen()
                    }
                }
            }
            if (currentScreen != Screen.CHAT || !connected) {
                BottomNav()
            }
        }
    }

    @Composable
    private fun CallBanner() {
        val bg = when (callState) {
            CallController.State.INCOMING -> OffgridColors.Accent
            CallController.State.OUTGOING -> OffgridColors.SurfaceElevated
            CallController.State.IN_CALL -> OffgridColors.Danger
            else -> OffgridColors.Surface
        }
        Row(
            Modifier.fillMaxWidth().background(bg).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                when (callState) {
                    CallController.State.INCOMING -> "Incoming call from $peerName"
                    CallController.State.OUTGOING -> "Calling $peerName…"
                    CallController.State.IN_CALL -> "In call with $peerName"
                    else -> ""
                },
                color = OffgridColors.Ink,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            when (callState) {
                CallController.State.INCOMING -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp)).background(OffgridColors.Ink)
                                .clickable { callController?.acceptIncoming() }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) { Text("Accept", color = OffgridColors.Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp)).background(OffgridColors.Ink)
                                .clickable { callController?.rejectIncoming() }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) { Text("Reject", color = OffgridColors.Danger, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                    }
                }
                CallController.State.OUTGOING, CallController.State.IN_CALL -> {
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(OffgridColors.Ink)
                            .clickable { callController?.endCall() }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) { Text("End", color = OffgridColors.Danger, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                }
                else -> {}
            }
        }
    }

    @Composable
    private fun SetupScreen() {
        Column(
            Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("UNYIELDED", color = OffgridColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp)
            Text("// OFFGRID", color = OffgridColors.TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            Text("Create your offline identity", color = OffgridColors.TextSecondary, fontSize = 14.sp)
            Spacer(Modifier.height(32.dp))
            OffgridCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Display Name", color = OffgridColors.TextSecondary, fontSize = 12.sp)
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(OffgridColors.Ink)
                            .border(1.dp, OffgridColors.Border, RoundedCornerShape(12.dp)).padding(14.dp)
                    ) {
                        if (setupName.isEmpty()) Text("Your name…", color = OffgridColors.TextTertiary)
                        BasicTextField(
                            value = setupName,
                            onValueChange = { setupName = it.take(32) },
                            textStyle = TextStyle(color = OffgridColors.TextPrimary, fontSize = 16.sp),
                            cursorBrush = SolidColor(OffgridColors.Accent),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                text = "CREATE IDENTITY",
                onClick = {
                    val id = identityManager?.createIdentity(setupName)
                    if (id != null) {
                        myShortId = id.shortId
                        myDisplayName = id.displayName
                        myFingerprint = id.fingerprint
                        transport?.setMyIdentity(id.shortId, id.displayName)
                        initMesh(id.shortId)
                        currentScreen = Screen.HOME
                    }
                },
                enabled = setupName.trim().isNotBlank()
            )
            Spacer(Modifier.height(16.dp))
            Text("Built by Ayaz · UNYIELDED", color = OffgridColors.TextTertiary, fontSize = 12.sp)
        }
    }

    @Composable
    private fun TopBar() {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (currentScreen != Screen.HOME) {
                IconButton(onClick = { currentScreen = if (connected) Screen.CHAT else Screen.HOME }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = OffgridColors.TextSecondary)
                }
            }
            Column(Modifier.weight(1f)) {
                Text("UNYIELDED", color = OffgridColors.Accent, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp)
                Text("// OFFGRID", color = OffgridColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(OffgridColors.Surface)
                    .border(1.dp, OffgridColors.Border, RoundedCornerShape(20.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                StatusDot(connected)
                Spacer(Modifier.width(6.dp))
                Text(if (connected) "SECURE" else "OFFLINE", color = if (connected) OffgridColors.Accent else OffgridColors.TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    private fun BottomNav() {
        Row(
            Modifier.fillMaxWidth().background(OffgridColors.Surface).border(1.dp, OffgridColors.Border)
                .padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            NavItem("Home", Icons.Default.Radar, Screen.HOME)
            NavItem("Discover", Icons.Default.Link, Screen.DISCOVERY)
            NavItem("Contacts", Icons.Default.Person, Screen.CONTACTS)
            if (connected) NavItem("Chat", Icons.Default.Lock, Screen.CHAT)
            NavItem("Profile", Icons.Default.Person, Screen.PROFILE)
        }
    }

    @Composable
    private fun NavItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, screen: Screen) {
        val selected = currentScreen == screen
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable { currentScreen = screen }.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Icon(icon, label, tint = if (selected) OffgridColors.Accent else OffgridColors.TextTertiary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(2.dp))
            Text(label, color = if (selected) OffgridColors.Accent else OffgridColors.TextTertiary, fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }

    @Composable
    private fun HomeScreen() {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = OffgridDimens.ScreenPadding).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            OffgridCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("NO CLOUD. NO COMPROMISE.", color = OffgridColors.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text("Private communication,\ndirectly between nearby devices.", color = OffgridColors.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 28.sp)
                    Text("You are $myDisplayName  •  $myShortId", color = OffgridColors.TextSecondary, fontSize = 13.sp)
                }
            }
            OffgridCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(connected)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(if (connected) "CONNECTED TO $peerName" else "NOT CONNECTED", color = OffgridColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(status, color = OffgridColors.TextSecondary, fontSize = 12.sp)
                        }
                    }
                    if (connected && fingerprint.isNotEmpty()) {
                        Text("Session: $fingerprint", color = OffgridColors.Accent, fontSize = 12.sp)
                    }
                }
            }
            if (meshPending > 0 || meshEvent != "Mesh idle") {
                OffgridCard {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Mesh queue: $meshPending pending", color = OffgridColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(meshEvent, color = OffgridColors.TextSecondary, fontSize = 12.sp)
                        Text("Nearby multi-hop only. Not long-distance.", color = OffgridColors.TextTertiary, fontSize = 11.sp)
                    }
                }
            }
            SectionLabel("Quick Actions")
            PrimaryButton(
                text = if (listening) "WAITING FOR PEER…" else "HOST / WAIT FOR PHONE",
                onClick = { runWithPermissions { transport?.startListening() } },
                enabled = !listening && !connected
            )
            SecondaryButton(
                text = if (scanning) "STOP SCANNING" else "SCAN NEARBY DEVICES",
                onClick = {
                    if (scanning) transport?.stopDiscovery()
                    else runWithPermissions { currentScreen = Screen.DISCOVERY; transport?.startDiscovery() }
                },
                enabled = !connected
            )
            if (connected) {
                PrimaryButton(text = "OPEN CHAT", onClick = { currentScreen = Screen.CHAT })
                SecondaryButton(text = "DISCONNECT", onClick = { transport?.disconnect() })
            }
            Text("Built by Ayaz · UNYIELDED  •  V6 Call", color = OffgridColors.TextTertiary, fontSize = 10.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }

    @Composable
    private fun DiscoveryScreen() {
        Column(Modifier.fillMaxSize().padding(horizontal = OffgridDimens.ScreenPadding)) {
            Spacer(Modifier.height(8.dp))
            SectionLabel("Nearby Devices")
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (scanning) { ScanningIndicator(); Spacer(Modifier.width(8.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(if (scanning) "Scanning…" else "Ready to scan", color = OffgridColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(status, color = OffgridColors.TextSecondary, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(text = if (scanning) "STOP SCAN" else "START SCAN", onClick = {
                if (scanning) transport?.stopDiscovery() else runWithPermissions { transport?.startDiscovery() }
            }, enabled = !connected)
            Spacer(Modifier.height(8.dp))
            SecondaryButton(text = if (listening) "WAITING…" else "HOST MODE", onClick = {
                runWithPermissions { transport?.startListening() }
            }, enabled = !listening && !connected)
            Spacer(Modifier.height(20.dp))
            if (discoveredDevices.isEmpty()) {
                OffgridCard {
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No devices yet", color = OffgridColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text("Enable Bluetooth on both phones.", color = OffgridColors.TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                    items(discoveredDevices, key = { it.address }) { device ->
                        DeviceCard(
                            name = transport?.safeDeviceName(device) ?: "Device",
                            address = transport?.safeDeviceAddress(device) ?: "—",
                            onConnect = { runWithPermissions { transport?.connectToDevice(device) } },
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
        val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null && connected) {
                val bytes = ImageCompressor.compress(this@MainActivity, uri)
                if (bytes != null) {
                    transport?.sendImage(bytes)
                    messages.add(ChatMessage("Photo", mine = true, isImage = true, mediaBytes = bytes))
                } else status = "Could not process image"
            }
        }

        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }

        Column(Modifier.fillMaxSize().imePadding()) {
            Column(Modifier.fillMaxWidth().background(OffgridColors.Surface).border(1.dp, OffgridColors.Border).padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(if (connected) peerName else "Not connected", color = OffgridColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            "Encrypted chat ready.\nHold mic for voice • Tap phone for call • Image for photo",
                            color = OffgridColors.TextTertiary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 40.dp)
                        )
                    }
                }
                items(messages) { msg ->
                    when {
                        msg.isVoice -> {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.mine) Arrangement.End else Arrangement.Start) {
                                Box(
                                    Modifier.clip(RoundedCornerShape(14.dp))
                                        .background(if (msg.mine) OffgridColors.BubbleMine else OffgridColors.BubblePeer)
                                        .border(1.dp, if (msg.mine) OffgridColors.AccentMuted else OffgridColors.Border, RoundedCornerShape(14.dp))
                                        .clickable { msg.mediaBytes?.let { voiceRecorder?.play(it) } }
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Mic, null, tint = OffgridColors.Accent, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Voice (tap to play)", color = OffgridColors.TextPrimary, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                        msg.isImage -> {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.mine) Arrangement.End else Arrangement.Start) {
                                Box(
                                    Modifier.clip(RoundedCornerShape(14.dp))
                                        .background(if (msg.mine) OffgridColors.BubbleMine else OffgridColors.BubblePeer)
                                        .border(1.dp, if (msg.mine) OffgridColors.AccentMuted else OffgridColors.Border, RoundedCornerShape(14.dp))
                                        .padding(10.dp)
                                ) {
                                    Text(if (msg.mine) "Photo sent" else "Photo received", color = OffgridColors.TextPrimary, fontSize = 13.sp)
                                }
                            }
                        }
                        else -> MessageBubble(msg.text, msg.mine, msg.system)
                    }
                }
            }

            // Composer with Call button
            Row(
                Modifier.fillMaxWidth().background(OffgridColors.Surface).border(1.dp, OffgridColors.Border).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Photo
                IconButton(onClick = { if (connected) imagePicker.launch("image/*") }, enabled = connected && callState == CallController.State.IDLE) {
                    Icon(Icons.Default.Image, "Photo", tint = if (connected) OffgridColors.Accent else OffgridColors.TextTertiary)
                }

                // Mic (voice message)
                Box(
                    Modifier.size(42.dp).clip(CircleShape)
                        .background(if (isRecording) OffgridColors.Danger else OffgridColors.SurfaceElevated)
                        .pointerInput(connected, callState) {
                            detectTapGestures(
                                onPress = {
                                    if (!connected || callState != CallController.State.IDLE) return@detectTapGestures
                                    if (voiceRecorder?.startRecording() == true) {
                                        isRecording = true
                                        status = "Recording…"
                                    }
                                    tryAwaitRelease()
                                    isRecording = false
                                    val bytes = voiceRecorder?.stopRecording()
                                    if (bytes != null && bytes.isNotEmpty()) {
                                        transport?.sendVoice(bytes)
                                        messages.add(ChatMessage("Voice message", mine = true, isVoice = true, mediaBytes = bytes))
                                    }
                                    status = if (connected) "Secure session" else "Ready"
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Mic, "Voice",
                        tint = if (isRecording) OffgridColors.TextPrimary else if (connected) OffgridColors.Accent else OffgridColors.TextTertiary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // CALL BUTTON
                IconButton(
                    onClick = {
                        if (!connected) return@IconButton
                        when (callState) {
                            CallController.State.IDLE -> callController?.startOutgoing()
                            CallController.State.OUTGOING, CallController.State.IN_CALL -> callController?.endCall()
                            else -> {}
                        }
                    },
                    enabled = connected
                ) {
                    Icon(
                        imageVector = if (callState == CallController.State.IDLE) Icons.Default.Call else Icons.Default.CallEnd,
                        contentDescription = "Call",
                        tint = when {
                            !connected -> OffgridColors.TextTertiary
                            callState == CallController.State.IN_CALL -> OffgridColors.Danger
                            callState == CallController.State.OUTGOING -> OffgridColors.Accent
                            else -> OffgridColors.Accent
                        }
                    )
                }

                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(OffgridColors.Ink)
                        .border(1.dp, OffgridColors.Border, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    if (draft.isEmpty()) Text("Message…", color = OffgridColors.TextTertiary, fontSize = 14.sp)
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        enabled = connected && callState == CallController.State.IDLE,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = OffgridColors.TextPrimary, fontSize = 14.sp),
                        cursorBrush = SolidColor(OffgridColors.Accent),
                        singleLine = true
                    )
                }
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(if (connected && draft.isNotBlank() && callState == CallController.State.IDLE) OffgridColors.Accent else OffgridColors.SurfaceElevated)
                        .clickable(enabled = connected && draft.isNotBlank() && callState == CallController.State.IDLE) {
                            val text = draft.trim()
                            if (text.isNotEmpty()) {
                                draft = ""
                                transport?.sendMessage(text)
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text("SEND", color = if (connected && draft.isNotBlank()) OffgridColors.Ink else OffgridColors.TextTertiary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }

    @Composable
    private fun ContactsScreen() {
        Column(Modifier.fillMaxSize().padding(horizontal = OffgridDimens.ScreenPadding).padding(bottom = 24.dp)) {
            Spacer(Modifier.height(8.dp))
            SectionLabel("Friends")
            Spacer(Modifier.height(12.dp))
            OffgridCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Add friend by invite code", color = OffgridColors.TextSecondary, fontSize = 12.sp)
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(OffgridColors.Ink)
                            .border(1.dp, OffgridColors.Border, RoundedCornerShape(12.dp)).padding(12.dp)
                    ) {
                        if (addContactCode.isEmpty()) Text("A3F7-9C2B|Their Name", color = OffgridColors.TextTertiary, fontSize = 13.sp)
                        BasicTextField(
                            value = addContactCode,
                            onValueChange = { addContactCode = it },
                            textStyle = TextStyle(color = OffgridColors.TextPrimary, fontSize = 13.sp),
                            cursorBrush = SolidColor(OffgridColors.Accent),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    PrimaryButton(text = "ADD CONTACT", onClick = {
                        val parsed = identityManager?.parseInviteCode(addContactCode)
                        if (parsed != null) {
                            val added = contactStore?.add(ContactStore.Contact(parsed.first, parsed.second)) == true
                            if (added) {
                                contacts.clear()
                                contacts.addAll(contactStore?.getAll() ?: emptyList())
                                addContactCode = ""
                                status = "Contact added"
                            } else status = "Already in contacts"
                        } else status = "Invalid invite code"
                    })
                }
            }
            Spacer(Modifier.height(16.dp))
            if (contacts.isEmpty()) {
                OffgridCard {
                    Text("No contacts yet.\nShare your invite code from Profile.", color = OffgridColors.TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(contacts, key = { it.shortId }) { c ->
                        OffgridCard {
                            Column {
                                Text(c.displayName, color = OffgridColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(c.shortId, color = OffgridColors.TextTertiary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ProfileScreen() {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = OffgridDimens.ScreenPadding).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            SectionLabel("Your Identity")
            OffgridCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(myDisplayName, color = OffgridColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("ID: $myShortId", color = OffgridColors.Accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Fingerprint: $myFingerprint", color = OffgridColors.TextSecondary, fontSize = 12.sp)
                }
            }
            SectionLabel("Invite Code")
            OffgridCard {
                Text("$myShortId|$myDisplayName", color = OffgridColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            SectionLabel("About")
            OffgridCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("UNYIELDED // OFFGRID", color = OffgridColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Built by Ayaz · UNYIELDED", color = OffgridColors.Accent, fontSize = 13.sp)
                    Text("Private offline chat, mesh relay, experimental calling.", color = OffgridColors.TextSecondary, fontSize = 12.sp)
                    Text("Version 0.6.0-v6", color = OffgridColors.TextTertiary, fontSize = 11.sp)
                }
            }
        }
    }

    @Composable
    private fun SecurityScreen() {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = OffgridDimens.ScreenPadding).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            SectionLabel("Connection Security")
            OffgridCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecurityRow("Status", if (connected) "Connected" else "Disconnected")
                    SecurityRow("Encryption", if (connected) "AES-256-GCM" else "—")
                    SecurityRow("Protocol", "OFFGRID V6")
                }
            }
            if (connected && fingerprint.isNotEmpty()) {
                SectionLabel("Session Fingerprint")
                FingerprintDisplay(fingerprint)
                if (!verified) {
                    PrimaryButton(text = "I VERIFIED THE FINGERPRINT", onClick = { verified = true })
                }
            }
        }
    }

    @Composable
    private fun SecurityRow(label: String, value: String) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = OffgridColors.TextSecondary, fontSize = 13.sp)
            Text(value, color = OffgridColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}
