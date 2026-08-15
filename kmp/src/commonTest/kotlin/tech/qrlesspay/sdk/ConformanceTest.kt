// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package tech.qrlesspay.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun hex(b: ByteArray) = b.joinToString("") {
    val v = it.toInt() and 0xFF
    "0123456789abcdef"[v shr 4].toString() + "0123456789abcdef"[v and 0x0F]
}

private fun unhex(s: String) = ByteArray(s.length / 2) {
    ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte()
}

private class SeqRandom(start: Int) : RandomBytes {
    private var c = start
    override fun next(size: Int) = ByteArray(size) { (c++ and 0xFF).toByte() }
}

private const val SPAYD = "SPD*1.0*ACC:CZ6508000000192000145399*AM:250.00*CC:CZK*RN:Jiri"

/**
 * The same vector `swift/Tests/QRlessPayTests/ConformanceTests.swift` runs, asserted here.
 *
 * Both implementations pinning the *same* bytes is what makes them one profile rather than two
 * dialects. The vectors are not committed as a resource in this module yet — reading a file from
 * `commonTest` needs a multiplatform resource story, and hardcoding the one vector both sides
 * already agree on is honest about the coverage that exists today.
 */
private const val REF0_BEACON = "11044a6972692021222356470061a8"
private const val REF0_SID = "20212223"
private const val REF0_NONCE = "2425262728292a2b2c2d2e2f30313233"
private const val REF0_PK = "03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8"
private const val REF0_SIG =
    "1a6f9ecb80fb00412b67af5ac4fda5826650f90fd22fec191e19d3609b8d4f45" +
        "62065a8b2ddf42fdb4871336d8e4a6f564e67227a21b42ca04bd378a98643900"
private const val REF0_CBOR =
    "a7010102442021222303783d5350442a312e302a4143433a435a363530383030303030303139323030303134" +
        "353339392a414d3a3235302e30302a43433a435a4b2a524e3a4a69726904502425262728292a2b2c2d2e" +
        "2f303132330519044206582003a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc86641255" +
        "31b80758401a6f9ecb80fb00412b67af5ac4fda5826650f90fd22fec191e19d3609b8d4f4562065a8b2d" +
        "df42fdb4871336d8e4a6f564e67227a21b42ca04bd378a98643900"

class ConformanceTest {

    private fun mintRef0() = NearPayProtocol.mint(SeqRandom(0), "Jiří", SPAYD, 1_000, 90, 25_000)

    @Test
    fun mintReproducesTheReferenceVector() {
        val m = mintRef0()
        assertEquals(REF0_SID, hex(m.bundle.sid))
        assertEquals(REF0_NONCE, hex(m.bundle.nonce))
        assertEquals(1_090L, m.bundle.exp)
        assertEquals(REF0_PK, hex(m.bundle.pk))
        // Unlike CryptoKit, this library's Ed25519 signing is deterministic, so the signature is
        // reproducible here. That asymmetry is documented in the Swift SDK: a conformance suite
        // may compare signature bytes against a fixed vector, never across fresh mints.
        assertEquals(REF0_SIG, hex(m.bundle.sig))
        assertEquals(REF0_BEACON, hex(BeaconCodec.encode(m.advert)))
    }

    @Test
    fun encodingMatchesTheSwiftImplementationByteForByte() {
        assertEquals(REF0_CBOR, hex(mintRef0().bundle.toCbor()))
    }

    @Test
    fun theSwiftEncodingDecodesHereToTheSameBundle() {
        assertEquals(mintRef0().bundle, assertNotNull(NearPayBundle.fromCbor(unhex(REF0_CBOR))))
    }

    @Test
    fun theBundleStaysWithinTheSizeAGattReadHasToCarry() {
        val size = mintRef0().bundle.toCbor().size
        assertTrue(size <= 210, "bundle grew to $size B")
    }

    @Test
    fun aFullVerificationAcceptsTheReferenceBundleAndRejectsItTwice() {
        val m = mintRef0()
        val guard = TtlReplayGuard(now = { 1_010 })
        assertTrue(NearPayProtocol.verify(m.advert, m.bundle, 1_010, guard) is VerifyResult.Ok)
        assertEquals(
            "replayed",
            (NearPayProtocol.verify(m.advert, m.bundle, 1_010, guard) as VerifyResult.Rejected).reason,
        )
    }

    @Test
    fun theOldLibraryEncodingIsRejected() {
        // Indefinite-length map with text keys — what kotlinx-serialization-cbor used to emit.
        assertNull(NearPayBundle.fromCbor(unhex("bf6776657273696f6e01637369649f1820182118221823ff")))
    }

    @Test
    fun theProximityGateAndAmbiguityHelperBehaveAsTheProfileRequires() {
        assertTrue(ambiguousDisplayNames(listOf(tile("Jiri", "a"), tile(" jiri ", "b"))).isNotEmpty())
        assertTrue(ambiguousDisplayNames(listOf(tile("Jiri"), tile("Eva"))).isEmpty())
        assertEquals(-70, NearPay.RSSI_GATE_DBM)
    }

    private fun tile(name: String, id: String = name) =
        NearbyTile(peerId = id, firstName = name, amountMinor = null, rssi = -50, beacon = ByteArray(0))
}
