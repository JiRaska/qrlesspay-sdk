// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package tech.qrlesspay.sdk

import android.content.Context
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbControleeSessionScope
import androidx.core.uwb.UwbControllerSessionScope
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android UWB ranging over `androidx.core.uwb` — the optional enhancement of spec §5.
 *
 * **Android's stack is asymmetric where Apple's is not.** FiRa splits a session into a *controller*,
 * which owns the channel, preamble and session id, and a *controlee*, which joins with only its
 * address. The payee takes the controller role here because it is the side already advertising, so
 * it is the side whose parameters can travel in the token the payer reads.
 *
 * Everything about this is best-effort. No radio, no permission, a peer on the other platform, or a
 * session that simply never converges all end the same way: [ProximityOutcome.Downgraded], and the
 * payer carries on with the RSSI baseline and the §6 confirmation. Ranging may sharpen the
 * proximity claim; it may never be required to make one.
 */
class AndroidUwbRanger(context: Context) {

    private val context = context.applicationContext
    private val manager: UwbManager? = runCatching { UwbManager.createInstance(this.context) }.getOrNull()

    /** Whether this device has a usable UWB radio at all. Most Android devices do not. */
    suspend fun isSupported(): Boolean = withTimeoutOrNull(CAPABILITY_TIMEOUT_MS) {
        manager?.let { runCatching { it.controleeSessionScope() }.isSuccess } ?: false
    } ?: false

    /**
     * Payee side. Opens a controller session and returns the token a payer needs to join, or null
     * when this device cannot range.
     */
    suspend fun openControllerSession(): ControllerSession? {
        val mgr = manager ?: return null
        val scope = runCatching { mgr.controllerSessionScope() }.getOrNull() ?: return null
        return ControllerSession(scope)
    }

    /**
     * Payer side. Joins the session described by [peerToken] and waits for the first distance.
     *
     * Returns a downgrade rather than throwing for every failure mode, because none of them is
     * actionable by the caller: the answer to "ranging did not work" is always the same, and it is
     * to carry on with the tile the payer already tapped.
     */
    suspend fun rangeAsControlee(peerToken: ByteArray): ProximityOutcome {
        val token = UwbTokenCodec.decode(peerToken)
            ?: return ProximityOutcome.Downgraded("unreadable-peer-token")
        if (token !is UwbToken.Controller) {
            // An Apple token, or a controlee token where a controller's was expected. Detected
            // rather than attempted: a session built from misread parameters never converges, and
            // waiting for it is worse than falling back immediately.
            return ProximityOutcome.Downgraded("cross-platform-uwb-unsupported")
        }
        val mgr = manager ?: return ProximityOutcome.Downgraded("no-local-uwb")
        val scope: UwbControleeSessionScope = runCatching { mgr.controleeSessionScope() }.getOrNull()
            ?: return ProximityOutcome.Downgraded("no-local-uwb")

        return try {
            val params = RangingParameters(
                uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR,
                sessionId = token.sessionId,
                subSessionId = 0,
                sessionKeyInfo = null,
                subSessionKeyInfo = null,
                complexChannel = UwbComplexChannel(token.channel, token.preambleIndex),
                peerDevices = listOf(UwbDevice(UwbAddress(token.address))),
                updateRateType = RangingParameters.RANGING_UPDATE_RATE_FREQUENT,
            )
            val metres = withTimeoutOrNull(RANGING_TIMEOUT_MS) {
                scope.prepareSession(params)
                    .mapNotNull { (it as? RangingResult.RangingResultPosition)?.position?.distance?.value }
                    .first()
            }
            metres?.let { ProximityOutcome.Ranged(it.toDouble()) }
                ?: ProximityOutcome.Downgraded("ranging-timed-out")
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            ProximityOutcome.Downgraded("ranging-failed")
        }
    }

    /** An open controller session and the token that lets a payer join it. */
    class ControllerSession(private val scope: UwbControllerSessionScope) {
        val token: ByteArray
            get() = UwbTokenCodec.encode(
                UwbToken.Controller(
                    address = scope.localAddress.address,
                    channel = scope.uwbComplexChannel.channel,
                    preambleIndex = scope.uwbComplexChannel.preambleIndex,
                    // Derived from the address rather than random: both sides must agree on it, and
                    // the address is the only thing already crossing the air.
                    sessionId = scope.localAddress.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) },
                ),
            )
    }

    private companion object {
        const val CAPABILITY_TIMEOUT_MS = 2_000L
        const val RANGING_TIMEOUT_MS = 6_000L
    }
}
