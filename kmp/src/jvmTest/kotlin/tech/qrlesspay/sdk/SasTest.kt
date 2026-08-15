// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package tech.qrlesspay.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.fail

class SasTest {

    private fun unhex(s: String) = ByteArray(s.length / 2) {
        ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte()
    }

    private fun hex(b: ByteArray) = b.joinToString("") {
        val v = it.toInt() and 0xFF
        "0123456789abcdef"[v shr 4].toString() + "0123456789abcdef"[v and 0x0F]
    }

    private val v by lazy {
        val f = File("../conformance/sas-vectors.json")
        if (!f.exists()) fail("sas vectors not found at ${f.absolutePath}")
        Json.parseToJsonElement(f.readText()).jsonObject
    }

    private fun str(k: String) = v[k]!!.jsonPrimitive.content

    /**
     * The regression this file exists for.
     *
     * `X25519.x25519(scalar, point)` **writes into its point argument** — measured, not inferred,
     * after the first SAS vectors came out with both ephemeral public keys holding the shared
     * secret. Nothing in the signature suggests it, and the basepoint case does not mutate, so a
     * probe that only derives keys sees nothing wrong. If someone drops the defensive copy in
     * `Sas.agree`, this goes red instead of the vectors quietly becoming nonsense.
     */
    @Test
    fun derivingASharedSecretDoesNotMutateThePeersPublicKey() {
        val random = object : RandomBytes {
            private var c = 0
            override fun next(size: Int) = ByteArray(size) { (c++ and 0xFF).toByte() }
        }
        val local = Sas.generateKeyPair(random)
        val peerPk = unhex(str("payerEphemeralPkHex"))
        val before = peerPk.copyOf()

        Sas.codeFor(
            localKeyPair = local,
            peerEphemeralPk = peerPk,
            sid = unhex(str("sidHex")),
            payeeEd25519Pk = unhex(str("payeeEd25519PkHex")),
            payeeEphemeralPk = unhex(str("payeeEphemeralPkHex")),
            payerEphemeralPk = peerPk,
        )
        assertEquals(hex(before), hex(peerPk), "the caller's public key must survive the agreement")
    }

    @Test
    fun codesMatchTheVectorsAtEveryWidth() {
        val shared = unhex(str("sharedSecretHex"))
        val transcript = unhex(str("transcriptHex"))
        v["codes"]!!.jsonArray.map { it.jsonObject }.forEach { c ->
            val digits = c["digits"]!!.jsonPrimitive.content.toInt()
            assertEquals(c["code"]!!.jsonPrimitive.content, Sas.code(shared, transcript, digits), "digits=$digits")
        }
    }

    @Test
    fun theTranscriptMatchesTheVector() {
        assertEquals(
            str("transcriptHex"),
            hex(
                Sas.transcript(
                    unhex(str("sidHex")),
                    unhex(str("payeeEd25519PkHex")),
                    unhex(str("payeeEphemeralPkHex")),
                    unhex(str("payerEphemeralPkHex")),
                ),
            ),
        )
    }

    /** The code attests to the bundle, not merely to the link: change the bundle, change the code. */
    @Test
    fun theCodeIsBoundToTheBundle() {
        val shared = unhex(str("sharedSecretHex"))
        val sid = unhex(str("sidHex"))
        val ed = unhex(str("payeeEd25519PkHex"))
        val payeePk = unhex(str("payeeEphemeralPkHex"))
        val payerPk = unhex(str("payerEphemeralPkHex"))

        val real = Sas.code(shared, Sas.transcript(sid, ed, payeePk, payerPk))
        val otherEd = ed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNotEquals(real, Sas.code(shared, Sas.transcript(sid, otherEd, payeePk, payerPk)))
    }
}
