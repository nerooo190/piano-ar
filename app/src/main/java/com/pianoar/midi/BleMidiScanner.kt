package com.pianoar.midi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid

/** Scans for Bluetooth LE MIDI instruments (standard BLE-MIDI service UUID). Needs BLUETOOTH_SCAN/CONNECT. */
@SuppressLint("MissingPermission")
class BleMidiScanner(context: Context, private val onFound: (BluetoothDevice) -> Unit) {

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val main = Handler(Looper.getMainLooper())
    private val seen = HashSet<String>()

    val isAvailable get() = adapter != null
    val isEnabled get() = adapter?.isEnabled == true

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            if (seen.add(d.address)) main.post { onFound(d) }
        }
    }

    fun start() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        seen.clear()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(MIDI_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, callback)
        main.postDelayed({ stop() }, 30_000)
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
    }

    companion object {
        const val MIDI_SERVICE = "03B80E5A-EDE8-4B33-A751-6CE34EC4C700"
    }
}
