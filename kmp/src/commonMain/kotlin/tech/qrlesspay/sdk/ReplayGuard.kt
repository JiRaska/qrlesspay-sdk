// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package tech.qrlesspay.sdk

/**
 * Single-use check for a QRlessPay bundle (wire spec §3 step 3: "`nonce`/`sid` not seen before").
 *
 * **Device-local by necessity, not by convenience.** QRlessPay is phone-to-phone with no server
 * (spec §11), so "not seen before" can only ever mean *not seen by this verifying device*. A
 * shared nonce store would be the one thing that costs the profile its SPAYD portability, so
 * there is deliberately no interface here that a network-backed implementation could satisfy
 * more usefully than an in-memory one.
 *
 * That bounds what this can promise, and the bound is worth stating: it catches the same payer
 * device being handed the same bundle twice — double payment, a stale tile re-tapped. A capture
 * replayed to a *different* payer device is not catchable without a backend, and is left to the
 * mandatory payer confirmation plus SCA, which no replayed bundle can satisfy on its own.
 */
interface ReplayGuard {
    /**
     * Records this (sid, nonce) as used and reports whether it was fresh.
     *
     * @return `true` if this pair had not been seen (and is now recorded), `false` if it is a replay.
     */
    fun firstUse(sid: ByteArray, nonce: ByteArray, expEpochSec: Long): Boolean
}

/**
 * In-memory [ReplayGuard] that forgets an entry once the bundle it belongs to has expired.
 *
 * Bundles live at most [NearPay.MAX_TTL_SECONDS], so an entry is worthless past its own `exp` and
 * the set stays small on its own. [maxEntries] is a second, independent bound: eviction here is
 * driven by a clock this class does not own, and a clock that jumps backwards (or a caller that
 * passes a far-future `exp`) would otherwise let the set grow without limit on a long-lived
 * screen. When the cap is hit the entries closest to expiry go first — they are the ones a replay
 * could soonest no longer be attempted against anyway.
 */
class TtlReplayGuard(private val now: () -> Long, private val maxEntries: Int = DEFAULT_MAX_ENTRIES) : ReplayGuard {

    /** key = sid ‖ nonce, rendered as hex because ByteArray has no structural equality. */
    private val seen = mutableMapOf<String, Long>()

    override fun firstUse(sid: ByteArray, nonce: ByteArray, expEpochSec: Long): Boolean {
        val nowSec = now()
        seen.entries.removeAll { it.value <= nowSec }
        val key = hex(sid) + ":" + hex(nonce)
        if (seen.containsKey(key)) return false
        if (seen.size >= maxEntries) {
            seen.entries.minByOrNull { it.value }?.let { seen.remove(it.key) }
        }
        seen[key] = expEpochSec
        return true
    }

    /** Entries currently held — for tests asserting eviction, not for callers to branch on. */
    internal fun size(): Int = seen.size

    private fun hex(b: ByteArray): String = b.joinToString("") {
        val v = it.toInt() and BYTE_MASK
        HEX[v shr NIBBLE_BITS].toString() + HEX[v and NIBBLE_MASK]
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 512
        private const val HEX = "0123456789abcdef"
        private const val BYTE_MASK = 0xFF
        private const val NIBBLE_MASK = 0x0F
        private const val NIBBLE_BITS = 4
    }
}
