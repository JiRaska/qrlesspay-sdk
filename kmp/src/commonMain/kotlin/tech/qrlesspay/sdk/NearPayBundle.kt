// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package tech.qrlesspay.sdk

/**
 * The signed payload transferred over GATT in phase 2 (ADR-0095 spec §3), CBOR-encoded.
 * The Ed25519 [sig] covers [signingBytes] (version|sid|nonce|exp|pk|spayd). The real
 * IBAN lives only inside [spayd] here — never on the advert.
 *
 * The encoding is the spec's: a definite-length map with unsigned-integer keys, byte strings for
 * the binary fields, a text string for the SPAYD. See [Cbor] for why it is hand-rolled rather than
 * delegated to a serialization library, and #450 for what the library's defaults actually put on
 * the wire.
 */
data class NearPayBundle(
    val version: Int,
    val sid: ByteArray,
    val spayd: String,
    val nonce: ByteArray,
    val exp: Long,
    val pk: ByteArray,
    val sig: ByteArray,
) {
    fun toCbor(): ByteArray = Cbor.mapHeader(FIELD_COUNT) +
        Cbor.uint(KEY_VERSION) + Cbor.uint(version.toLong()) +
        Cbor.uint(KEY_SID) + Cbor.bytes(sid) +
        Cbor.uint(KEY_SPAYD) + Cbor.text(spayd) +
        Cbor.uint(KEY_NONCE) + Cbor.bytes(nonce) +
        Cbor.uint(KEY_EXP) + Cbor.uint(exp) +
        Cbor.uint(KEY_PK) + Cbor.bytes(pk) +
        Cbor.uint(KEY_SIG) + Cbor.bytes(sig)

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
        // Spec §3 map keys. Integers, not property names — a text key costs 7–8 bytes each on a
        // payload that has to survive a single GATT read.
        private const val KEY_VERSION = 1L
        private const val KEY_SID = 2L
        private const val KEY_SPAYD = 3L
        private const val KEY_NONCE = 4L
        private const val KEY_EXP = 5L
        private const val KEY_PK = 6L
        private const val KEY_SIG = 7L
        private const val FIELD_COUNT = 7

        /**
         * Returns null for anything that is not exactly one well-formed bundle. Every rejection is
         * structural and happens before the cryptography ever runs: a wrong major type, an unknown
         * or repeated key, a missing field, or bytes left over after the map. Tolerating a second
         * encoding would be how two dialects become permanent (#450).
         *
         * Split in two on purpose. [readFields] knows CBOR and nothing about this profile;
         * assembling below knows the profile and nothing about CBOR. Keeping the two apart is what
         * stops the type checks from being written per-key seven times over.
         */
        fun fromCbor(bytes: ByteArray): NearPayBundle? {
            val f = readFields(bytes) ?: return null
            return NearPayBundle(
                version = (f[KEY_VERSION] as? Long)?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null,
                sid = f[KEY_SID] as? ByteArray ?: return null,
                spayd = f[KEY_SPAYD] as? String ?: return null,
                nonce = f[KEY_NONCE] as? ByteArray ?: return null,
                exp = f[KEY_EXP] as? Long ?: return null,
                pk = f[KEY_PK] as? ByteArray ?: return null,
                sig = f[KEY_SIG] as? ByteArray ?: return null,
            )
        }

        /**
         * Reads the definite-length map into key → value, where a value is a `Long`, a `String` or
         * a `ByteArray` according to its CBOR major type. Unknown keys, repeated keys, any other
         * major type and trailing bytes are all rejected here rather than filtered later.
         */
        private fun readFields(bytes: ByteArray): Map<Long, Any>? {
            val r = Cbor.Reader(bytes)
            val (mapMajor, pairs) = r.readHead() ?: return null
            if (mapMajor != Cbor.MT_MAP || pairs != FIELD_COUNT.toLong()) return null

            val out = mutableMapOf<Long, Any>()
            repeat(FIELD_COUNT) {
                val (keyMajor, key) = r.readHead() ?: return null
                if (keyMajor != Cbor.MT_UINT || key !in KNOWN_KEYS || key in out) return null
                out[key] = readValue(r) ?: return null
            }
            // Trailing bytes are a framing error, not slack to ignore.
            return if (r.isAtEnd) out else null
        }

        /** One value, typed by its CBOR major type. Any other major type is not this profile. */
        private fun readValue(r: Cbor.Reader): Any? {
            val (major, arg) = r.readHead() ?: return null
            return when (major) {
                Cbor.MT_UINT -> arg
                // `decodeToString()` defaults to REPLACING an invalid UTF-8 sequence with U+FFFD,
                // so a malformed SPAYD would decode "successfully" into a silently altered payment
                // string — while Swift, which rejects it, would refuse the same bundle. Strict.
                Cbor.MT_TSTR -> r.readBytes(arg)?.let {
                    runCatching { it.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
                } ?: return null
                Cbor.MT_BSTR -> r.readBytes(arg)
                else -> null
            }
        }

        private val KNOWN_KEYS =
            setOf(KEY_VERSION, KEY_SID, KEY_SPAYD, KEY_NONCE, KEY_EXP, KEY_PK, KEY_SIG)
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
