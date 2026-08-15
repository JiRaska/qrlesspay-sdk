// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package tech.qrlesspay.sdk

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Android QRlessPay transport — `android.bluetooth.le` plus GATT.
 *
 * **Payee**: advertises [NearPay.SERVICE_UUID] as the scan filter, with the beacon bytes as service
 * data under the 16-bit alias, and runs a connectable GATT server whose single read characteristic
 * returns the signed bundle. Android carries service data in an advertisement where iOS does not;
 * a conformant scanner accepts both that and the iOS local-name carriage (spec §2).
 *
 * **Payer**: scans for the service UUID, takes the beacon straight from the scan record, and on
 * selection connects and reads the bundle over GATT.
 *
 * **Best-effort by design.** Missing permission, Bluetooth off, or no hardware is a silent no-op
 * rather than an exception: the payee role is unavailable on a meaningful share of Android devices,
 * and a profile that throws there would make every host app write the same try/catch. Failing
 * closed (no advert, no tiles) is the safe direction — it degrades to the QR fallback.
 *
 * The [Context] is a constructor parameter, not a global. The app this was extracted from reads one
 * from a process-wide holder, which is convenient for an app and wrong for a library: a dependency
 * that installs a singleton on its consumer has made a lifetime decision it has no standing to make.
 */
class AndroidNearPayTransport(context: Context) : NearPayTransport {

    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val serviceUuid: UUID = UUID.fromString(NearPay.SERVICE_UUID)
    private val charUuid: UUID = UUID.fromString(NearPay.CHAR_BUNDLE_UUID)
    private val dataParcelUuid = ParcelUuid(UUID.fromString(NearPay.DATA_UUID_16))
    private val serviceParcelUuid = ParcelUuid(serviceUuid)

    private fun adapter() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun hasPermission(vararg perms: String) =
        perms.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    // ── Payee: advertise + GATT server ──────────────────────────────────────────

    private var advertiseCallback: AdvertiseCallback? = null
    private var gattServer: BluetoothGattServer? = null

    @Volatile
    private var servedBundle: ByteArray = ByteArray(0)

    override fun startReceiving(beacon: ByteArray, bundle: ByteArray) {
        if (Build.VERSION.SDK_INT >= ANDROID_12 &&
            !hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            return
        }
        servedBundle = bundle
        startGattServer()
        val advertiser = adapter()?.takeIf { it.isEnabled }?.bluetoothLeAdvertiser ?: return
        stopAdvertising()
        val cb = object : AdvertiseCallback() {}
        swallowingPlatformFailures {
            advertiser.startAdvertising(
                AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setConnectable(true) // the payer connects over GATT to read the bundle
                    .setTimeout(0)
                    .build(),
                AdvertiseData.Builder().addServiceUuid(serviceParcelUuid).setIncludeDeviceName(false).build(),
                // Beacon goes in the scan response: the primary advert's 31 bytes are spent on the
                // service UUID, and including the device name would leak far more than a first name.
                AdvertiseData.Builder().addServiceData(dataParcelUuid, beacon).build(),
                cb,
            )
            advertiseCallback = cb
        }
    }

    override fun stopReceiving() {
        stopAdvertising()
        swallowingPlatformFailures { gattServer?.close() }
        gattServer = null
        servedBundle = ByteArray(0)
    }

    private fun startGattServer() {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        swallowingPlatformFailures {
            val server = mgr.openGattServer(context, gattServerCallback) ?: return
            val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(
                BluetoothGattCharacteristic(
                    charUuid,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ,
                ),
            )
            server.addService(service)
            gattServer = server
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            // The bundle exceeds a default ATT MTU, so the stack reads it in offset chunks. Answering
            // the whole value to a non-zero offset silently corrupts the payload for the payer.
            val value = if (characteristic.uuid == charUuid && offset <= servedBundle.size) {
                servedBundle.copyOfRange(offset, servedBundle.size)
            } else {
                ByteArray(0)
            }
            swallowingPlatformFailures {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    private fun stopAdvertising() {
        advertiseCallback?.let { cb ->
            swallowingPlatformFailures { adapter()?.bluetoothLeAdvertiser?.stopAdvertising(cb) }
        }
        advertiseCallback = null
    }

    // ── Payer: scan ─────────────────────────────────────────────────────────────

    private var scanCallback: ScanCallback? = null
    private var onPeers: ((List<DiscoveredPeer>) -> Unit)? = null
    private val peers = mutableMapOf<String, Pair<DiscoveredPeer, Long>>()

    private val prune = object : Runnable {
        override fun run() {
            val cutoff = System.currentTimeMillis() - PEER_TTL_MS
            if (peers.values.removeAll { (_, seen) -> seen < cutoff }) emit()
            handler.postDelayed(this, PRUNE_INTERVAL_MS)
        }
    }

    override fun startDiscovery(onPeers: (List<DiscoveredPeer>) -> Unit) {
        // Below Android 12 a BLE scan is a location request, so the permission differs by version.
        // Above it, neverForLocation in the manifest is what keeps this from asking for location to
        // accept a payment — a dialog nobody grants.
        val needed = if (Build.VERSION.SDK_INT >= ANDROID_12) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (!hasPermission(*needed)) return
        val scanner = adapter()?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        stopDiscovery()
        this.onPeers = onPeers

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val beacon = result.scanRecord?.getServiceData(dataParcelUuid) ?: return
                val peer = DiscoveredPeer(result.device.address, beacon, result.rssi)
                handler.post {
                    peers[peer.id] = peer to System.currentTimeMillis()
                    emit()
                }
            }
        }
        swallowingPlatformFailures {
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(serviceParcelUuid).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                cb,
            )
            scanCallback = cb
            handler.postDelayed(prune, PRUNE_INTERVAL_MS)
        }
    }

    override fun stopDiscovery() {
        onPeers = null
        handler.removeCallbacks(prune)
        peers.clear()
        scanCallback?.let { cb -> swallowingPlatformFailures { adapter()?.bluetoothLeScanner?.stopScan(cb) } }
        scanCallback = null
    }

    private fun emit() {
        // Ordering is a convenience; the proximity gate itself lives in NearPayController, so no
        // screen can present a tile that failed it by forgetting to filter.
        onPeers?.invoke(peers.values.map { it.first }.sortedByDescending { it.rssi })
    }

    // ── Payer: GATT read ────────────────────────────────────────────────────────

    override suspend fun fetchBundle(peerId: String): ByteArray? {
        if (Build.VERSION.SDK_INT >= ANDROID_12 && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return null
        val device = swallowingPlatformFailures { adapter()?.getRemoteDevice(peerId) } ?: return null

        return withTimeoutOrNull(GATT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                var gatt: BluetoothGatt? = null

                // Disconnect, close, resume — one exit path for every outcome. Calling disconnect()
                // fires onConnectionStateChange(DISCONNECTED), by which point cont.isActive is
                // already false, so the guard there is a safe no-op rather than a double resume.
                fun done(g: BluetoothGatt, value: ByteArray?) {
                    swallowingPlatformFailures { g.disconnect() }
                    swallowingPlatformFailures { g.close() }
                    if (cont.isActive) cont.resume(value)
                }

                val cb = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> swallowingPlatformFailures { g.discoverServices() }
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                // Remote-initiated: close and resume. Ours: isActive is already false.
                                swallowingPlatformFailures { g.close() }
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    }

                    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                        val ch = g.getService(serviceUuid)?.getCharacteristic(charUuid)
                        if (ch == null) {
                            done(g, null)
                            return
                        }
                        swallowingPlatformFailures { g.readCharacteristic(ch) }
                    }

                    // API 33+ hands the value directly; characteristic.getValue() is stale there.
                    // Both overloads are required — a device on either side of 33 uses only one, and
                    // implementing just the modern one silently never delivers on older hardware.
                    @Suppress("NewApi")
                    override fun onCharacteristicRead(
                        g: BluetoothGatt,
                        ch: BluetoothGattCharacteristic,
                        value: ByteArray,
                        status: Int,
                    ) {
                        done(g, if (status == BluetoothGatt.GATT_SUCCESS) value else null)
                    }

                    @Suppress("DEPRECATION")
                    override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                        done(g, if (status == BluetoothGatt.GATT_SUCCESS) ch.value else null)
                    }
                }

                gatt = swallowingPlatformFailures { device.connectGatt(context, false, cb) }
                if (gatt == null && cont.isActive) cont.resume(null)
                cont.invokeOnCancellation {
                    swallowingPlatformFailures {
                        gatt?.disconnect()
                        gatt?.close()
                    }
                }
            }
        }
    }

    /**
     * Runs a platform call whose failure is not actionable — Bluetooth switched off mid-call, a
     * revoked permission, a vendor stack throwing where the API says it returns.
     *
     * Cancellation is rethrown deliberately: swallowing it would leave a coroutine that cannot be
     * cancelled, which is a worse bug than the one this is catching.
     */
    private inline fun <T> swallowingPlatformFailures(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        null
    }

    private companion object {
        const val ANDROID_12 = 31
        const val PEER_TTL_MS = 6_000L
        const val PRUNE_INTERVAL_MS = 2_000L
        const val GATT_TIMEOUT_MS = 8_000L
    }
}
