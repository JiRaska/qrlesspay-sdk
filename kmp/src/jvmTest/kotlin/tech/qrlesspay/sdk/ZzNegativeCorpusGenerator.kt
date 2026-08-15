// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package tech.qrlesspay.sdk

import java.io.File
import kotlin.test.Test

/**
 * Writes `conformance/negative-vectors.json` from the real implementation.
 *
 * Generated rather than hand-written for the same reason the positive vectors are: a hand-typed
 * hex string is a transcription error waiting to be debugged as a protocol bug, and a corpus that
 * drifts to match the code it is meant to constrain is worse than none.
 *
 * Run with `-Dqrlesspay.writeVectors=true`; otherwise it is a no-op, so a normal test run does not
 * rewrite a committed artifact.
 */
class ZzNegativeCorpusGenerator {

    private val spayd = "SPD*1.0*ACC:CZ6508000000192000145399*AM:250.00*CC:CZK*RN:Jiri"

    private class Seq(start: Int) : RandomBytes {
        private var c = start
        override fun next(size: Int) = ByteArray(size) { (c++ and 0xFF).toByte() }
    }

    private fun hex(b: ByteArray) = b.joinToString("") {
        val v = it.toInt() and 0xFF
        "0123456789abcdef"[v shr 4].toString() + "0123456789abcdef"[v and 0x0F]
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    @Test
    fun write() {
        if (System.getProperty("qrlesspay.writeVectors") != "true") return

        val ref = NearPayProtocol.mint(Seq(0), "Jiří", spayd, 1_000, 90, 25_000)
        val advertHex = hex(BeaconCodec.encode(ref.advert))
        val validCbor = ref.bundle.toCbor()

        // ── Structural: the decoder must refuse these outright ──────────────────
        val structural = mutableListOf<Triple<String, String, String>>() // id, why, hex

        structural += Triple(
            "struct-indefinite-map-text-keys",
            "Indefinite-length map with text keys — what kotlinx-serialization-cbor emitted before #450. A conformant decoder must refuse it rather than tolerate a second dialect.",
            "bf6776657273696f6e01637369649f1820182118221823ff",
        )
        structural += Triple(
            "struct-truncated-head",
            "One byte. Nothing can be read from it.",
            hex(validCbor.copyOfRange(0, 1)),
        )
        structural += Triple(
            "struct-truncated-mid-value",
            "Cut inside a byte-string value: the length says more bytes follow than exist.",
            hex(validCbor.copyOfRange(0, validCbor.size / 2)),
        )
        structural += Triple(
            "struct-trailing-byte",
            "A well-formed bundle followed by one extra byte. Trailing data is a framing error, not slack to ignore.",
            hex(validCbor + byteArrayOf(0x00)),
        )
        structural += Triple(
            "struct-wrong-pair-count",
            "Map header claims 6 pairs. The profile has exactly 7.",
            hex(validCbor.copyOf().also { it[0] = ((5 shl 5) or 6).toByte() }),
        )
        structural += Triple(
            "struct-unknown-key",
            "Key 9, which this profile does not define. An unknown key is a different protocol, not an extension to skip.",
            hex(validCbor.copyOf().also { it[1] = 0x09 }),
        )
        structural += Triple(
            "struct-non-canonical-integer",
            "version encoded as 0x1801 (one-byte argument holding 1) rather than the direct 0x01. Same value, second spelling — a payload under a signature must have exactly one.",
            hex(validCbor.copyOfRange(0, 2) + byteArrayOf(0x18, 0x01) + validCbor.copyOfRange(3, validCbor.size)),
        )
        structural += Triple(
            "struct-text-where-bytes-belong",
            "sid declared as a text string instead of a byte string.",
            hex(validCbor.copyOf().also { it[3] = 0x64 }),
        )
        structural += Triple(
            "struct-empty",
            "Zero bytes.",
            "",
        )
        structural += Triple(
            "struct-garbage",
            "A map header repeated 64 times. Well-formed enough to start, nonsense immediately after.",
            hex(ByteArray(64) { 0xA7.toByte() }),
        )

        // ── Semantic: these decode, and verification must reject each for its own reason ──
        val semantic = mutableListOf<List<String>>() // id, why, advertHex, bundleHex, now, reason

        fun sem(id: String, why: String, advert: BeaconPayload, bundle: NearPayBundle, now: Long, reason: String) {
            semantic += listOf(id, why, hex(BeaconCodec.encode(advert)), hex(bundle.toCbor()), now.toString(), reason)
        }

        sem(
            "sem-version", "Bundle announces protocol version 2.",
            ref.advert, ref.bundle.copy(version = 2), 1_010, "version",
        )
        sem(
            "sem-field-size", "sid is 3 bytes; the profile fixes it at 4.",
            ref.advert, ref.bundle.copy(sid = ref.bundle.sid.copyOfRange(0, 3)), 1_010, "field-size",
        )
        sem(
            "sem-key-or-sig-size", "Public key truncated to 31 bytes.",
            ref.advert, ref.bundle.copy(pk = ref.bundle.pk.copyOfRange(0, 31)), 1_010, "key-or-sig-size",
        )
        sem(
            "sem-sid-mismatch", "Advert announces a different session than the bundle it points at.",
            ref.advert.copy(sid = byteArrayOf(9, 9, 9, 9)), ref.bundle, 1_010, "sid-mismatch",
        )
        sem(
            "sem-advert-bundle-binding",
            "Advert's key hash does not match SHA-256(pk)[:2] — the swapped-bundle case: a nearby device answering the GATT read with someone else's signed bundle.",
            ref.advert.copy(keyHash = byteArrayOf(0x00, 0x00)), ref.bundle, 1_010, "advert-bundle-binding",
        )
        sem(
            "sem-expired", "Verified after exp.",
            ref.advert, ref.bundle, 1_200, "expired",
        )
        sem(
            "sem-exp-too-far", "exp is further out than now + the 90 s ceiling.",
            ref.advert, ref.bundle, 900, "exp-too-far",
        )
        run {
            // Tamper with the amount after signing: the classic attack the signature exists to stop.
            val tampered = ref.bundle.copy(spayd = spayd.replace("250.00", "950.00"))
            sem(
                "sem-bad-signature",
                "Amount changed from 250.00 to 950.00 after signing. The signature no longer covers the payload.",
                ref.advert, tampered, 1_010, "bad-signature",
            )
        }
        run {
            // Correctly signed, but the payload is not a SPAYD — signature valid, content useless.
            val junk = NearPayProtocol.mint(Seq(0), "Jiří", "not-a-spayd-at-all", 1_000, 90, 25_000)
            sem(
                "sem-bad-spayd",
                "Validly signed by its own key, but the payload does not parse as SPAYD. A good signature over rubbish is still rubbish.",
                junk.advert, junk.bundle, 1_010, "bad-spayd",
            )
        }

        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"profile\": \"qrlesspay-v1\",\n")
        sb.append("  \"note\": \"Generated from the reference implementation. Implementations agree on the happy path and diverge on which failures they notice, so this corpus — not the positive one — is where conformance is actually decided.\",\n")
        sb.append("  \"referenceAdvertHex\": \"$advertHex\",\n")
        sb.append("  \"structural\": [\n")
        structural.forEachIndexed { i, (id, why, h) ->
            sb.append("    { \"id\": \"$id\", \"why\": \"${esc(why)}\", \"bundleCborHex\": \"$h\", \"expect\": \"decode-fails\" }")
            sb.append(if (i == structural.lastIndex) "\n" else ",\n")
        }
        sb.append("  ],\n  \"semantic\": [\n")
        semantic.forEachIndexed { i, row ->
            sb.append(
                "    { \"id\": \"${row[0]}\", \"why\": \"${esc(row[1])}\", \"advertHex\": \"${row[2]}\", " +
                    "\"bundleCborHex\": \"${row[3]}\", \"nowEpochSec\": ${row[4]}, \"expect\": \"rejected\", \"reason\": \"${row[5]}\" }",
            )
            sb.append(if (i == semantic.lastIndex) "\n" else ",\n")
        }
        sb.append("  ],\n")
        sb.append("  \"replay\": { \"why\": \"The same bundle presented twice to one device. First is accepted, second must be rejected as replayed.\", \"advertHex\": \"$advertHex\", \"bundleCborHex\": \"${hex(validCbor)}\", \"nowEpochSec\": 1010, \"reason\": \"replayed\" }\n")
        sb.append("}\n")

        val out = File(System.getProperty("qrlesspay.vectorsDir") ?: "../conformance", "negative-vectors.json")
        out.writeText(sb.toString())
        println("WROTE ${out.absolutePath} structural=${structural.size} semantic=${semantic.size}")
    }
}
