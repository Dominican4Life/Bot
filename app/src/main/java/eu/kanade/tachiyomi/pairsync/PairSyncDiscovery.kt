package eu.kanade.tachiyomi.pairsync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.security.MessageDigest
import java.util.UUID

/**
 * KMK (r89): Lets the device entering a code spot the device showing one. While a code shows, that device advertises
 * over Bluetooth LE a 4-byte hash of its Bluetooth name (the name classic discovery shows; the name itself doesn't
 * fit in an advertisement). The other device scans for that service data and tags the found devices whose name has
 * the same hash. Everything is best effort: a device without LE advertising still pairs, just without the tag.
 */
@SuppressLint("MissingPermission")
object PairSyncDiscovery {

    /** Service data key of the advertisement (not the RFCOMM service UUID) */
    private val SERVICE_DATA_UUID: ParcelUuid = ParcelUuid(UUID.fromString("3a7c9e21-5d4b-4f86-b0e3-8c1f2a6d9b47"))

    /** Device kinds that can't run the app: shown under "other devices" */
    private val HIDDEN_MAJOR = setOf(
        BluetoothClass.Device.Major.AUDIO_VIDEO,
        BluetoothClass.Device.Major.WEARABLE,
        BluetoothClass.Device.Major.HEALTH,
        BluetoothClass.Device.Major.TOY,
        BluetoothClass.Device.Major.PERIPHERAL,
        BluetoothClass.Device.Major.IMAGING,
    )

    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null

    /** The name other devices see for this one in classic discovery */
    fun bluetoothName(context: Context): String =
        runCatching { PairSync.adapter(context)?.name }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: PairSync.deviceName(context)

    /** A stable 4-byte hash of a Bluetooth name */
    private fun hashBytes(name: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(name.trim().toByteArray(Charsets.UTF_8)).copyOf(4)

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    /** The hash of [name] as hex, compared with what the scan found */
    fun nameHash(name: String): String = hashBytes(name).hex()

    /** True for headphones, TVs, watches and the like (they go in the hidden group) */
    fun isUnlikely(device: BluetoothDevice): Boolean =
        runCatching { device.bluetoothClass?.majorDeviceClass?.let { it in HIDDEN_MAJOR } == true }.getOrDefault(false)

    /** Starts advertising this device's name hash, until [stopAdvertising] */
    fun startAdvertising(context: Context) {
        stopAdvertising()
        runCatching {
            val leAdvertiser = PairSync.adapter(context)?.bluetoothLeAdvertiser ?: return
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .setTimeout(0)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceData(SERVICE_DATA_UUID, hashBytes(bluetoothName(context)))
                .build()
            val callback = object : AdvertiseCallback() {}
            leAdvertiser.startAdvertising(settings, data, callback)
            advertiser = leAdvertiser
            advertiseCallback = callback
        }
    }

    fun stopAdvertising() {
        val callback = advertiseCallback
        if (callback != null) runCatching { advertiser?.stopAdvertising(callback) }
        advertiser = null
        advertiseCallback = null
    }

    /** A scan for devices showing a code; [hashes] are the name hashes found so far */
    class Scan(private val context: Context) {
        private val _hashes = MutableStateFlow<Set<String>>(emptySet())
        val hashes: StateFlow<Set<String>> = _hashes.asStateFlow()

        private var scanner: BluetoothLeScanner? = null

        private val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                add(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::add)
            }
        }

        private fun add(result: ScanResult) {
            val data = runCatching { result.scanRecord?.getServiceData(SERVICE_DATA_UUID) }.getOrNull() ?: return
            if (data.size < 4) return
            val hash = data.copyOf(4).hex()
            _hashes.update { if (hash in it) it else it + hash }
        }

        fun start() {
            runCatching {
                val leScanner = PairSync.adapter(context)?.bluetoothLeScanner ?: return
                val filter = ScanFilter.Builder().setServiceData(SERVICE_DATA_UUID, ByteArray(0)).build()
                val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
                leScanner.startScan(listOf(filter), settings, callback)
                scanner = leScanner
            }
        }

        fun stop() {
            runCatching { scanner?.stopScan(callback) }
            scanner = null
        }
    }
}
