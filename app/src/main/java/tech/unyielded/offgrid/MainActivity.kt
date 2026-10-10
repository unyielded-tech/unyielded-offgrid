package tech.unyielded.offgrid

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothServerSocket
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

private val Ink = Color(0xFF090B10)
private val Panel = Color(0xFF151922)
private val Lime = Color(0xFFC5FF54)
private val Muted = Color(0xFF969EAD)

class MainActivity : ComponentActivity() {

    private val devices = mutableStateListOf<BluetoothDevice>()
    private var status by mutableStateOf("Ready to connect")
    private var scanning by mutableStateOf(false)

    private val executor = Executors.newSingleThreadExecutor()
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
            if (result.values.all { it }) {
                startDiscovery()
            } else {
                status = "Bluetooth permissions are required to discover devices."
            }
        }

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device =
                        if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }

                    if (device != null && devices.none { it.address == device.address }) {
                        devices.add(device)
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    scanning = false
                    status = if (devices.isEmpty()) {
                        "Scan finished. No devices found."
                    } else {
                        "Found ${devices.size} device(s)."
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
                    onSurface = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Ink
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "UNYIELDED",
                                    color = Lime,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 4.sp
                                )
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    "// OFFGRID",
                                    color = Color.White,
                                    fontSize = 30.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }

                            Text(
                                "BETA 01",
                                color = Lime,
                                fontSize = 10.sp,
                                modifier = Modifier
                                    .background(Panel, RoundedCornerShape(8.dp))
                                    .padding(10.dp)
                            )
                        }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Panel),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    "NO INTERNET. NO CLOUD.",
                                    color = Lime,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.5.sp
                                )
                                Text(
                                    "Your nearby network starts here.",
                                    color = Color.White,
                                    fontSize = 21.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Discover nearby Bluetooth devices and prepare for direct, offline communication.",
                                    color = Muted,
                                    fontSize = 14.sp,
                                    lineHeight = 21.sp
                                )
                            }
                        }

                        Button(
                            onClick = { requestBluetoothPermissions() },
                            enabled = !scanning,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (scanning) "SCANNING..." else "SCAN NEARBY DEVICES")
                        }

                        Text(
                            status,
                            color = Muted,
                            fontSize = 13.sp
                        )

                        Text(
                            "NEARBY DEVICES",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )

                        if (devices.isEmpty()) {
                            Text(
                                "No devices listed yet.\nMake the other phone discoverable and scan again.",
                                color = Muted,
                                fontSize = 14.sp,
                                lineHeight = 22.sp
                            )
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(devices, key = { it.address }) { device ->
                                    DeviceRow(device = device)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        Text(
                            "OFFLINE FIRST  •  BUILT BY UNYIELDED",
                            color = Muted,
                            fontSize = 10.sp,
                            letterSpacing = 1.sp,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun DeviceRow(device: BluetoothDevice) {
        val deviceName = try {
            @SuppressLint("MissingPermission")
            device.name ?: "Unknown device"
        } catch (_: SecurityException) {
            "Bluetooth device"
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(deviceName, color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(device.address, color = Muted, fontSize = 11.sp)
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
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    private fun requestBluetoothPermissions() {
        if (adapter == null) {
            status = "This phone does not support Bluetooth."
            return
        }

        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) !=
                PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            startDiscovery()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        val bluetooth = adapter ?: run {
            status = "Bluetooth is unavailable."
            return
        }

        if (!bluetooth.isEnabled) {
            status = "Turn on Bluetooth in Android settings, then scan again."
            return
        }

        try {
            if (!receiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }

                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(
                        discoveryReceiver,
                        filter,
                        Context.RECEIVER_NOT_EXPORTED
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
            scanning = bluetooth.startDiscovery()

            status = if (scanning) {
                "Looking for nearby discoverable devices..."
            } else {
                "Could not start discovery. Check Bluetooth permissions."
            }
        } catch (e: SecurityException) {
            status = "Bluetooth permission denied."
            scanning = false
        } catch (e: Exception) {
            status = "Discovery error: ${e.message ?: "unknown error"}"
            scanning = false
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        try {
            adapter?.cancelDiscovery()
        } catch (_: Exception) {
        }

        if (receiverRegistered) {
            try {
                unregisterReceiver(discoveryReceiver)
            } catch (_: Exception) {
            }
        }

        executor.shutdownNow()
        super.onDestroy()
    }
}
