// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package tech.qrlesspay.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

private const val SPAYD = "SPD*1.0*ACC:CZ6508000000192000145399*AM:250.00*CC:CZK*RN:Jiri"
private const val URI = "spayd://v1/SPD*1.0*ACC:CZ6508000000192000145399*AM:250.00*CC:CZK*RN:Jiri"

/**
 * Runs on every target (no file access). The full corpus, shared with Swift, is in
 * `jvmTest/SpaydUriCorpusTest` — same split as the bundle vectors, for the same reason.
 */
class SpaydUriTest {

    @Test
    fun wrapAddsOnlyThePrefixToATypicalDescriptor() {
        assertEquals(URI, SpaydUri.wrap(SPAYD))
        assertEquals("spayd-0800://v1/" + URI.removePrefix("spayd://v1/"), SpaydUri.wrap(SPAYD, "0800"))
    }

    @Test
    fun unwrapAcceptsBothFormsAndReturnsTheSameBytes() {
        assertEquals(SPAYD, SpaydUri.unwrap(SPAYD))
        assertEquals(SPAYD, SpaydUri.unwrap(URI))
        assertEquals(SPAYD, SpaydUri.unwrap(SpaydUri.wrap(SPAYD, "0300")))
    }

    @Test
    fun roundTripSurvivesEveryCharacterAUriForbids() {
        val nasty = "SPD*1.0*ACC:CZ6508000000192000145399*MSG:Faktura 123/2026 50% #7?a&b=c%*RN:Jiří Nováček"
        val uri = SpaydUri.wrap(nasty)
        for (c in uri) check(c.code in 0x21..0x7E) { "raw byte ${c.code} leaked into the URI" }
        assertEquals(nasty, SpaydUri.unwrap(uri))
    }

    @Test
    fun theRelayTargetIsReportedToTheReceiver() {
        val ok = assertIs<SpaydUri.Result.Ok>(SpaydUri.parse(SpaydUri.wrap(SPAYD, "0300")))
        assertEquals("0300", ok.value.target.bankCode)
        assertEquals("spayd-0300", ok.value.target.scheme)
        assertNull(assertIs<SpaydUri.Result.Ok>(SpaydUri.parse(URI)).value.target.bankCode)
    }

    @Test
    fun wrapRefusesWhatEveryReceiverWouldReject() {
        assertFailsWith<IllegalArgumentException> { SpaydUri.wrap("hello") }
        assertFailsWith<IllegalArgumentException> { SpaydUri.wrap(SPAYD, "80") }
        assertFailsWith<IllegalArgumentException> { SpaydUri.wrap(SPAYD, "08OO") }
    }

    @Test
    fun unwrapIsNullForAnythingThatIsNotAPayment() {
        assertNull(SpaydUri.unwrap("https://example.com/SPD*1.0*ACC:CZ6508000000192000145399"))
        assertNull(SpaydUri.unwrap("spayd://v1/hello"))
        assertNull(SpaydUri.unwrap("SPD*1.0*AM:250.00"))
        assertNull(SpaydUri.unwrap(""))
    }

    /**
     * The negative that proves the guard: a URI with a raw space must not be repaired into a
     * payment. Drop the ILLEGAL_CHARACTER check and this test, not the corpus, is what goes red
     * on the Android target.
     */
    @Test
    fun aRawSpaceIsRefusedNotRepaired() {
        val r = SpaydUri.parse("spayd://v1/SPD*1.0*ACC:CZ6508000000192000145399*MSG:Faktura 123")
        assertEquals(SpaydUri.Result.Rejected(SpaydUri.Rejection.ILLEGAL_CHARACTER), r)
    }
}
