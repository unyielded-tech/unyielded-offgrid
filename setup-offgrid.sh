#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

APP="unyielded-offgrid"
PKG="tech.unyielded.offgrid"
ORG="unyielded-tech"
ROOT="$HOME/projects/$APP"

echo "=========================================="
echo " UNYIELDED // OFFGRID"
echo " Android project bootstrap"
echo "=========================================="

for cmd in git gh java gradle; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "ERROR: Missing command: $cmd"
    exit 1
  fi
done

gh auth status >/dev/null 2>&1 || {
  echo "ERROR: GitHub authentication is required."
  exit 1
}

mkdir -p "$ROOT"
cd "$ROOT"

if [ -n "$(git status --porcelain 2>/dev/null || true)" ]; then
  if [ -d .git ]; then
    echo "ERROR: This directory already contains Git changes."
    echo "Back up or inspect the existing project before continuing."
    exit 1
  fi
fi

if gh repo view "$ORG/$APP" >/dev/null 2>&1; then
  echo "ERROR: Repository $ORG/$APP already exists."
  echo "Stopping to avoid overwriting an existing project."
  exit 1
fi

if [ -d .git ]; then
  echo "ERROR: Existing Git repository detected."
  echo "Stopping to protect existing work."
  exit 1
fi

echo
echo "[1/8] Creating Android project directories..."

mkdir -p \
  app/src/main/java/tech/unyielded/offgrid \
  app/src/main/res/values \
  .github/workflows

echo
echo "[2/8] Writing Gradle configuration..."

cat > settings.gradle.kts <<'EOF'
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "UNYIELDED-OFFGRID"
include(":app")
EOF

cat > build.gradle.kts <<'EOF'
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
EOF

cat > gradle.properties <<'EOF'
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=true
kotlin.code.style=official
EOF

cat > app/build.gradle.kts <<'EOF'
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "tech.unyielded.offgrid"
    compileSdk = 35

    defaultConfig {
        applicationId = "tech.unyielded.offgrid"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
EOF

echo
echo "[3/8] Writing Android manifest..."

cat > app/src/main/AndroidManifest.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-feature
        android:name="android.hardware.bluetooth"
        android:required="false" />

    <uses-permission android:name="android.permission.BLUETOOTH"
        android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN"
        android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
        android:maxSdkVersion="30" />

    <uses-permission
        android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

    <application
        android:allowBackup="false"
        android:label="UNYIELDED // OFFGRID"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">

            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>

        </activity>
    </application>
</manifest>
EOF

cat > app/src/main/res/values/strings.xml <<'EOF'
<resources>
    <string name="app_name">UNYIELDED // OFFGRID</string>
</resources>
EOF

echo
echo "[4/8] Writing initial native application..."

cat > app/src/main/java/tech/unyielded/offgrid/MainActivity.kt <<'EOF'
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
EOF

echo
echo "[5/8] Writing GitHub Actions workflow..."

cat > .github/workflows/android.yml <<'EOF'
name: Build Android APK

on:
  push:
    branches: [main]
  workflow_dispatch:

permissions:
  contents: read

jobs:
  build:
    name: Build debug APK
    runs-on: ubuntu-latest
    timeout-minutes: 30

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: gradle

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.9'

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3

      - name: Install Android SDK packages
        run: sdkmanager "platforms;android-35" "build-tools;35.0.0"

      - name: Build debug APK
        run: gradle --no-daemon --stacktrace assembleDebug

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: unyielded-offgrid-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk
          if-no-files-found: error
          retention-days: 14
EOF

cat > .gitignore <<'EOF'
.gradle/
build/
**/build/
local.properties
.idea/
*.iml
.DS_Store
EOF

cat > README.md <<'EOF'
# UNYIELDED // OFFGRID

An Android-first, offline communication project.

## Current milestone

- Native Kotlin and Jetpack Compose foundation.
- Bluetooth permission handling.
- Nearby Bluetooth device discovery.
- GitHub Actions debug APK build.

## Important

This initial milestone discovers nearby discoverable Bluetooth devices.
It does not yet implement message transport, end-to-end encryption,
voice notes, file transfer, or mesh routing.

Do not treat device discovery as proof of secure messaging.

## Build

Push to `main`, then open the repository's Actions tab.
Download the `unyielded-offgrid-debug-apk` artifact from a successful run.

The debug APK is for testing and is not a Play Store release.
EOF

echo
echo "[6/8] Generating Gradle wrapper..."

gradle --no-daemon wrapper --gradle-version 8.9 --distribution-type bin

echo
echo "[7/8] Creating GitHub repository..."

gh repo create "$ORG/$APP" \
  --public \
  --description "UNYIELDED // OFFGRID — Android-first offline Bluetooth communication" \
  --source "$ROOT" \
  --remote origin \
  --push

echo
echo "[8/8] Checking repository..."

git status --short
gh repo view "$ORG/$APP" --json nameWithOwner,url,defaultBranchRef \
  --jq '"Repository: " + .nameWithOwner + "\nURL: " + .url + "\nBranch: " + .defaultBranchRef.name'

echo
echo "=========================================="
echo " PROJECT PUSHED"
echo "=========================================="
echo "Open:"
echo "https://github.com/$ORG/$APP/actions"
echo
echo "Wait for the Android build to finish."
echo "Then open the successful workflow run,"
echo "scroll to Artifacts, and download the APK."
