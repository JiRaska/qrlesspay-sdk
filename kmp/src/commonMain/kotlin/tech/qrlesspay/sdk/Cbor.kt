// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package tech.qrlesspay.sdk

/**
 * The slice of CBOR (RFC 8949) the QRlessPay bundle needs — encoder and decoder, written out
 * rather than pulled in.
 *
 * The wire spec (§3) pins a **definite-length map with unsigned-integer keys**, byte strings for
 * the binary fields and a text string for the SPAYD. That is a deliberately tiny surface, and
 * owning it buys two things a general-purpose CBOR library did not: the decoder can *reject* a
 * payload for being encoded unexpectedly instead of quietly accepting it, and the encoder cannot
 * drift from the spec because it has no other modes to drift into.
 *
 * The library it replaces did drift. `kotlinx-serialization-cbor` with default settings emitted an
 * indefinite-length map with **text keys taken from the Kotlin property names** and every
 * `ByteArray` as an **indefinite-length array of integers** — a 4-byte `sid` went on the wire as
 * `9f 1820 1821 1822 1823 ff`, fourteen bytes carrying four. The result was 326 bytes against the
 * 197 this encoding produces, and, far worse, unreadable by any implementation written from the
 * spec. Nothing caught it because the round-trip test drove this encoder into this decoder; only a
 * second implementation could disagree, and once one existed it did (#450).
 */
internal object Cbor {

    const val MT_UINT = 0
    const val MT_BSTR = 2
    const val MT_TSTR = 3
    const val MT_MAP = 5

    // RFC 8949 §3: the low five bits of the initial byte are the "additional information". Values
    // 0..23 carry the argument directly; 24/25/26/27 mean it follows in 1/2/4/8 bytes; 31 is
    // indefinite length, which this profile does not allow.
    private const val AI_DIRECT_MAX = 23L
    private const val AI_ONE_BYTE = 24
    private const val AI_TWO_BYTES = 25
    private const val AI_FOUR_BYTES = 26
    private const val AI_EIGHT_BYTES = 27
    private const val MAJOR_TYPE_SHIFT = 5
    private const val AI_MASK = 0x1F
    private const val BYTE_MASK = 0xFFL
    private const val BITS_PER_BYTE = 8
    private const val MAX_ONE_BYTE = 0xFFL
    private const val MAX_TWO_BYTES = 0xFFFFL
    private const val MAX_FOUR_BYTES = 0xFFFF_FFFFL
    private const val WIDTH_FOUR = 4
    private const val WIDTH_EIGHT = 8

    // ── Encoding ────────────────────────────────────────────────────────────────

    fun head(value: Long, majorType: Int): ByteArray {
        val mt = majorType shl MAJOR_TYPE_SHIFT
        return when {
            value <= AI_DIRECT_MAX -> byteArrayOf((mt or value.toInt()).toByte())
            value <= MAX_ONE_BYTE -> byteArrayOf((mt or AI_ONE_BYTE).toByte(), value.toByte())
            value <= MAX_TWO_BYTES -> bigEndian(mt or AI_TWO_BYTES, value, widthBytes = 2)
            value <= MAX_FOUR_BYTES -> bigEndian(mt or AI_FOUR_BYTES, value, widthBytes = 4)
            else -> bigEndian(mt or AI_EIGHT_BYTES, value, widthBytes = 8)
        }
    }

    private fun bigEndian(initial: Int, value: Long, widthBytes: Int) = ByteArray(widthBytes + 1).also {
        it[0] = initial.toByte()
        for (i in 0 until widthBytes) {
            it[i + 1] = ((value ushr ((widthBytes - 1 - i) * BITS_PER_BYTE)) and BYTE_MASK).toByte()
        }
    }

    fun uint(value: Long): ByteArray = head(value, MT_UINT)
    fun bytes(value: ByteArray): ByteArray = head(value.size.toLong(), MT_BSTR) + value
    fun text(value: String): ByteArray = value.encodeToByteArray().let { head(it.size.toLong(), MT_TSTR) + it }
    fun mapHeader(pairs: Int): ByteArray = head(pairs.toLong(), MT_MAP)

    // ── Decoding ────────────────────────────────────────────────────────────────

    /** (majorType, argument) or null when the input is not canonical, not well-formed, or short. */
    class Reader(private val bytes: ByteArray) {
        var index: Int = 0
            private set

        val isAtEnd: Boolean get() = index >= bytes.size

        /**
         * The `takeIf` on each width is the canonicity check: an argument that would have fitted a
         * shorter form is a second spelling of the same value, and a payload carried under a
         * signature must have exactly one. Additional info 31 (indefinite length) falls through to
         * null for the same reason.
         */
        fun readHead(): Pair<Int, Long>? {
            if (index >= bytes.size) return null
            val initial = bytes[index++].toInt() and BYTE_MASK.toInt()
            val major = initial shr MAJOR_TYPE_SHIFT
            return when (val info = initial and AI_MASK) {
                in 0..AI_DIRECT_MAX.toInt() -> major to info.toLong()
                AI_ONE_BYTE -> readUInt(1)?.takeIf { it > AI_DIRECT_MAX }?.let { major to it }
                AI_TWO_BYTES -> readUInt(2)?.takeIf { it > MAX_ONE_BYTE }?.let { major to it }
                AI_FOUR_BYTES -> readUInt(WIDTH_FOUR)?.takeIf { it > MAX_TWO_BYTES }?.let { major to it }
                AI_EIGHT_BYTES -> readUInt(WIDTH_EIGHT)?.takeIf { it > MAX_FOUR_BYTES }?.let { major to it }
                else -> null
            }
        }

        fun readBytes(count: Long): ByteArray? {
            if (count < 0 || count > Int.MAX_VALUE) return null
            val n = count.toInt()
            if (index + n > bytes.size) return null
            return bytes.copyOfRange(index, index + n).also { index += n }
        }

        private fun readUInt(width: Int): Long? {
            if (index + width > bytes.size) return null
            var v = 0L
            repeat(width) { v = (v shl BITS_PER_BYTE) or (bytes[index++].toLong() and BYTE_MASK) }
            return v
        }
    }
}
