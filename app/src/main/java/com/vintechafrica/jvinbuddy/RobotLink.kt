package com.vintechafrica.jvinbuddy

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Talks to the ESP32 body over Bluetooth Low Energy (Nordic UART service).
 * WiFi stays free, so the phone reaches the internet over home WiFi or mobile
 * data, and the robot works with no internet at all.
 *
 * First run: scans for the body and saves its address.
 * After that: connects straight to the saved address, no scan needed.
 * Sends "D,left,right" ten times a second. The ESP32 stops on its own if
 * these stop arriving for 400 ms.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class RobotLink(
    private val context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    val connected = MutableStateFlow(false)
    val battery = MutableStateFlow<Float?>(null)
    val manual = MutableStateFlow(false)
    val status = MutableStateFlow("starting")

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var running = false
    private var sendJob: Job? = null
    private var failures = 0
    private var connectTimeout: Runnable? = null

    @Volatile private var left = 0
    @Volatile private var right = 0

    fun setDrive(l: Int, r: Int) {
        left = l.coerceIn(-255, 255)
        right = r.coerceIn(-255, 255)
    }

    fun stop() = setDrive(0, 0)

    fun start() {
        if (running) return
        running = true
        connectOrScan()
        sendJob = scope.launch {
            while (isActive) {
                write("D,$left,$right")
                delay(100)
            }
        }
    }

    fun shutdown() {
        running = false
        sendJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        stopScan()
        write("S")
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        rx = null
        connected.value = false
    }

    // ------------------------------------------------------------ connect
    private fun connectOrScan() {
        if (!running || gatt != null || scanning) return
        val a = adapter
        if (a == null) {
            status.value = "no bluetooth on this phone"
            return
        }
        if (!a.isEnabled) {
            status.value = "bluetooth is off"
            retryLater(3000)
            return
        }
        val saved = prefs.robotAddress
        if (saved.isNotBlank() && BluetoothAdapter.checkBluetoothAddress(saved)) {
            connect(a.getRemoteDevice(saved))
        } else {
            scan(a)
        }
    }

    private fun connect(device: BluetoothDevice) {
        status.value = "connecting"
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        // If nothing happens in 10 s, give up and try again.
        connectTimeout?.let { handler.removeCallbacks(it) }
        val timeout = Runnable {
            if (!connected.value && gatt != null) {
                Log.w(TAG, "Connect timeout")
                closeGatt()
                onFailure()
            }
        }
        connectTimeout = timeout
        handler.postDelayed(timeout, 10_000)
    }

    private fun scan(a: BluetoothAdapter) {
        val scanner = a.bluetoothLeScanner ?: return retryLater(3000)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !locationOn()) {
            status.value = "turn on location to pair (first time only)"
            retryLater(3000)
            return
        }
        status.value = "searching for body"
        scanning = true
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Scan failed to start", e)
            scanning = false
            retryLater(3000)
            return
        }
        handler.postDelayed({
            if (scanning) {
                stopScan()
                retryLater(1000)
            }
        }, 10_000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            stopScan()
            Log.i(TAG, "Found body ${result.device.address}")
            prefs.robotAddress = result.device.address
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Scan failed $errorCode")
            scanning = false
            retryLater(3000)
        }
    }

    private fun locationOn(): Boolean {
        val lm = context.getSystemService(LocationManager::class.java) ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled else true
    }

    private fun retryLater(ms: Long = 2000) {
        if (!running) return
        handler.postDelayed({ connectOrScan() }, ms)
    }

    private fun onFailure() {
        failures++
        // A saved address that keeps failing may be a different board. Rescan.
        if (failures >= 5 && prefs.robotAddress.isNotBlank()) {
            Log.w(TAG, "Saved body unreachable, scanning again")
            prefs.robotAddress = ""
            failures = 0
        }
        status.value = "reconnecting"
        retryLater(2000)
    }

    private fun closeGatt() {
        gatt?.close()
        gatt = null
        rx = null
        connected.value = false
        manual.value = false
    }

    // ------------------------------------------------------------ GATT
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    g.discoverServices()
                } else {
                    Log.w(TAG, "Disconnected, status $status")
                    if (gatt == g) closeGatt() else g.close()
                    onFailure()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                val svc = g.getService(SERVICE)
                val rxChar = svc?.getCharacteristic(RX)
                val txChar = svc?.getCharacteristic(TX)
                if (rxChar == null || txChar == null) {
                    Log.w(TAG, "Not a JVin Buddy body")
                    prefs.robotAddress = ""
                    g.disconnect()
                    return@post
                }
                rx = rxChar
                g.setCharacteristicNotification(txChar, true)
                val cccd = txChar.getDescriptor(CCCD)
                if (cccd == null) {
                    markConnected()
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            handler.post { markConnected() }
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            parse(String(value))
        }

        // Android 12 and older
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            parse(String(c.value ?: return))
        }
    }

    private fun markConnected() {
        if (connected.value) return
        failures = 0
        connected.value = true
        status.value = "body ok"
        Log.i(TAG, "Body connected")
    }

    private fun parse(msg: String) {
        val m = msg.trim()
        when {
            m.startsWith("B,") -> battery.value = m.substring(2).toFloatOrNull()?.takeIf { it > 0f }
            m == "M,1" -> manual.value = true
            m == "M,0" -> manual.value = false
        }
    }

    private fun write(text: String) {
        val g = gatt ?: return
        val c = rx ?: return
        if (!connected.value) return
        val bytes = text.toByteArray()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                c.value = bytes
                g.writeCharacteristic(c)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Write failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "RobotLink"
        val SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
