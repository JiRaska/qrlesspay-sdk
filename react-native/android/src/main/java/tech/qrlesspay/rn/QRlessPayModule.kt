// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package tech.qrlesspay.rn

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tech.qrlesspay.sdk.AndroidNearPayTransport
import tech.qrlesspay.sdk.NearPayController
import tech.qrlesspay.sdk.NearbyTile
import tech.qrlesspay.sdk.RandomBytes
import tech.qrlesspay.sdk.VerifyResult
import java.security.SecureRandom

/**
 * Android bridge, mirroring `ios/QRlessPayModule.swift`.
 *
 * Nothing here decides anything: no verification, no filtering, no re-ordering. The controller has
 * already applied the proximity gate and the full §3 verification order, and duplicating any of it
 * on this side would create a second place for it to be wrong — in the layer an adopter is most
 * likely to patch and least likely to test.
 */
class QRlessPayModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "QRlessPay"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val controller by lazy {
        NearPayController(
            transport = AndroidNearPayTransport(reactContext),
            // SecureRandom().nextBytes, not generateSeed: the latter taps the blocking seed source
            // and can stall on a device with little entropy, which on this path is a request screen
            // that appears to hang.
            random = RandomBytes { size -> ByteArray(size).also { SecureRandom().nextBytes(it) } },
            now = { System.currentTimeMillis() / 1_000L },
        )
    }

    /** Native tiles are retained here; only display fields cross the bridge. */
    private var tiles: Map<String, NearbyTile> = emptyMap()

    override fun invalidate() {
        // A beacon left advertising after the JS context goes away broadcasts a name the user
        // believes they stopped broadcasting.
        controller.stopReceiving()
        controller.stopDiscovery()
        scope.cancel()
        super.invalidate()
    }

    // ── Payee ───────────────────────────────────────────────────────────────────

    @ReactMethod
    fun startReceiving(firstName: String, spayd: String, amountMinor: Double?, promise: Promise) {
        controller.startReceiving(firstName, spayd, amountMinor?.toInt())
        promise.resolve(null)
    }

    @ReactMethod
    fun stopReceiving(promise: Promise) {
        controller.stopReceiving()
        promise.resolve(null)
    }

    // ── Payer ───────────────────────────────────────────────────────────────────

    @ReactMethod
    fun startDiscovery(promise: Promise) {
        controller.startDiscovery { discovered ->
            tiles = discovered.associateBy { it.peerId }
            emit(discovered)
        }
        promise.resolve(null)
    }

    @ReactMethod
    fun stopDiscovery(promise: Promise) {
        controller.stopDiscovery()
        tiles = emptyMap()
        promise.resolve(null)
    }

    @ReactMethod
    fun resolve(peerId: String, promise: Promise) {
        val tile = tiles[peerId]
        if (tile == null) {
            // A tile that has aged out is an ordinary outcome, not an exception: the peer simply
            // stopped advertising. Rejecting the promise would make the host app treat it as a
            // crash-worthy error rather than a tap to explain.
            promise.resolve(rejection("fetch-failed"))
            return
        }
        scope.launch {
            when (val result = controller.resolve(tile)) {
                is VerifyResult.Ok -> promise.resolve(
                    Arguments.createMap().apply {
                        putBoolean("ok", true)
                        putString("spayd", result.spayd)
                    },
                )
                is VerifyResult.Rejected -> promise.resolve(rejection(result.reason))
            }
        }
    }

    // RN requires these two for NativeEventEmitter; the counting is the platform's, not ours.
    @ReactMethod fun addListener(eventName: String) = Unit

    @ReactMethod fun removeListeners(count: Int) = Unit

    private fun rejection(reason: String): WritableMap = Arguments.createMap().apply {
        putBoolean("ok", false)
        putString("reason", reason)
    }

    private fun emit(discovered: List<NearbyTile>) {
        val payload: WritableArray = Arguments.createArray()
        discovered.forEach { tile ->
            payload.pushMap(
                Arguments.createMap().apply {
                    putString("peerId", tile.peerId)
                    putString("firstName", tile.firstName)
                    tile.amountMinor?.let { putInt("amountMinor", it) } ?: putNull("amountMinor")
                    putInt("rssi", tile.rssi)
                },
            )
        }
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("qrlesspay:tiles", payload)
    }
}
