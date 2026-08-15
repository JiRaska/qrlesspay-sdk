// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package tech.qrlesspay.sdk

import io.github.andreypfau.curve25519.x25519.X25519
import org.kotlincrypto.macs.hmac.sha2.HmacSHA256

/**
 * Short Authentication String — the numeric code two people compare out loud (wire spec §4).
 *
 * It defeats an **active MITM on the GATT link** without any PKI: an attacker who terminates the
 * link and runs a separate key agreement with each side ends up with two different shared secrets,
 * so the two humans read out different numbers and stop. That is the entire mechanism; there is
 * nothing to verify against a directory, which is what makes it usable in a protocol with no
 * backend (§11).
 *
 * **§4 is prose and this fills the gaps it leaves.** It names X25519, HKDF and the label `QP-SAS`,
 * and stops before the parts an implementer has to get identical: what goes into the transcript,
 * how many digits, and how digits are drawn from key material. Marked a **v1.1 proposal**, like the
 * UWB bits, until the spec adopts or replaces it.
 *
 * ### What the code attests to, and why the transcript is the security-relevant part
 *
 * A SAS over the raw shared secret alone would prove only that *some* key agreement was not
 * intercepted. The payer needs more than that: the code must attest to **the payment they are about
 * to make**, or an attacker can run an honest SAS exchange on one session and splice it onto a
 * different bundle.
 *
 * So the transcript binds the session id and the payee's Ed25519 key from the bundle, alongside both
 * ephemeral public keys. Confirming the digits then means "this GATT link is not intercepted **and**
 * it carries the bundle I am about to pay", which is the claim the payer is actually being asked to
 * make.
 *
 * The two ephemeral keys are ordered by role, not sorted: the roles are unambiguous here (payee
 * advertises, payer connects), and role-ordering is one less thing for two implementations to
 * disagree about.
 */
object Sas {

    /** §10 proposes six digits over the §4 four: ~20 bits of comparison entropy rather than ~13. */
    const val DEFAULT_DIGITS = 6

    /** The `sas` characteristic of §1 — read/write, ephemeral-DH public keys. */
    const val CHAR_SAS_UUID = "0000C3A7-2F3B-4E8A-9A5E-0B0E6F1C2D3A"

    private const val LABEL = "QP-SAS-v1"
    private const val X25519_KEY_BYTES = 32
    private const val OKM_BYTES = 8
    private const val BYTE_MASK = 0xFFL

    /** RFC 7748 §4.1: the Curve25519 base point, u = 9, little-endian. */
    private val BASE_POINT = ByteArray(X25519_KEY_BYTES).also { it[0] = 9 }

    /**
     * `X25519.x25519(scalar, point)` **writes its result into the `point` argument**. Measured, not
     * assumed: deriving two public keys and then agreeing left both public-key arrays holding the
     * shared secret instead.
     *
     * Nothing in the signature says so — it returns a `ByteArray`, which reads as pure — and the
     * basepoint case happens not to mutate, so a probe that only derives keys sees nothing wrong.
     * Every call therefore hands it a copy, and a test asserts the caller's array survives. Passing
     * a peer's public key straight in would corrupt the caller's own data structure, which on this
     * path is the key a SAS code is about to be derived from.
     */
    private fun agree(scalar: ByteArray, point: ByteArray): ByteArray =
        X25519.x25519(scalar.copyOf(), point.copyOf())

    /** An ephemeral X25519 keypair, valid for exactly one request screen. */
    class KeyPair internal constructor(internal val privateKey: ByteArray, val publicKey: ByteArray)

    /**
     * Derives an ephemeral keypair from [random]. Ephemeral is not a style choice: reusing one
     * across sessions would let an attacker who recorded an earlier exchange replay its code.
     */
    fun generateKeyPair(random: RandomBytes): KeyPair {
        val scalar = random.next(X25519_KEY_BYTES)
        // scalarBaseMult is private in the library, so the public key is the scalar applied to the
        // RFC 7748 base point (u = 9) — the same operation, with the constant in the open.
        return KeyPair(scalar, agree(scalar, BASE_POINT))
    }

    /**
     * The bytes both sides hash. Every field is fixed width except none — all are — so no two
     * different inputs can produce the same transcript by shifting a boundary.
     */
    fun transcript(
        sid: ByteArray,
        payeeEd25519Pk: ByteArray,
        payeeEphemeralPk: ByteArray,
        payerEphemeralPk: ByteArray,
    ): ByteArray = byteArrayOf(NearPay.VERSION.toByte()) + sid + payeeEd25519Pk + payeeEphemeralPk + payerEphemeralPk

    /**
     * The code both sides display. Identical on both devices when the link is honest, different when
     * it is not.
     *
     * @param sharedSecret X25519 output — the same value on both sides, and never displayed.
     */
    fun code(
        sharedSecret: ByteArray,
        transcript: ByteArray,
        digits: Int = DEFAULT_DIGITS,
    ): String {
        require(digits in 4..9) { "digits must be 4..9" }
        val okm = hkdf(ikm = sharedSecret, salt = transcript, info = LABEL.encodeToByteArray(), length = OKM_BYTES)

        // Eight bytes folded into a positive 63-bit value, then reduced. The modulo bias is what
        // makes the width matter: with 2^63 over 10^6 the most likely code is more likely than the
        // least by about one part in 10^13, which is far below anything a human comparison can be
        // gamed on. Taking four bytes instead would still be safe here; taking two would not.
        var value = 0L
        for (b in okm) value = (value shl 8) or (b.toLong() and BYTE_MASK)
        value = value and Long.MAX_VALUE

        var modulus = 1L
        repeat(digits) { modulus *= 10 }
        return (value % modulus).toString().padStart(digits, '0')
    }

    /** Convenience: agreement plus derivation, for the side that has the peer's ephemeral key. */
    fun codeFor(
        localKeyPair: KeyPair,
        peerEphemeralPk: ByteArray,
        sid: ByteArray,
        payeeEd25519Pk: ByteArray,
        payeeEphemeralPk: ByteArray,
        payerEphemeralPk: ByteArray,
        digits: Int = DEFAULT_DIGITS,
    ): String = code(
        sharedSecret = agree(localKeyPair.privateKey, peerEphemeralPk),
        transcript = transcript(sid, payeeEd25519Pk, payeeEphemeralPk, payerEphemeralPk),
        digits = digits,
    )

    /** RFC 5869 HKDF-SHA256. Written out because the extract/expand split is three lines. */
    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = HmacSHA256(salt).doFinal(ikm)
        var previous = ByteArray(0)
        var out = ByteArray(0)
        var counter = 1
        while (out.size < length) {
            previous = HmacSHA256(prk).doFinal(previous + info + byteArrayOf(counter.toByte()))
            out += previous
            counter++
        }
        return out.copyOf(length)
    }
}
