// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package tech.qrlesspay.sdk

import android.content.Context
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Compile-time check of exactly the API the React Native Android bridge calls.
 *
 * That bridge cannot be compiled in this repository — it needs `com.facebook.react:react-android`,
 * which only resolves inside a host RN app. So the failure mode it is exposed to is a signature
 * drifting here and nobody noticing until an adopter's build breaks. This file closes that gap for
 * everything except the React types themselves: if the controller, transport or result shapes
 * change, this stops compiling.
 *
 * It deliberately does not *run* any of it — there is no radio in a unit test, and pretending
 * otherwise is how a green test comes to stand for something it never checked.
 */
class BridgeApiSurfaceTest {

    @Suppress("UNUSED_VARIABLE")
    @Test
    fun theBridgesCallsStillTypeCheck() {
        val makeController: (Context) -> NearPayController = { context ->
            NearPayController(
                transport = AndroidNearPayTransport(context),
                random = RandomBytes { size -> ByteArray(size) },
                now = { 0L },
            )
        }

        // Payee
        val payee: (NearPayController) -> Unit = { c ->
            c.startReceiving("Jiri", "SPD*1.0*ACC:CZ65", 25_000)
            c.stopReceiving()
        }

        // Payer
        val payer: (NearPayController) -> Unit = { c ->
            c.startDiscovery { tiles: List<NearbyTile> ->
                tiles.forEach { tile ->
                    val id: String = tile.peerId
                    val name: String = tile.firstName
                    val amount: Int? = tile.amountMinor
                    val rssi: Int = tile.rssi
                }
            }
            c.stopDiscovery()
        }

        // resolve() is suspend and returns the sealed result the bridge branches on.
        val branch: (VerifyResult) -> String = { result ->
            when (result) {
                is VerifyResult.Ok -> result.spayd
                is VerifyResult.Rejected -> result.reason
            }
        }

        assertNotNull(makeController)
        assertNotNull(payee)
        assertNotNull(payer)
        assertNotNull(branch)
    }
}
