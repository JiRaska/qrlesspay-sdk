// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package tech.qrlesspay.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs `conformance/negative-vectors.json`.
 *
 * This is where conformance is actually decided. Two implementations agree on the happy path by
 * construction — both were written from the same spec by someone who wanted them to work — and
 * diverge on which malformed inputs they notice. A corpus of valid bundles proves that neither is
 * broken; only a corpus of invalid ones proves they are the *same*.
 *
 * Lives in `jvmTest` rather than `commonTest` because reading a file needs a multiplatform resource
 * story this module does not have yet. That is a gap: the iOS targets do not run these.
 */
class NegativeCorpusTest {

    private fun unhex(s: String) = ByteArray(s.length / 2) {
        ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte()
    }

    private fun section(name: String): List<JsonObject> =
        when (val node = corpus[name]) {
            is JsonArray -> node.map { it.jsonObject }
            is JsonObject -> listOf(node)
            else -> fail("section $name missing")
        }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    private val corpus: JsonObject by lazy {
        val f = File("../conformance/negative-vectors.json")
        if (!f.exists()) fail("negative corpus not found at ${f.absolutePath}")
        Json.parseToJsonElement(f.readText()).jsonObject
    }

    @Test
    fun everyStructuralCaseIsRefusedByTheDecoder() {
        val cases = section("structural")
        assertTrue(cases.size >= 10, "expected the full structural corpus, got ${cases.size}")
        cases.forEach { c ->
            assertNull(
                NearPayBundle.fromCbor(unhex(c.str("bundleCborHex"))),
                "${c.str("id")}: ${c.str("why")}",
            )
        }
    }

    @Test
    fun everySemanticCaseIsRejectedForItsOwnReason() {
        val cases = section("semantic")
        assertTrue(cases.size >= 9, "expected the full semantic corpus, got ${cases.size}")
        cases.forEach { c ->
            val advert = BeaconCodec.decode(unhex(c.str("advertHex"))) ?: fail("${c.str("id")}: advert did not decode")
            val bundle = NearPayBundle.fromCbor(unhex(c.str("bundleCborHex")))
                ?: fail("${c.str("id")}: bundle did not decode, but this case is meant to reach verification")
            val result = NearPayProtocol.verify(
                advert, bundle, c.str("nowEpochSec").toLong(), TtlReplayGuard(now = { c.str("nowEpochSec").toLong() }),
            )
            // The reason matters as much as the rejection: two implementations that both refuse a
            // payload for different reasons will disagree the moment either starts acting on the
            // reason — telemetry, retry policy, or the copy a payer reads.
            assertEquals(
                c.str("reason"),
                (result as? VerifyResult.Rejected)?.reason,
                "${c.str("id")}: ${c.str("why")}",
            )
        }
    }

    @Test
    fun theReplayCaseIsAcceptedOnceThenRejected() {
        val c = section("replay").first()
        val advert = BeaconCodec.decode(unhex(c.str("advertHex")))!!
        val bundle = NearPayBundle.fromCbor(unhex(c.str("bundleCborHex")))!!
        val now = c.str("nowEpochSec").toLong()
        val guard = TtlReplayGuard(now = { now })

        assertTrue(NearPayProtocol.verify(advert, bundle, now, guard) is VerifyResult.Ok, "first presentation")
        assertEquals(
            c.str("reason"),
            (NearPayProtocol.verify(advert, bundle, now, guard) as VerifyResult.Rejected).reason,
            "second presentation",
        )
    }
}
