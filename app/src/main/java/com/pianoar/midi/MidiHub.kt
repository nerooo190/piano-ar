package com.pianoar.midi

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper

/**
 * Opens every connected MIDI device (USB and Bluetooth LE) and forwards
 * note and sustain-pedal events on the main thread.
 */
class MidiHub(
    context: Context,
    private val onNote: (note: Int, velocity: Int, on: Boolean) -> Unit,
    private val onSustain: (on: Boolean) -> Unit,
    private val onDevicesChanged: (names: List<String>) -> Unit,
) {
    private val manager = context.getSystemService(Context.MIDI_SERVICE) as MidiManager?
    private val main = Handler(Looper.getMainLooper())
    private val devices = mutableMapOf<Int, Pair<MidiDevice, List<MidiOutputPort>>>()
    private val bleConnections = ArrayList<MidiDevice>()

    val isSupported get() = manager != null

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = open(device)
        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            devices.remove(device.id)?.let { (dev, ports) -> close(dev, ports) }
            publish()
        }
    }

    @Suppress("DEPRECATION")
    fun start() {
        val mm = manager ?: return publish()
        mm.registerDeviceCallback(callback, main)
        mm.devices.forEach(::open)
        publish()
    }

    fun stop() {
        manager?.unregisterDeviceCallback(callback)
        devices.values.forEach { (dev, ports) -> close(dev, ports) }
        devices.clear()
        bleConnections.forEach { runCatching { it.close() } }
        bleConnections.clear()
    }

    /** Connects a BLE-MIDI instrument. Android then reports it like any other MIDI device. */
    fun openBluetooth(device: BluetoothDevice) {
        val mm = manager ?: return
        mm.openBluetoothDevice(device, { dev ->
            if (dev == null) return@openBluetoothDevice
            bleConnections += dev
            // Fallback if the device callback does not report it.
            main.postDelayed({
                if (!devices.containsKey(dev.info.id)) {
                    devices[dev.info.id] = dev to openPorts(dev, dev.info)
                    publish()
                }
            }, 1500)
        }, main)
    }

    private fun close(dev: MidiDevice, ports: List<MidiOutputPort>) {
        ports.forEach { runCatching { it.close() } }
        if (dev !in bleConnections) runCatching { dev.close() }
    }

    private fun open(info: MidiDeviceInfo) {
        val mm = manager ?: return
        // Output ports are where a keyboard sends its notes to us.
        if (info.outputPortCount == 0 || devices.containsKey(info.id)) return
        mm.openDevice(info, { dev ->
            if (dev == null || devices.containsKey(info.id)) { dev?.close(); return@openDevice }
            devices[info.id] = dev to openPorts(dev, info)
            publish()
        }, main)
    }

    private fun openPorts(dev: MidiDevice, info: MidiDeviceInfo) =
        (0 until info.outputPortCount).mapNotNull { i -> dev.openOutputPort(i)?.also { it.connect(Parser()) } }

    private fun publish() {
        onDevicesChanged(devices.values.map { (dev, _) ->
            dev.info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI-Gerät"
        })
    }

    /** Minimal MIDI byte-stream parser with running status. One instance per port. */
    private inner class Parser : MidiReceiver() {
        private var status = 0
        private val data = IntArray(2)
        private var count = 0

        override fun onSend(msg: ByteArray, offset: Int, length: Int, timestamp: Long) {
            for (i in offset until offset + length) {
                val b = msg[i].toInt() and 0xFF
                when {
                    b >= 0xF8 -> Unit                       // realtime: clock, active sensing
                    b >= 0xF0 -> { status = 0; count = 0 }  // sysex/common: ignore
                    b >= 0x80 -> { status = b; count = 0 }
                    status != 0 -> {
                        data[count++] = b
                        val hi = status and 0xF0
                        val needed = if (hi == 0xC0 || hi == 0xD0) 1 else 2
                        if (count == needed) {
                            dispatch(hi, data[0], data[1])
                            count = 0
                        }
                    }
                }
            }
        }

        private fun dispatch(hi: Int, d1: Int, d2: Int) {
            when (hi) {
                0x90 -> main.post { onNote(d1, d2, d2 > 0) }
                0x80 -> main.post { onNote(d1, 0, false) }
                0xB0 -> if (d1 == 64) main.post { onSustain(d2 >= 64) }
            }
        }
    }
}
