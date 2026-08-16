// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package tech.qrlesspay.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Property-based fuzzing of the decoders — threat-model §8 gate 2, mirroring the Swift `FuzzTests`.
 *
 * Running the same oracles in both languages is not redundancy. A decoder bug that exists in only
 * one of them is exactly the class this repository exists to catch, and the beacon defect these
 * tests first found (a decoded name carrying characters `encode` would never emit) was present in
 * both implementations for *different reasons* — Swift filtered non-ASCII and Kotlin did not, so
 * they also disagreed on the bytes.
 *
 * Seeded, never random: every case is a pure function of a `Long` seed, reported on failure.
 */
class FuzzTest {

    /** xorshift64*, written out so a seed means the same corpus on every platform. */
    private class Rng(seed: Long) {
        private var state: Long = if (seed == 0L) 0x9E3779B97F4A7C15uL.toLong() else seed
        fun next(): Long {
            state = state xor (state ushr 12)
            state = state xor (state shl 25)
            state = state xor (state ushr 27)
            return state * 2685821657736338717L
        }
        fun int(bound: Int): Int = if (bound <= 0) 0 else ((next() ushr 1) % bound).toInt()
        fun byte(): Byte = (next() and 0xFF).toByte()
    }

    private fun hex(b: ByteArray) = b.joinToString("") {
        val v = it.toInt() and 0xFF
        "0123456789abcdef"[v ushr 4].toString() + "0123456789abcdef"[v and 0xF]
    }

    private fun mutate(input: ByteArray, rng: Rng): ByteArray {
        val out = input.toMutableList()
        when (rng.int(6)) {
            0 -> if (out.isNotEmpty()) {
                val i = rng.int(out.size)
                out[i] = (out[i].toInt() xor (1 shl rng.int(8))).toByte()
            }
            1 -> if (out.isNotEmpty()) out[rng.int(out.size)] = rng.byte()
            2 -> if (out.isNotEmpty()) return out.take(rng.int(out.size)).toByteArray()
            3 -> repeat(1 + rng.int(8)) { out.add(rng.byte()) }
            4 -> if (out.isNotEmpty()) {
                val i = rng.int(out.size)
                repeat(minOf(1 + rng.int(8), out.size - i)) { out.removeAt(i) }
            }
            else -> {
                val i = rng.int(out.size + 1)
                out.addAll(i, (0 until 1 + rng.int(8)).map { rng.byte() })
            }
        }
        return out.toByteArray()
    }

    private fun seedBundle(): ByteArray = NearPayBundle(
        version = 1,
        sid = byteArrayOf(0x20, 0x21, 0x22, 0x23),
        spayd = "SPD*1.0*ACC:CZ6508000000192000145399*AM:250.00*CC:CZK*RN:Jiri",
        nonce = ByteArray(16) { (0x24 + it).toByte() },
        exp = 1_090,
        pk = ByteArray(32) { 0xAB.toByte() },
        sig = ByteArray(64) { 0xCD.toByte() },
    ).toCbor()

    @Test
    fun bundleDecoderSurvivesMutationAndStaysCanonical() {
        var accepted = 0
        for (seed in 1L..20_000L) {
            val rng = Rng(seed)
            var input = seedBundle()
            repeat(1 + rng.int(4)) { input = mutate(input, rng) }

            val decoded = NearPayBundle.fromCbor(input) ?: continue
            accepted++
            assertEquals(
                hex(input), hex(decoded.toCbor()),
                "seed $seed: decoded but did not re-encode to its input — a non-canonical encoding was accepted",
            )
        }
        // Without this the round-trip assertion could never have run and the test would be green
        // while proving nothing.
        assertTrue(accepted > 0, "no mutated input was ever accepted — the oracle never fired")
    }

    @Test
    fun beaconDecoderSurvivesMutationAndIsIdempotent() {
        val seedBeacon = BeaconCodec.encode(
            BeaconPayload(1, "Jiri", byteArrayOf(0x20, 0x21, 0x22, 0x23), byteArrayOf(0x56, 0x47), 25_000),
        )
        var accepted = 0
        for (seed in 1L..20_000L) {
            val rng = Rng(seed + 2_000_000L)
            var input = seedBeacon
            repeat(1 + rng.int(3)) { input = mutate(input, rng) }

            val decoded = BeaconCodec.decode(input) ?: continue
            accepted++
            // The advert is not length-prefixed as a whole, so trailing bytes are legal and a
            // re-encode need not equal the input. What must hold is idempotence.
            assertEquals(decoded, BeaconCodec.decode(BeaconCodec.encode(decoded)), "seed $seed")
        }
        assertTrue(accepted > 0, "no mutated advert was ever accepted — the oracle never fired")
    }

    /**
     * The regression the fuzzer actually found, pinned as a named case so it cannot silently
     * regress once the fuzz corpus shifts.
     *
     * A hostile payee does not have to call [BeaconCodec.encode] — it hand-builds the advert bytes.
     * Nothing then stops it putting a combining mark, an RTL override or a zero-width joiner in the
     * name, which is the single field the payer reads off an unauthenticated tile.
     */
    @Test
    fun decodedNameCarriesOnlyPrintableAscii() {
        val hostile = "Jiri\u036A\u202E"
        val bytes = hostile.encodeToByteArray()
        val advert = byteArrayOf((1 shl 4).toByte(), bytes.size.toByte()) +
            bytes + byteArrayOf(0x20, 0x21, 0x22, 0x23) + byteArrayOf(0x56, 0x47)

        val decoded = BeaconCodec.decode(advert)
        assertEquals("Jiri", decoded?.name)
    }
}
