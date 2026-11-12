package io.github.witzig0hast.cmftrack

import android.Manifest
import android.annotation.SuppressLint
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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.util.UUID

/**
 * Live heart rate over the standard Bluetooth Heart Rate Service (0x180D):
 * watches in "heart rate broadcast" mode, chest straps, arm bands.
 */
class HeartRateBle(private val ctx: Context, private val onHr: (Int) -> Unit, private val onStatus: (String) -> Unit) {
    companion object {
        val SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

        fun granted(ctx: Context) = permissions().all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

        /** Parses a Heart Rate Measurement characteristic (flags bit 0: 16-bit value). */
        fun parse(v: ByteArray): Int? {
            if (v.isEmpty()) return null
            return if (v[0].toInt() and 1 == 0) v.getOrNull(1)?.toInt()?.and(0xFF)
            else if (v.size >= 3) (v[1].toInt() and 0xFF) or ((v[2].toInt() and 0xFF) shl 8) else null
        }
    }

    private val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private var stopped = false
    private val main = Handler(Looper.getMainLooper())

    private val scanCb = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            stopScan()
            onStatus("Verbinde mit ${result.device.name ?: result.device.address} …")
            connect(result.device)
        }
        override fun onScanFailed(errorCode: Int) = onStatus("Bluetooth-Suche fehlgeschlagen ($errorCode)")
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!granted(ctx)) { onStatus("Bluetooth-Berechtigung fehlt"); return }
        if (adapter?.isEnabled != true) { onStatus("Bluetooth ist aus"); return }
        stopped = false
        onStatus("Suche Pulssender …")
        adapter.bluetoothLeScanner?.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        main.postDelayed({ if (gatt == null && !stopped) { stopScan(); onStatus("Kein Pulssender gefunden – Puls wird nach dem Training von der Uhr geladen") } }, 20_000)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) }
    }

    @SuppressLint("MissingPermission")
    private fun connect(d: BluetoothDevice) {
        gatt = d.connectGatt(ctx, true, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
                else if (newState == BluetoothProfile.STATE_DISCONNECTED && !stopped) onStatus("Pulssender getrennt – verbinde neu …")
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val c = g.getService(SERVICE)?.getCharacteristic(MEASUREMENT) ?: return onStatus("Gerät liefert keinen Puls")
                g.setCharacteristicNotification(c, true)
                val desc = c.getDescriptor(CCCD) ?: return
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                else @Suppress("DEPRECATION") run { desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(desc) }
                onStatus("Puls verbunden: ${d.name ?: d.address}")
            }
            @Deprecated("API < 33")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION") c.value?.let { parse(it) }?.let(onHr)
            }
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                parse(value)?.let(onHr)
            }
        }, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        stopScan()
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
    }
}

/** Heart rate the watch recorded (synced by the CMF app into Health Connect). */
object WatchHeartRate {
    val PERMISSION = HealthPermission.getReadPermission(HeartRateRecord::class)
    private val CMF = listOf("com.nothing.cmf.watch", "com.nothing.smartcenter", "com.nothing.cmf")

    /** Returns samples in [start, end] and the app they came from; prefers the CMF app. */
    suspend fun load(client: HealthConnectClient, start: Long, end: Long): Pair<List<Pair<Long, Int>>, String?> {
        val bySource = mutableMapOf<String, MutableList<Pair<Long, Int>>>()
        var token: String? = null
        do {
            val r = client.readRecords(ReadRecordsRequest(HeartRateRecord::class,
                TimeRangeFilter.between(Instant.ofEpochMilli(start - 60_000), Instant.ofEpochMilli(end + 60_000)), pageToken = token))
            r.records.forEach { rec ->
                val list = bySource.getOrPut(rec.metadata.dataOrigin.packageName) { mutableListOf() }
                rec.samples.forEach { s -> list += s.time.toEpochMilli() to s.beatsPerMinute.toInt() }
            }
            token = r.pageToken
        } while (!token.isNullOrEmpty())
        val src = bySource.keys.firstOrNull { k -> CMF.any { k.startsWith(it) } }
            ?: bySource.maxByOrNull { it.value.size }?.key ?: return emptyList<Pair<Long, Int>>() to null
        return bySource[src]!!.filter { it.first in start..end }.sortedBy { it.first } to src
    }
}
