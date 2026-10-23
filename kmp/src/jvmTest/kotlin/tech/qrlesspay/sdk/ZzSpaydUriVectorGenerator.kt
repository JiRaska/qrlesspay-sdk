// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package tech.qrlesspay.sdk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import kotlin.test.Test

/**
 * Writes `conformance/spayd-uri-vectors.json` from this implementation. A no-op unless
 * `-Dqrlesspay.writeVectors=true`, same contract as the other generators: an ordinary test run
 * cannot rewrite a committed artifact.
 *
 * The positive cases are produced by [SpaydUri.wrap]; the negative cases are written by hand
 * here because they are *inputs*, and each carries the reason this implementation refuses it.
 * The Swift suite asserts both the URI and the reason, so the two cores cannot drift to accept
 * different subsets of malformed input without a red build.
 */
class ZzSpaydUriVectorGenerator {

    private val spayd = "SPD*1.0*ACC:CZ6508000000192000145399*AM:250.00*CC:CZK*RN:Jiri"

    @Test
    fun writeVectors() {
        if (System.getProperty("qrlesspay.writeVectors") != "true") return

        val positive = listOf(
            Triple("typical", spayd, null),
            Triple("typical-relay-0800", spayd, "0800"),
            Triple("bic", "SPD*1.0*ACC:CZ6508000000192000145399+GIBACZPX*AM:1.00*CC:CZK", null),
            Triple("message-with-space-and-slash", "SPD*1.0*ACC:CZ6508000000192000145399*MSG:Faktura 123/2026", null),
            Triple("every-delimiter", "SPD*1.0*ACC:CZ6508000000192000145399*MSG:50% #7?a&b=c%", "0300"),
            Triple("utf8-diacritics", "SPD*1.0*ACC:CZ6508000000192000145399*RN:Jiří Nováček", null),
            Triple("x-vs", "SPD*1.0*ACC:CZ6508000000192000145399*AM:250.00*CC:CZK*X-VS:123456*X-SS:1", null),
        ).map { (name, s, code) ->
            buildJsonObject {
                put("name", JsonPrimitive(name))
                put("spayd", JsonPrimitive(s))
                put("bankCode", code?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
                put("uri", JsonPrimitive(SpaydUri.wrap(s, code)))
            }
        }

        // The domain-verified envelope, produced by wrapLink.
        val link = listOf(
            Triple("link-common", spayd, null),
            Triple("link-bank-9998", spayd, "9998"),
            Triple("link-bic-plus-is-encoded", "SPD*1.0*ACC:CZ6508000000192000145399+GIBACZPX*AM:1.00*CC:CZK", null),
            Triple("link-message-with-space", "SPD*1.0*ACC:CZ6508000000192000145399*MSG:Faktura 123/2026", "0800"),
        ).map { (name, s, code) ->
            buildJsonObject {
                put("name", JsonPrimitive(name))
                put("spayd", JsonPrimitive(s))
                put("bankCode", code?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
                put("uri", JsonPrimitive(SpaydUri.wrapLink(s, code)))
            }
        }

        // Accepted on input although never produced: each must unwrap to exactly `expectedSpayd`.
        val tolerated = listOf(
            "opaque-no-slashes" to "spayd:$spayd",
            "no-version-segment" to "spayd://$spayd",
            "upper-case-scheme" to "SPAYD://v1/$spayd",
            "upper-case-relay-scheme" to "SPAYD-0800://v1/$spayd",
            "lower-case-hex-escape" to "spayd://v1/SPD*1.0*ACC:CZ6508000000192000145399*AM:250.00*CC:CZK*RN:Ji%72i",
            "escaped-prefix-decodes-first" to "spayd://v1/%53PD*1.0*ACC:CZ6508000000192000145399*AM:250.00*CC:CZK*RN:Jiri",
            "surrounding-whitespace" to "  spayd://v1/$spayd \n",
            "link-upper-case-host" to "https://OPEN-BANK.TECH/pay/v1?$spayd",
            "link-trailing-slash" to "https://open-bank.tech/pay/v1/?$spayd",
            "link-with-fragment" to "https://open-bank.tech/pay/v1?$spayd#x",
        ).map { (name, uri) ->
            buildJsonObject {
                put("name", JsonPrimitive(name))
                put("uri", JsonPrimitive(uri))
                put("expectedSpayd", JsonPrimitive(spayd))
            }
        }

        val negative = listOf(
            Triple("https-not-spayd", "https://pay.example/v1/$spayd", "bad-link-host"),
            Triple("bare-descriptor-is-not-a-uri", spayd, "not-spayd-uri"),
            Triple("no-scheme-at-all", "v1/$spayd", "not-spayd-uri"),
            Triple("spaydx-scheme", "spaydx://v1/$spayd", "not-spayd-uri"),
            Triple("empty", "", "not-spayd-uri"),
            Triple("link-wrong-host", "https://pay.example.org/pay/v1?$spayd", "bad-link-host"),
            Triple("link-lookalike-host", "https://open-bank.tech.evil.example/pay/v1?$spayd", "bad-link-host"),
            Triple("link-http-not-https", "http://open-bank.tech/pay/v1?$spayd", "not-spayd-uri"),
            Triple("link-wrong-path", "https://open-bank.tech/app/oauth/callback?$spayd", "bad-version"),
            Triple("link-bank-code-letters", "https://open-bank.tech/pay/v1/08OO?$spayd", "bad-bank-code"),
            Triple("link-no-query", "https://open-bank.tech/pay/v1", "not-spayd"),
            Triple("link-raw-space", "https://open-bank.tech/pay/v1?SPD*1.0*ACC:CZ6508000000192000145399*MSG:Faktura 123", "illegal-character"),
            Triple("bank-code-too-short", "spayd-80://v1/$spayd", "bad-bank-code"),
            Triple("bank-code-letters", "spayd-08OO://v1/$spayd", "bad-bank-code"),
            Triple("bank-code-empty", "spayd-://v1/$spayd", "bad-bank-code"),
            Triple("version-v2", "spayd://v2/$spayd", "bad-version"),
            Triple("host-not-version", "spayd://pay/$spayd", "bad-version"),
            Triple("version-without-payload", "spayd://v1", "bad-version"),
            Triple("raw-space", "spayd://v1/SPD*1.0*ACC:CZ6508000000192000145399*MSG:Faktura 123", "illegal-character"),
            Triple("raw-utf8", "spayd://v1/SPD*1.0*ACC:CZ6508000000192000145399*RN:Jiří", "illegal-character"),
            Triple("control-byte", "spayd://v1/SPD*1.0*ACC:CZ6508000000192000145399\u0007", "illegal-character"),
            Triple("escaped-control-byte", "spayd://v1/SPD*1.0*ACC:CZ6508000000192000145399*MSG:a%07b", "illegal-character"),
            Triple("escaped-nul", "spayd://v1/SPD*1.0*ACC:CZ6508000000192000145399*MSG:a%00b", "illegal-character"),
            Triple("truncated-escape", "spayd://v1/SPD*1.0*ACC:CZ6508000000192000145399*MSG:a%2", "bad-percent-encoding"),
            Triple("non-hex-escape", "spayd://v1/SPD*1.0*ACC:CZ6508000000192000145399*MSG:a%ZZ", "bad-percent-encoding"),
            Triple("invalid-utf8-after-decoding", "spayd://v1/SPD*1.0*ACC:CZ6508000000192000145399*MSG:%C3", "bad-percent-encoding"),
            Triple("empty-payload", "spayd://v1/", "not-spayd"),
            Triple("not-a-descriptor", "spayd://v1/hello", "not-spayd"),
            Triple("descriptor-without-account", "spayd://v1/SPD*1.0*AM:250.00*CC:CZK", "not-spayd"),
            Triple("lower-case-spd", "spayd://v1/spd*1.0*ACC:CZ6508000000192000145399", "not-spayd"),
        ).map { (name, uri, reason) ->
            buildJsonObject {
                put("name", JsonPrimitive(name))
                put("uri", JsonPrimitive(uri))
                put("reason", JsonPrimitive(reason))
            }
        }

        val json = JsonObject(
            mapOf(
                "generatedBy" to JsonPrimitive("kmp ZzSpaydUriVectorGenerator"),
                "positive" to JsonArray(positive),
                "link" to JsonArray(link),
                "tolerated" to JsonArray(tolerated),
                "negative" to JsonArray(negative),
            ),
        ).toString()

        val out = File(System.getProperty("qrlesspay.vectorsDir") ?: "../conformance", "spayd-uri-vectors.json")
        out.writeText(json)
        println("WROTE ${out.absolutePath}")
    }
}
