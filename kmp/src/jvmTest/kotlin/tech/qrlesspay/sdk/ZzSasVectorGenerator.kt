// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package tech.qrlesspay.sdk

import io.github.andreypfau.curve25519.x25519.X25519
import java.io.File
import kotlin.test.Test

/** Writes `conformance/sas-vectors.json`. No-op without -Dqrlesspay.writeVectors=true. */
class ZzSasVectorGenerator {

    private fun hex(b: ByteArray) = b.joinToString("") {
        val v = it.toInt() and 0xFF
        "0123456789abcdef"[v shr 4].toString() + "0123456789abcdef"[v and 0x0F]
    }

    @Test
    fun write() {
        if (System.getProperty("qrlesspay.writeVectors") != "true") return

        // Fixed scalars: a vector must be reproducible, so the ephemeral keys are supplied rather
        // than generated. Production never does this — see Sas.generateKeyPair.
        val payeeScalar = ByteArray(32) { (it + 1).toByte() }
        val payerScalar = ByteArray(32) { (200 - it).toByte() }
        val base = ByteArray(32).also { it[0] = 9 }
        // Copies everywhere: x25519 writes into its point argument (see Sas.agree).
        val payeePub = X25519.x25519(payeeScalar.copyOf(), base.copyOf())
        val payerPub = X25519.x25519(payerScalar.copyOf(), base.copyOf())

        val sid = byteArrayOf(0x20, 0x21, 0x22, 0x23)
        val payeeEd = ByteArray(32) { (0x40 + it).toByte() }

        val transcript = Sas.transcript(sid, payeeEd, payeePub, payerPub)
        val sharedFromPayee = X25519.x25519(payeeScalar.copyOf(), payerPub.copyOf())
        val sharedFromPayer = X25519.x25519(payerScalar.copyOf(), payeePub.copyOf())
        check(sharedFromPayee.contentEquals(sharedFromPayer)) { "X25519 agreement disagreed with itself" }

        val rows = listOf(4, 6, 8).joinToString(",\n") { d ->
            """    { "digits": $d, "code": "${Sas.code(sharedFromPayee, transcript, d)}" }"""
        }

        val json = """
            {
              "profile": "qrlesspay-v1.1-proposal",
              "note": "SAS derivation. The spec (§4) names X25519, HKDF and the label and stops there; these pin the transcript, the digit count and how digits are drawn, so two implementations cannot show a payer different numbers for an honest link.",
              "payeeScalarHex": "${hex(payeeScalar)}",
              "payerScalarHex": "${hex(payerScalar)}",
              "payeeEphemeralPkHex": "${hex(payeePub)}",
              "payerEphemeralPkHex": "${hex(payerPub)}",
              "sidHex": "${hex(sid)}",
              "payeeEd25519PkHex": "${hex(payeeEd)}",
              "transcriptHex": "${hex(transcript)}",
              "sharedSecretHex": "${hex(sharedFromPayee)}",
              "codes": [
            $rows
              ]
            }
        """.trimIndent() + "\n"

        val out = File(System.getProperty("qrlesspay.vectorsDir") ?: "../conformance", "sas-vectors.json")
        out.writeText(json)
        println("WROTE ${out.absolutePath}")
    }
}
