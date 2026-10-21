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
import kotlin.test.assertIs
import kotlin.test.fail

/**
 * The SPAYD-URI corpus, as `swift/Tests/QRlessPayTests/SpaydUriCorpusTests.swift` runs it. Both
 * cores asserting the same URIs *and the same refusal reasons* is what keeps the camera path one
 * profile: a bank whose scanner accepts a malformed URI that the next bank's refuses is a support
 * ticket with two innocent parties.
 */
class SpaydUriCorpusTest {

    private val v by lazy {
        val f = File("../conformance/spayd-uri-vectors.json")
        if (!f.exists()) fail("spayd-uri vectors not found at ${f.absolutePath}")
        Json.parseToJsonElement(f.readText()).jsonObject
    }

    private fun reasonOf(r: SpaydUri.Rejection) = r.name.lowercase().replace('_', '-')

    @Test
    fun positiveVectorsWrapToTheCommittedUriAndBack() {
        for (c in v["positive"]!!.jsonArray.map { it.jsonObject }) {
            val name = c["name"]!!.jsonPrimitive.content
            val spayd = c["spayd"]!!.jsonPrimitive.content
            val code = c["bankCode"]!!.jsonPrimitive.takeIf { it.isString }?.content
            val uri = c["uri"]!!.jsonPrimitive.content
            assertEquals(uri, SpaydUri.wrap(spayd, code), name)
            val ok = assertIs<SpaydUri.Result.Ok>(SpaydUri.parse(uri), name)
            assertEquals(spayd, ok.value.spayd, name)
            assertEquals(code, ok.value.target.bankCode, name)
            assertEquals(spayd, SpaydUri.unwrap(uri), name)
        }
    }

    @Test
    fun toleratedFormsUnwrapToTheSameDescriptor() {
        for (c in v["tolerated"]!!.jsonArray.map { it.jsonObject }) {
            val name = c["name"]!!.jsonPrimitive.content
            assertEquals(c["expectedSpayd"]!!.jsonPrimitive.content, SpaydUri.unwrap(c["uri"]!!.jsonPrimitive.content), name)
        }
    }

    @Test
    fun negativeVectorsAreRefusedForTheStatedReason() {
        for (c in v["negative"]!!.jsonArray.map { it.jsonObject }) {
            val name = c["name"]!!.jsonPrimitive.content
            val r = assertIs<SpaydUri.Result.Rejected>(SpaydUri.parse(c["uri"]!!.jsonPrimitive.content), name)
            assertEquals(c["reason"]!!.jsonPrimitive.content, reasonOf(r.reason), name)
        }
    }
}
