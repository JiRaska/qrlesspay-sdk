// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
package tech.qrlesspay.sdk

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import tech.openbank.app.util.runCatchingCancellable

/**
 * The signed payload transferred over GATT in phase 2 (ADR-0095 spec §3), CBOR-encoded.
 * The Ed25519 [sig] covers [signingBytes] (version|sid|nonce|exp|pk|spayd). The real
 * IBAN lives only inside [spayd] here — never on the advert.
 */
@Serializable
data class NearPayBundle(
    val version: Int,
    val sid: ByteArray,
    val spayd: String,
    val nonce: ByteArray,
    val exp: Long,
    val pk: ByteArray,
    val sig: ByteArray,
) {
    @OptIn(ExperimentalSerializationApi::class)
    fun toCbor(): ByteArray = Cbor.encodeToByteArray(this)

    // data class equals/hashCode would compare the ByteArray fields by reference; override for
    // structural equality so two identical (e.g. decoded) bundles compare equal.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NearPayBundle) return false
        return version == other.version &&
            exp == other.exp &&
            spayd == other.spayd &&
            sid.contentEquals(other.sid) &&
            nonce.contentEquals(other.nonce) &&
            pk.contentEquals(other.pk) &&
            sig.contentEquals(other.sig)
    }

    override fun hashCode(): Int {
        var r = version
        r = 31 * r + exp.hashCode()
        r = 31 * r + spayd.hashCode()
        r = 31 * r + sid.contentHashCode()
        r = 31 * r + nonce.contentHashCode()
        r = 31 * r + pk.contentHashCode()
        r = 31 * r + sig.contentHashCode()
        return r
    }

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        fun fromCbor(bytes: ByteArray): NearPayBundle? =
            runCatchingCancellable { Cbor.decodeFromByteArray<NearPayBundle>(bytes) }.getOrNull()
    }
}

/**
 * Deterministic byte string the Ed25519 signature covers. Both mint (receiver) and verify
 * (payer) reconstruct it identically: `version(1) | sid(4) | nonce(16) | exp(8, big-endian)
 * | pk(32) | spayd(UTF-8)`. A canonical concatenation is used rather than canonical-CBOR so
 * there is exactly one byte representation to sign — no canonicalisation ambiguity.
 */
internal fun signingBytes(
    version: Int,
    sid: ByteArray,
    nonce: ByteArray,
    exp: Long,
    pk: ByteArray,
    spayd: String,
): ByteArray {
    val expBE = ByteArray(8) { ((exp ushr (56 - it * 8)) and 0xFF).toByte() }
    return byteArrayOf(version.toByte()) + sid + nonce + expBE + pk + spayd.encodeToByteArray()
}
