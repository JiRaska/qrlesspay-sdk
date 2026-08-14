// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
package tech.qrlesspay.sdk

/**
 * QRlessPay (ADR-0095) phase-2 transport seam. The protocol core ([NearPayProtocol]) is pure;
 * this interface is the only thing that touches BLE. commonMain ([NearPayController]) drives it,
 * platform classes implement it (Android `android.bluetooth.le` + GATT; iOS CoreBluetooth), and
 * tests inject a fake — so the whole orchestration is unit-testable without a radio.
 *
 * Phase 1 (advert) carries only the opaque beacon bytes; phase 2 (GATT read) returns the signed
 * bundle bytes. The transport moves bytes and never interprets them — all encoding, signing and
 * verification stays in the protocol core.
 */
interface NearPayTransport {
    /** Receiver: advertise [beacon] under the QRlessPay service UUID and serve [bundle] over GATT. */
    fun startReceiving(beacon: ByteArray, bundle: ByteArray)
    fun stopReceiving()

    /** Payer: scan for receivers; [onPeers] fires on the main thread with the current peer list. */
    fun startDiscovery(onPeers: (List<DiscoveredPeer>) -> Unit)
    fun stopDiscovery()

    /** Payer: GATT-connect to [peerId] and read the signed bundle bytes, or null on any failure. */
    suspend fun fetchBundle(peerId: String): ByteArray?
}

/** A receiver discovered over BLE: a stable per-session [id], its raw advert [beacon] bytes, [rssi]. */
data class DiscoveredPeer(val id: String, val beacon: ByteArray, val rssi: Int) {
    override fun equals(other: Any?): Boolean = this === other ||
        (other is DiscoveredPeer && id == other.id && rssi == other.rssi && beacon.contentEquals(other.beacon))
    override fun hashCode(): Int = (id.hashCode() * 31 + rssi) * 31 + beacon.contentHashCode()
}

/** What the payer's "nearby" UI shows for a discovered receiver (decoded from its advert). */
data class NearbyTile(
    val peerId: String,
    val firstName: String,
    val amountMinor: Int?,
    val rssi: Int,
    internal val beacon: ByteArray,
) {
    override fun equals(other: Any?): Boolean = this === other ||
        (
            other is NearbyTile &&
                peerId == other.peerId &&
                firstName == other.firstName &&
                amountMinor == other.amountMinor &&
                rssi == other.rssi &&
                beacon.contentEquals(other.beacon)
            )
    override fun hashCode(): Int {
        var r = peerId.hashCode()
        r = 31 * r + firstName.hashCode()
        r = 31 * r + (amountMinor ?: 0)
        r = 31 * r + rssi
        r = 31 * r + beacon.contentHashCode()
        return r
    }
}
