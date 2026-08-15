// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package tech.qrlesspay.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * The UWB token corpus, shared with the Swift implementation.
 *
 * The rejection cases carry the weight. A token from the other platform must **fail** to decode —
 * misreading one produces a ranging session whose distances derive from nonsense, which is worse
 * than no ranging at all: it shows a payer a confident "0.3 m" for a peer that could be anywhere.
 */
class UwbCorpusTest {

    private fun unhex(s: String) = ByteArray(s.length / 2) {
        ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte()
    }

    private fun hex(b: ByteArray) = b.joinToString("") {
        val v = it.toInt() and 0xFF
        "0123456789abcdef"[v shr 4].toString() + "0123456789abcdef"[v and 0x0F]
    }

    private val corpus: JsonObject by lazy {
        val f = File("../conformance/uwb-vectors.json")
        if (!f.exists()) fail("uwb corpus not found at ${f.absolutePath}")
        Json.parseToJsonElement(f.readText()).jsonObject
    }

    private fun section(name: String) = corpus[name]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.int(k: String) = this[k]!!.jsonPrimitive.content.toInt()

    @Test
    fun validTokensDecodeAndReEncodeToTheSameBytes() {
        section("valid").forEach { c ->
            val token = assertNotNull(UwbTokenCodec.decode(unhex(c.str("hex"))), c.str("id"))
            when (c.str("kind")) {
                "controller" -> {
                    token as UwbToken.Controller
                    assertEquals(c.str("address"), hex(token.address), c.str("id"))
                    assertEquals(c.int("channel"), token.channel, c.str("id"))
                    assertEquals(c.int("preambleIndex"), token.preambleIndex, c.str("id"))
                    assertEquals(c.int("sessionId"), token.sessionId, c.str("id"))
                }
                "controlee" -> assertEquals(c.str("address"), hex((token as UwbToken.Controlee).address), c.str("id"))
                "opaque" -> assertEquals(c.str("blob"), hex((token as UwbToken.Opaque).blob), c.str("id"))
            }
            assertEquals(c.str("hex"), hex(UwbTokenCodec.encode(token)), "${c.str("id")}: re-encode")
        }
    }

    @Test
    fun everyRejectionCaseFailsToDecode() {
        section("rejected").forEach { c ->
            assertNull(UwbTokenCodec.decode(unhex(c.str("hex"))), "${c.str("id")}: ${c.str("why")}")
        }
    }

    @Test
    fun thePolicyDowngradesExactlyWhereItShould() {
        section("policy").forEach { c ->
            val tokenNode = c["peerTokenHex"]!!
            val token = if (tokenNode is JsonNull) null else unhex(tokenNode.jsonPrimitive.content)
            val outcome = ProximityPolicy.attempt(
                localSupportsUwb = c.str("localSupportsUwb").toBoolean(),
                peerAdvertisedUwb = c.str("peerAdvertisedUwb").toBoolean(),
                peerToken = token,
                localIsAppleStack = c.str("localIsAppleStack").toBoolean(),
            )
            val expected = c.str("expect")
            if (expected == "attempt") {
                assertNull(outcome, "${c.str("id")}: ranging is worth attempting here")
            } else {
                assertEquals(expected, (outcome as ProximityOutcome.Downgraded).why, c.str("id"))
            }
        }
    }
}
