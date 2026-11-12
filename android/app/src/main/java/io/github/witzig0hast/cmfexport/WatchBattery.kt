package io.github.witzig0hast.cmfexport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Reads the watch battery over Bluetooth while the watch stays paired with the CMF app
 * (Android shares one LE link between several GATT clients). Uses the standard
 * Battery Service 0x180F; falls back to Android's own cached level for the device.
 */
object WatchBattery {
    private val BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    data class Reading(val level: Int, val device: String, val method: String, val time: Instant = Instant.now())

    fun hasPermission(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun watches(ctx: Context): List<BluetoothDevice> {
        if (!hasPermission(ctx)) return emptyList()
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        return adapter.bondedDevices.orEmpty().filter { d ->
            val n = (d.name ?: "").lowercase()
            "cmf" in n || "nothing" in n || "watch" in n
        }.sortedByDescending { "cmf" in (it.name ?: "").lowercase() }
    }

    @SuppressLint("MissingPermission")
    suspend fun read(ctx: Context, log: (String) -> Unit = {}): Reading? {
        if (!hasPermission(ctx)) { log("Uhr-Akku: Bluetooth-Berechtigung fehlt"); return null }
        val list = watches(ctx)
        if (list.isEmpty()) { log("Uhr-Akku: keine gekoppelte CMF-Uhr gefunden"); return null }
        for (d in list) {
            gatt(ctx, d)?.let { return Reading(it, d.name ?: d.address, "Bluetooth") }
            cached(d)?.let { return Reading(it, d.name ?: d.address, "Android") }
        }
        log("Uhr-Akku: Die Uhr gibt ihren Akkustand nicht über den Standard-Dienst heraus (nur über Gadgetbridge verfügbar).")
        return null
    }

    /** Android's own battery level for the device (hidden API, works for many devices). */
    private fun cached(d: BluetoothDevice): Int? = runCatching {
        (BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(d) as Int).takeIf { it in 0..100 }
    }.getOrNull()

    @SuppressLint("MissingPermission")
    private suspend fun gatt(ctx: Context, d: BluetoothDevice): Int? {
        val result = CompletableDeferred<Int?>()
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) result.complete(null)
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val c = g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL)
                if (c == null || !g.readCharacteristic(c)) result.complete(null)
            }
            @Deprecated("API < 33")
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                @Suppress("DEPRECATION")
                result.complete(c.value?.firstOrNull()?.toInt()?.and(0xFF).takeIf { status == BluetoothGatt.GATT_SUCCESS })
            }
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                result.complete(value.firstOrNull()?.toInt()?.and(0xFF).takeIf { status == BluetoothGatt.GATT_SUCCESS })
            }
        }
        val g = d.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE) ?: return null
        return try {
            withTimeoutOrNull(15_000) { result.await() }?.takeIf { it in 0..100 }
        } finally {
            runCatching { g.disconnect(); g.close() }
        }
    }

    /** Tiny upload for the server: battery.csv only. */
    fun zip(r: Reading, out: File): File {
        ZipOutputStream(out.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("battery.csv"))
            z.write("time,level,device,method\n${iso(r.time)},${r.level},\"${r.device.replace("\"", "")}\",${r.method}\n".toByteArray())
            z.closeEntry()
        }
        return out
    }
}
