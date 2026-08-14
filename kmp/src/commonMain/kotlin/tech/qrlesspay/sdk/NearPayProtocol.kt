// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package tech.qrlesspay.sdk

import io.github.andreypfau.curve25519.ed25519.Ed25519
import io.github.andreypfau.curve25519.ed25519.Ed25519PublicKey
import org.kotlincrypto.hash.sha2.SHA256
import tech.qrlesspay.sdk.parseSpayd

/** Output of [NearPayProtocol.mint]: the advert to broadcast + the signed bundle to serve over GATT. */
data class MintResult(val advert: BeaconPayload, val bundle: NearPayBundle)

/** Outcome of payer-side verification. [Rejected.reason] is a stable machine code, not UI copy. */
sealed class VerifyResult {
    data class Ok(val spayd: String) : VerifyResult()
    data class Rejected(val reason: String) : VerifyResult()
}

/**
 * The QRlessPay protocol core (ADR-0095). No BLE, no I/O, no platform dependency — just the
 * cryptographic + structural transform, so it is identical and unit-testable on iOS and Android.
 *
 * Replay defence within the [NearPay.MAX_TTL_SECONDS] window is part of [verify], via the
 * [ReplayGuard] the caller must supply. That guard holds the only state involved and keeps it
 * in memory on the verifying device, so the core stays free of I/O and platform types; what it
 * gives up is purity in the narrow sense that two identical calls no longer return the same
 * answer, which is precisely the property a single-use check exists to provide.
 */
object NearPayProtocol {

    /**
     * Receiver side. Derives a per-session Ed25519 keypair from a secure [random] seed,
     * signs the bundle, and builds the matching advert (carrying SHA-256(pk)[:2] as the
     * binding hash). The key is ephemeral — it proves device/session continuity, not identity.
     */
    fun mint(
        random: RandomBytes,
        firstName: String,
        spayd: String,
        nowEpochSec: Long,
        ttlSec: Long = NearPay.MAX_TTL_SECONDS,
        amountMinor: Int? = null,
    ): MintResult {
        val seed = random.next(NearPay.SEED_BYTES)
        val sid = random.next(NearPay.SID_BYTES)
        val nonce = random.next(NearPay.NONCE_BYTES)

        val priv = Ed25519.keyFromSeed(seed)
        val pk = priv.publicKey().toByteArray()
        val exp = nowEpochSec + ttlSec.coerceIn(1, NearPay.MAX_TTL_SECONDS)
        val sig = priv.sign(signingBytes(NearPay.VERSION, sid, nonce, exp, pk, spayd))

        val bundle = NearPayBundle(NearPay.VERSION, sid, spayd, nonce, exp, pk, sig)
        val keyHash = SHA256().digest(pk).copyOf(NearPay.KEYHASH_BYTES)
        val advert = BeaconPayload(NearPay.VERSION, foldAscii(firstName), sid, keyHash, amountMinor)
        return MintResult(advert, bundle)
    }

    /**
     * Payer side. Verifies, in order: version, advert↔bundle sid match, key/sig sizes, the
     * advert↔bundle key-hash binding, the expiry window, the Ed25519 signature, single use, and
     * that the SPAYD parses. Any failure returns [VerifyResult.Rejected] with a specific reason.
     *
     * [replayGuard] is required rather than defaulted on purpose. The single-use check used to be
     * documented here as "the caller's responsibility … in a later phase", and no caller ever took
     * it; a parameter with no default is the version of that sentence a compiler enforces. Its
     * state is the only state this object touches, and it stays in-memory and device-local — see
     * [ReplayGuard] for why that is a constraint rather than an implementation detail.
     *
     * Note the position of the check: **after** the signature verifies, never before. Consuming a
     * (sid, nonce) for a bundle that has not been proven authentic would let anyone in radio range
     * burn a legitimate payee's session by sending garbage carrying its identifiers — a denial of
     * service handed out by the anti-replay control itself.
     */
    fun verify(
        advert: BeaconPayload,
        bundle: NearPayBundle,
        nowEpochSec: Long,
        replayGuard: ReplayGuard,
    ): VerifyResult {
        if (bundle.version != NearPay.VERSION || advert.version != NearPay.VERSION) {
            return VerifyResult.Rejected("version")
        }
        if (bundle.sid.size != NearPay.SID_BYTES || bundle.nonce.size != NearPay.NONCE_BYTES) {
            return VerifyResult.Rejected("field-size")
        }
        if (bundle.pk.size != NearPay.PUBKEY_BYTES || bundle.sig.size != NearPay.SIG_BYTES) {
            return VerifyResult.Rejected("key-or-sig-size")
        }
        if (!advert.sid.contentEquals(bundle.sid)) return VerifyResult.Rejected("sid-mismatch")
        val kh = SHA256().digest(bundle.pk).copyOf(NearPay.KEYHASH_BYTES)
        if (!kh.contentEquals(advert.keyHash)) return VerifyResult.Rejected("advert-bundle-binding")
        if (bundle.exp <= nowEpochSec) return VerifyResult.Rejected("expired")
        if (bundle.exp > nowEpochSec + NearPay.MAX_TTL_SECONDS) return VerifyResult.Rejected("exp-too-far")

        val signed = signingBytes(NearPay.VERSION, bundle.sid, bundle.nonce, bundle.exp, bundle.pk, bundle.spayd)
        if (!Ed25519PublicKey(bundle.pk).verify(signed, bundle.sig)) return VerifyResult.Rejected("bad-signature")

        if (parseSpayd(bundle.spayd) == null) return VerifyResult.Rejected("bad-spayd")

        // Last, so a bundle rejected for any other reason leaves the session re-payable.
        if (!replayGuard.firstUse(bundle.sid, bundle.nonce, bundle.exp)) {
            return VerifyResult.Rejected("replayed")
        }
        return VerifyResult.Ok(bundle.spayd)
    }
}
