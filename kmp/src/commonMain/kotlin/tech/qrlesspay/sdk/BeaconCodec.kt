// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package tech.qrlesspay.sdk

/**
 * Phase-1 discovery beacon (the BLE advertisement payload, ADR-0095 spec §2):
 * a readable first name + ephemeral session-id + a 2-byte hash of the session
 * public key (which binds the advert to the signed bundle read over GATT) + an
 * optional requested amount. NO IBAN ever rides the advert.
 */
data class BeaconPayload(
    val version: Int,
    val name: String,
    val sid: ByteArray,
    val keyHash: ByteArray,
    val amountMinor: Int?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BeaconPayload) return false
        return version == other.version &&
            name == other.name &&
            sid.contentEquals(other.sid) &&
            keyHash.contentEquals(other.keyHash) &&
            amountMinor == other.amountMinor
    }

    override fun hashCode(): Int {
        var r = version
        r = 31 * r + name.hashCode()
        r = 31 * r + sid.contentHashCode()
        r = 31 * r + keyHash.contentHashCode()
        r = 31 * r + (amountMinor ?: 0)
        return r
    }
}

/**
 * Byte codec for [BeaconPayload]. Layout:
 *   [verFlags 1B] [nameLen 1B] [name nameLen B] [sid 4B] [keyHash 2B] [amount 3B?]
 * `verFlags` high nibble = protocol version, low nibble = flags ([NearPay.FLAG_AMOUNT] …).
 * The name is ASCII-folded and truncated to [NearPay.NAME_MAX_BYTES].
 */
object BeaconCodec {

    fun encode(p: BeaconPayload): ByteArray {
        require(p.sid.size == NearPay.SID_BYTES) { "sid must be ${NearPay.SID_BYTES} bytes" }
        require(p.keyHash.size == NearPay.KEYHASH_BYTES) { "keyHash must be ${NearPay.KEYHASH_BYTES} bytes" }

        val name = truncateUtf8(foldAscii(p.name), NearPay.NAME_MAX_BYTES)

        val flags = if (p.amountMinor != null) NearPay.FLAG_AMOUNT else 0
        // The payload's own version, not the constant. Hard-coding [NearPay.VERSION] here made
        // encode(decode(x)) silently rewrite a version this implementation does not understand
        // into one it does — which is how a re-broadcast or a logged advert loses the very field
        // that says how to read it. Swift already used the payload's value; this makes them agree.
        val verFlags = ((p.version and 0xF) shl 4) or (flags and 0xF)

        val out = ArrayList<Byte>(2 + name.size + NearPay.SID_BYTES + NearPay.KEYHASH_BYTES + 3)
        out.add(verFlags.toByte())
        out.add(name.size.toByte())
        name.forEach(out::add)
        p.sid.forEach(out::add)
        p.keyHash.forEach(out::add)
        if (p.amountMinor != null) {
            val a = p.amountMinor.coerceIn(0, NearPay.AMOUNT_MAX_MINOR)
            out.add(((a ushr 16) and 0xFF).toByte())
            out.add(((a ushr 8) and 0xFF).toByte())
            out.add((a and 0xFF).toByte())
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): BeaconPayload? {
        if (bytes.size < 2) return null
        var i = 0
        val verFlags = bytes[i++].toInt() and 0xFF
        val version = (verFlags ushr 4) and 0xF
        val flags = verFlags and 0xF
        val nameLen = bytes[i++].toInt() and 0xFF
        if (nameLen > NearPay.NAME_MAX_BYTES || i + nameLen > bytes.size) return null
        // Sanitise on the way IN. The advert is unauthenticated and the name is the one field the
        // payer reads on the tile, so a hostile payee hand-building the bytes could otherwise put
        // combining marks, an RTL override or zero-width joiners in front of them. `decodeToString`
        // also substitutes U+FFFD for invalid UTF-8 where Swift rejects the advert outright; the
        // fold reconciles that, since U+FFFD is not printable ASCII and is dropped here.
        val name = foldAscii(bytes.copyOfRange(i, i + nameLen).decodeToString())
        i += nameLen
        if (i + NearPay.SID_BYTES + NearPay.KEYHASH_BYTES > bytes.size) return null
        val sid = bytes.copyOfRange(i, i + NearPay.SID_BYTES)
        i += NearPay.SID_BYTES
        val keyHash = bytes.copyOfRange(i, i + NearPay.KEYHASH_BYTES)
        i += NearPay.KEYHASH_BYTES
        var amount: Int? = null
        if ((flags and NearPay.FLAG_AMOUNT) != 0) {
            if (i + 3 > bytes.size) return null
            amount = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
        }
        return BeaconPayload(version, name, sid, keyHash, amount)
    }
}

/** Czech diacritics → ASCII (the advert is an ASCII discovery label; mirrors SPAYD folding). */
private val CZ_FOLD: Map<Char, Char> = mapOf(
    'á' to 'a', 'č' to 'c', 'ď' to 'd', 'é' to 'e', 'ě' to 'e', 'í' to 'i', 'ň' to 'n', 'ó' to 'o',
    'ř' to 'r', 'š' to 's', 'ť' to 't', 'ú' to 'u', 'ů' to 'u', 'ý' to 'y', 'ž' to 'z',
    'Á' to 'A', 'Č' to 'C', 'Ď' to 'D', 'É' to 'E', 'Ě' to 'E', 'Í' to 'I', 'Ň' to 'N', 'Ó' to 'O',
    'Ř' to 'R', 'Š' to 'S', 'Ť' to 'T', 'Ú' to 'U', 'Ů' to 'U', 'Ý' to 'Y', 'Ž' to 'Z',
)

/**
 * Fold to **printable ASCII**. Mapping the Czech diacritics is not enough on its own: everything
 * the map does not name (Greek, emoji, combining marks, control characters) used to pass straight
 * through here while the Swift implementation filtered it out, so the two produced different bytes
 * for the same name — an interop divergence in the one field a human reads. Dropping is the right
 * answer rather than rejecting: the advert is a discovery label, and a name that arrives plain
 * beats a tile that does not arrive.
 */
internal fun foldAscii(s: String): String =
    buildString { for (c in s) (CZ_FOLD[c] ?: c).let { if (it.code in 0x20..0x7E) append(it) } }
        // Trim LAST, not first. Dropping a non-ASCII character can expose a space that was interior
        // in the input ("J␣✳" becomes "J␣"), so trimming up front leaves one behind and the fold is
        // no longer idempotent. Swift did not trim at all, which was the same disagreement seen from
        // the other side.
        .trim()

/** Encode [s] to UTF-8 truncated to at most [maxBytes], never splitting a character mid-codepoint. */
internal fun truncateUtf8(s: String, maxBytes: Int): ByteArray {
    val full = s.encodeToByteArray()
    if (full.size <= maxBytes) return full
    val sb = StringBuilder()
    var used = 0
    for (c in s) {
        val cb = c.toString().encodeToByteArray().size
        if (used + cb > maxBytes) break
        sb.append(c)
        used += cb
    }
    return sb.toString().encodeToByteArray()
}
