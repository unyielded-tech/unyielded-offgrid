package tech.unyielded.offgrid

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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

private val Ink = Color(0xFF080A0D)
private val Panel = Color(0xFF12161B)
private val PanelLight = Color(0xFF1A2027)
private val Lime = Color(0xFFC5FF54)
private val White = Color(0xFFF4F6F2)
private val Muted = Color(0xFF929BA6)
private val Border = Color(0xFF29313A)
private val Red = Color(0xFFFF7373)

class MainActivity : ComponentActivity() {

    private val devices = mutableStateListOf<BluetoothDevice>()

    private var status by mutableStateOf("Ready to scan")
    private var scanning by mutableStateOf(false)
    private var receiverRegistered = false

    private val bluetoothManager by lazy {
        getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    private val adapter: BluetoothAdapter?
        get() = bluetoothManager.adapter

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            if (result.values.isNotEmpty() && result.values.all { it }) {
                startDiscovery()
            } else {
                scanning = false
                status = "Bluetooth permissions were not granted."
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
                            val address = device.address

                            if (devices.none { it.address == address }) {
                                devices.add(device)
                            }

                            status = "Discovering nearby devices… ${devices.size} found"
                        } catch (_: SecurityException) {
                            status = "Bluetooth permission is required."
                        }
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    scanning = false

                    status = if (devices.isEmpty()) {
                        "Scan finished. No devices discovered."
                    } else {
                        "Scan complete. ${devices.size} device(s) discovered."
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    scanning = true
                    status = "Bluetooth discovery is running…"
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
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {

            // Brand header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "UNYIELDED",
                        color = Lime,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 4.sp
                    )

                    Spacer(Modifier.height(5.dp))

                    Text(
                        text = "// OFFGRID",
                        color = White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-1).sp
                    )

                    Text(
                        text = "PRIVATE. LOCAL. INDEPENDENT.",
                        color = Muted,
                        fontSize = 9.sp,
                        letterSpacing = 1.5.sp
                    )
                }

                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = PanelLight,
                                shape = RoundedCornerShape(9.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = Border,
                                shape = RoundedCornerShape(9.dp)
                            )
                            .padding(horizontal = 11.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "BETA 01",
                            color = Lime,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    if (scanning) Lime else Muted,
                                    CircleShape
                                )
                        )

                        Spacer(Modifier.size(5.dp))

                        Text(
                            text = if (scanning) "ACTIVE" else "STANDBY",
                            color = if (scanning) Lime else Muted,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Main hero card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Panel
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = Border,
                            shape = RoundedCornerShape(22.dp)
                        )
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    Text(
                        text = "YOUR NETWORK. YOUR RULES.",
                        color = Lime,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.8.sp
                    )

                    Text(
                        text = "Communication\nwithout the cloud.",
                        color = White,
                        fontSize = 27.sp,
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = 33.sp
                    )

                    Text(
                        text = "Discover nearby Bluetooth devices directly from your phone. No internet connection is required for Bluetooth discovery.",
                        color = Muted,
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FeaturePill("OFFLINE FIRST")
                        FeaturePill("BLUETOOTH")
                    }
                }
            }

            // Requested banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF171C16)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = Color(0xFF465438),
                            shape = RoundedCornerShape(18.dp)
                        )
                        .padding(17.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(
                                color = Lime,
                                shape = RoundedCornerShape(13.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "A",
                            color = Ink,
                            fontSize = 23.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    Spacer(Modifier.size(13.dp))

                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "AYAZ SUPPORTS CJP",
                            color = Lime,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.5.sp
                        )

                        Spacer(Modifier.height(4.dp))

                        Text(
                            text = "A message from the creator",
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }

                    Text(
                        text = "✦",
                        color = Lime,
                        fontSize = 21.sp
                    )
                }
            }

            // Scanner section
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "DEVICE RADAR",
                            color = White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.3.sp
                        )

                        Spacer(Modifier.height(4.dp))

                        Text(
                            text = "Nearby Bluetooth discovery",
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }

                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(23.dp),
                            color = Lime,
                            strokeWidth = 2.dp
                        )
                    }
                }

                Button(
                    onClick = {
                        if (scanning) {
                            stopDiscoveryByUser()
                        } else {
                            requestBluetoothPermissions()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (scanning) PanelLight else Lime,
                        contentColor = if (scanning) White else Ink
                    )
                ) {
                    Text(
                        text = if (scanning) {
                            "STOP SCANNING"
                        } else {
                            "SCAN NEARBY DEVICES  →"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    )
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(13.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Panel
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    color = when {
                                        scanning -> Lime
                                        status.contains("permission", true) -> Red
                                        else -> Muted
                                    },
                                    shape = CircleShape
                                )
                        )

                        Spacer(Modifier.size(10.dp))

                        Text(
                            text = status,
                            color = Muted,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            // Results
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DISCOVERED DEVICES",
                    color = White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = devices.size.toString().padStart(2, '0'),
                    color = Lime,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (devices.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(17.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Panel
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Text(
                            text = "⌁",
                            color = Lime,
                            fontSize = 34.sp
                        )

                        Text(
                            text = if (scanning) {
                                "Searching for devices"
                            } else {
                                "Nothing on the radar yet"
                            },
                            color = White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "Keep Bluetooth enabled. Nearby devices may need to be discoverable to appear here.",
                            color = Muted,
                            fontSize = 12.sp,
                            lineHeight = 19.sp
                        )
                    }
                }
            } else {
                devices.forEach { device ->
                    DeviceRow(device)
                }
            }

            Spacer(Modifier.height(5.dp))

            Text(
                text = "OFFLINE FIRST  •  BUILT BY UNYIELDED",
                color = Muted,
                fontSize = 9.sp,
                letterSpacing = 1.2.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }

    @Composable
    private fun FeaturePill(label: String) {
        Box(
            modifier = Modifier
                .background(
                    color = PanelLight,
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Text(
                text = label,
                color = Lime,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.7.sp
            )
        }
    }

    @Composable
    private fun DeviceRow(device: BluetoothDevice) {
        val name = safeDeviceName(device)
        val address = safeDeviceAddress(device)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(15.dp),
            colors = CardDefaults.cardColors(
                containerColor = Panel
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = Border,
                        shape = RoundedCornerShape(15.dp)
                    )
                    .padding(15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(43.dp)
                        .background(
                            color = PanelLight,
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "BT",
                        color = Lime,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                Spacer(Modifier.size(12.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = name,
                        color = White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(5.dp))

                    Text(
                        text = address,
                        color = Muted,
                        fontSize = 10.sp
                    )
                }

                Text(
                    text = "FOUND",
                    color = Lime,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
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
            "Address permission unavailable"
        }
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBluetoothPermissions() {
        if (adapter == null) {
            scanning = false
            status = "This phone does not support Bluetooth."
            return
        }

        if (!hasRequiredPermissions()) {
            permissionLauncher.launch(requiredPermissions())
            return
        }

        startDiscovery()
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        val bluetooth = adapter

        if (bluetooth == null) {
            scanning = false
            status = "Bluetooth is unavailable on this device."
            return
        }

        if (!hasRequiredPermissions()) {
            scanning = false
            status = "Bluetooth permission is required."
            requestBluetoothPermissions()
            return
        }

        try {
            if (!bluetooth.isEnabled) {
                scanning = false
                status = "Turn on Bluetooth in Android settings, then try again."
                return
            }

            if (!receiverRegistered) {
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

            if (bluetooth.isDiscovering) {
                bluetooth.cancelDiscovery()
            }

            devices.clear()
            scanning = true
            status = "Starting real Bluetooth discovery…"

            val started = bluetooth.startDiscovery()

            if (!started) {
                scanning = false
                status = "Android could not start discovery. Try again shortly."
            } else {
                status = "Discovery started. Waiting for nearby devices…"
            }
        } catch (_: SecurityException) {
            scanning = false
            status = "Bluetooth permission was denied. Allow it in Android settings."
        } catch (_: Exception) {
            scanning = false
            status = "Bluetooth discovery failed. Check Bluetooth and try again."
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopDiscoveryByUser() {
        try {
            adapter?.let {
                if (it.isEnabled && it.isDiscovering && hasRequiredPermissions()) {
                    it.cancelDiscovery()
                }
            }

            scanning = false
            status = "Scan stopped."
        } catch (_: SecurityException) {
            scanning = false
            status = "Unable to stop discovery because permission is missing."
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        try {
            if (hasRequiredPermissions()) {
                adapter?.let {
                    if (it.isEnabled && it.isDiscovering) {
                        it.cancelDiscovery()
                    }
                }
            }
        } catch (_: Exception) {
            // The activity is closing; there is nothing else to do.
        }

        if (receiverRegistered) {
            try {
                unregisterReceiver(discoveryReceiver)
            } catch (_: IllegalArgumentException) {
                // Receiver was already unregistered.
            }

            receiverRegistered = false
        }

        super.onDestroy()
    }
}
