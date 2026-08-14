// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package tech.qrlesspay.sdk

/**
 * QRlessPay (ADR-0095) phase-2 orchestrator. Bridges the pure protocol core
 * ([NearPayProtocol]) to a [NearPayTransport], so the receiver/payer flows are one
 * shared, testable implementation across iOS and Android.
 *
 * Receiver: [startReceiving] mints a session and advertises it. Payer: [startDiscovery]
 * surfaces nearby tiles; [resolve] fetches + verifies the signed bundle and yields the
 * SPAYD to pre-fill a payment proposal. No money moves here — the caller still confirms + signs.
 *
 * @param now epoch-seconds clock (injected for testability).
 * @param random secure random bytes (injected; production wires a platform CSPRNG).
 */
class NearPayController(
    private val transport: NearPayTransport,
    private val random: RandomBytes,
    private val now: () -> Long,
    /**
     * Single-use tracking for verified bundles. Owned per controller, so it survives across
     * tile taps within one payer screen — which is the window a double payment happens in —
     * and is discarded with the screen. Injectable so a test can drive its clock.
     */
    private val replayGuard: ReplayGuard = TtlReplayGuard(now),
) {
    // ── Receiver ────────────────────────────────────────────────────────────────
    fun startReceiving(firstName: String, spayd: String, amountMinor: Int? = null) {
        val minted = NearPayProtocol.mint(
            random = random,
            firstName = firstName,
            spayd = spayd,
            nowEpochSec = now(),
            amountMinor = amountMinor,
        )
        transport.startReceiving(BeaconCodec.encode(minted.advert), minted.bundle.toCbor())
    }

    fun stopReceiving() = transport.stopReceiving()

    // ── Payer ───────────────────────────────────────────────────────────────────
    /**
     * Surface discovered receivers as UI tiles (strongest signal first); undecodable adverts
     * dropped, and anything below [NearPay.RSSI_GATE_DBM] withheld — the spec §5 proximity
     * baseline is applied here so no payer surface can present a tile that failed it.
     */
    fun startDiscovery(onTiles: (List<NearbyTile>) -> Unit) {
        transport.startDiscovery { peers ->
            onTiles(
                peers.mapNotNull { p ->
                    BeaconCodec.decode(p.beacon)?.let { b ->
                        NearbyTile(p.id, b.name, b.amountMinor, p.rssi, p.beacon)
                    }
                }.filter { it.rssi >= NearPay.RSSI_GATE_DBM }
                    .sortedByDescending { it.rssi },
            )
        }
    }

    fun stopDiscovery() = transport.stopDiscovery()

    /**
     * Fetch the selected receiver's signed bundle over GATT and verify it against the advert
     * (binding), expiry, signature and single use. Returns [VerifyResult.Ok] with the SPAYD to
     * pre-fill, or a [VerifyResult.Rejected] with a stable reason — `"replayed"` for a bundle
     * this device already accepted, which is distinct from `"expired"` because the two mean
     * different things to the person holding the phone. NOTE: a proximity gate (RSSI/UWB) and
     * the mandatory payer confirmation + SCA are applied by the UI layer on top of this.
     */
    suspend fun resolve(tile: NearbyTile): VerifyResult {
        val advert = BeaconCodec.decode(tile.beacon) ?: return VerifyResult.Rejected("bad-advert")
        val bundleBytes = transport.fetchBundle(tile.peerId) ?: return VerifyResult.Rejected("fetch-failed")
        val bundle = NearPayBundle.fromCbor(bundleBytes) ?: return VerifyResult.Rejected("bad-bundle")
        return NearPayProtocol.verify(advert, bundle, now(), replayGuard)
    }
}
