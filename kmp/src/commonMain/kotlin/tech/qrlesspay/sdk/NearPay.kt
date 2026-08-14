// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package tech.qrlesspay.sdk

/**
 * QRlessPay (ADR-0095) protocol constants — the wire profile from
 * `docs/specs/qrlesspay-v1.md`. Phase 1: the pure, platform-independent protocol
 * core (beacon codec + signed CBOR bundle + advert↔bundle binding + verification).
 * Transport (BLE advert + GATT) and UI come in later phases.
 */
object NearPay {
    // 128-bit service / characteristic UUIDs (placeholders until a real allocation is registered).
    const val SERVICE_UUID = "0000C3A4-2F3B-4E8A-9A5E-0B0E6F1C2D3A"
    const val CHAR_BUNDLE_UUID = "0000C3A5-2F3B-4E8A-9A5E-0B0E6F1C2D3A"

    /** 16-bit advert service-data alias (0xF0B2 on the BT base UUID). A 128-bit service-data
     *  UUID would blow the 31-byte advert budget; the 16-bit alias leaves room for the beacon. */
    const val DATA_UUID_16 = "0000F0B2-0000-1000-8000-00805F9B34FB"

    const val VERSION = 1

    // Advert budget (≤ ~27 B usable): verFlags(1) + nameLen(1) + name(≤12) + sid(4) + keyHash(2) + amount(3).
    const val NAME_MAX_BYTES = 12
    const val SID_BYTES = 4
    const val KEYHASH_BYTES = 2
    const val AMOUNT_MAX_MINOR = 16_777_215 // 3-byte big-endian cap

    const val NONCE_BYTES = 16
    const val SEED_BYTES = 32
    const val PUBKEY_BYTES = 32
    const val SIG_BYTES = 64

    const val MAX_TTL_SECONDS = 90L

    /**
     * Baseline proximity gate in dBm — anything weaker is not offered to the payer at all.
     *
     * Wire spec §5: RSSI is the required anti-relay baseline and is *necessary, not sufficient*
     * (an attacker can raise TX power), so it is always paired with the payer's confirmation.
     * It is enforced in [NearPayController.startDiscovery] rather than in a screen, so a second
     * payer surface cannot render ungated tiles by forgetting to filter.
     */
    const val RSSI_GATE_DBM = -70

    // Advert flag nibble.
    const val FLAG_AMOUNT = 0x1

    /**
     * RESERVED, never emitted and never read. Was "bank-attested"; the attestation path is out
     * of v1 because it needed a trust anchor and every anchor is interbank coordination, which
     * costs the SPAYD-portability the profile exists for (wire spec §11). Kept reserved rather
     * than retired so the decision is reversible if a scheme registry ever appears.
     */
    const val FLAG_RESERVED_ATTESTED = 0x2
    const val FLAG_SAS = 0x4

    /**
     * Master switch for the PAYER half (scan → tiles → resolve). Off until the QRlessPay
     * rollout gates in `docs/threat-models/qrlesspay.md` §8 are met — independent crypto
     * review, CBOR fuzzing, ADR-0030 second approval and DPIA sign-off.
     *
     * The threat model already described the feature as dormant "with the feature flag off";
     * until this existed that was not true — dormancy was accidental, resting on the fact that
     * nothing happened to call [NearPayController.startDiscovery]. An accidental control is one
     * any future refactor can remove without noticing, so the claim is now backed by a switch.
     * Activation is flipping this to `true`; nothing else is missing.
     *
     * The PAYEE half is deliberately not behind it — advertising a signed request the payer's
     * device merely displays is the QR-equivalent surface that already shipped.
     */
    const val PAYER_DISCOVERY_ENABLED = false
}

/**
 * Source of cryptographically-secure random bytes. The protocol owns NO RNG: production
 * injects a platform secure RNG (Phase 2 wiring), tests inject a deterministic source.
 * This keeps the money-path key material out of the pure protocol layer (ADR-0030).
 */
fun interface RandomBytes {
    fun next(size: Int): ByteArray
}
