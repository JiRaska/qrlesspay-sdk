// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package tech.qrlesspay.sdk

/**
 * Enhanced proximity (wire spec §5) — optional, never required.
 *
 * **The spec describes UWB in prose and gives it no wire format.** It says the capability is
 * "negotiated, best-effort" without defining how a device announces it or how ranging parameters
 * cross between two phones. This file is therefore a **v1.1 proposal**, not an implementation of
 * something normative, and it is marked as such everywhere it surfaces. The bits it proposes:
 * advert flag `0x8` for UWB-capable, and a fourth GATT characteristic carrying the token below.
 *
 * Two facts decide the whole design, and both are the unwelcome kind:
 *
 * 1. **Apple's Nearby Interaction and Android's FiRa stack do not interoperate.** A cross-platform
 *    pair cannot range, at any effort, and the honest response is to detect that in the codec and
 *    downgrade rather than to attempt a session that will never converge.
 * 2. **UWB hardware is a minority** — iPhone 11 and later excluding the SE, and a slice of flagship
 *    Android. Most devices have no radio at all, so RSSI remains the baseline and UWB may only ever
 *    sharpen it.
 *
 * Consequence for the security story: UWB is the only *cryptographic* answer to a relay attack, and
 * it is unavailable on most pairs. Nothing here may become a precondition for paying.
 */
object Uwb {

    /** Advert flag proposed for "this device can range". Reserved-unused until the spec adopts it. */
    const val FLAG_UWB_CAPABLE = 0x8

    /** Proposed fourth characteristic, for exchanging the token below. */
    const val CHAR_UWB_TOKEN_UUID = "0000C3A6-2F3B-4E8A-9A5E-0B0E6F1C2D3A"
}

/**
 * A ranging token as it crosses the air.
 *
 * The platforms disagree about what a token even is. Android/FiRa needs concrete session parameters
 * and distinguishes the **controller** (which owns them) from the **controlee** (which joins);
 * Apple's `NIDiscoveryToken` is an opaque blob with no such split. The encoding below carries all
 * three shapes and, critically, tags them — so an Apple token arriving at an Android parser is
 * *rejected* rather than read as a malformed FiRa token and acted on.
 *
 * That rejection is the point. Silently misparsing a foreign token produces a ranging session that
 * reports distances derived from nonsense, which is far worse than no ranging at all: it would give
 * a payer a confident "0.3 m" for a peer that could be anywhere.
 */
sealed interface UwbToken {
    /** FiRa controller: owns the session parameters the controlee joins. */
    data class Controller(
        val address: ByteArray,
        val channel: Int,
        val preambleIndex: Int,
        val sessionId: Int,
    ) : UwbToken {
        override fun equals(other: Any?): Boolean = this === other ||
            (
                other is Controller && address.contentEquals(other.address) &&
                    channel == other.channel && preambleIndex == other.preambleIndex && sessionId == other.sessionId
                )

        override fun hashCode(): Int {
            var r = address.contentHashCode()
            r = 31 * r + channel
            r = 31 * r + preambleIndex
            r = 31 * r + sessionId
            return r
        }
    }

    /** FiRa controlee: only its address; the controller supplies everything else. */
    data class Controlee(val address: ByteArray) : UwbToken {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is Controlee && address.contentEquals(other.address))

        override fun hashCode(): Int = address.contentHashCode()
    }

    /** Apple `NIDiscoveryToken`, opaque by design — never parsed, only handed back to the platform. */
    data class Opaque(val blob: ByteArray) : UwbToken {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is Opaque && blob.contentEquals(other.blob))

        override fun hashCode(): Int = blob.contentHashCode()
    }
}

/**
 * `[magic 'Q'][version][kind][payload]`.
 *
 * The magic byte and version exist so that a token from the other platform, or from a future
 * revision, fails to decode instead of decoding into something wrong. The app this was extracted
 * from learned that the hard way and asserts it in a test; the same assertion lives in the
 * conformance corpus here, in both languages.
 */
object UwbTokenCodec {

    private const val MAGIC = 'Q'.code.toByte()
    private const val VERSION: Byte = 1
    private const val KIND_CONTROLLER: Byte = 1
    private const val KIND_CONTROLEE: Byte = 2
    private const val KIND_OPAQUE: Byte = 3
    private const val HEADER = 3
    private const val ADDRESS_BYTES = 2
    private const val CONTROLLER_PAYLOAD = ADDRESS_BYTES + 1 + 1 + 4
    private const val BYTE_MASK = 0xFF

    fun encode(token: UwbToken): ByteArray = when (token) {
        is UwbToken.Controller -> byteArrayOf(MAGIC, VERSION, KIND_CONTROLLER) +
            token.address.copyOf(ADDRESS_BYTES) +
            byteArrayOf(token.channel.toByte(), token.preambleIndex.toByte()) +
            ByteArray(4) { ((token.sessionId ushr (24 - it * 8)) and BYTE_MASK).toByte() }

        is UwbToken.Controlee -> byteArrayOf(MAGIC, VERSION, KIND_CONTROLEE) + token.address.copyOf(ADDRESS_BYTES)

        is UwbToken.Opaque -> byteArrayOf(MAGIC, VERSION, KIND_OPAQUE) + token.blob
    }

    /** Null for anything that is not a token of this version — including the other platform's. */
    fun decode(bytes: ByteArray): UwbToken? {
        if (bytes.size < HEADER) return null
        if (bytes[0] != MAGIC || bytes[1] != VERSION) return null
        val payload = bytes.copyOfRange(HEADER, bytes.size)
        return when (bytes[2]) {
            KIND_CONTROLLER -> {
                if (payload.size != CONTROLLER_PAYLOAD) return null
                UwbToken.Controller(
                    address = payload.copyOfRange(0, ADDRESS_BYTES),
                    channel = payload[ADDRESS_BYTES].toInt() and BYTE_MASK,
                    preambleIndex = payload[ADDRESS_BYTES + 1].toInt() and BYTE_MASK,
                    sessionId = (0 until 4).fold(0) { acc, i ->
                        (acc shl 8) or (payload[ADDRESS_BYTES + 2 + i].toInt() and BYTE_MASK)
                    },
                )
            }
            KIND_CONTROLEE -> if (payload.size != ADDRESS_BYTES) null else UwbToken.Controlee(payload)
            KIND_OPAQUE -> if (payload.isEmpty()) null else UwbToken.Opaque(payload)
            else -> null
        }
    }
}

/** What a ranging attempt concluded. */
sealed interface ProximityOutcome {
    /** UWB ranged and the peer is within [metres]. */
    data class Ranged(val metres: Double) : ProximityOutcome

    /**
     * No UWB session was possible — no radio on one side, a cross-platform pair, a rejected token,
     * or the user declined. **Not an error**: the RSSI baseline already gated this tile, and the
     * §6 confirmation is what authorises the payment either way.
     */
    data class Downgraded(val why: String) : ProximityOutcome
}

/**
 * Decides whether a ranging attempt is even worth starting, before any radio is touched.
 *
 * Every branch that returns [ProximityOutcome.Downgraded] here is a session that would otherwise be
 * attempted and never converge — the cross-platform case most of all, where both devices have
 * working UWB hardware and still cannot range each other.
 */
object ProximityPolicy {

    /** Peer capability, as read from the advert flag and the token it published. */
    fun attempt(
        localSupportsUwb: Boolean,
        peerAdvertisedUwb: Boolean,
        peerToken: ByteArray?,
        localIsAppleStack: Boolean,
    ): ProximityOutcome? {
        if (!localSupportsUwb) return ProximityOutcome.Downgraded("no-local-uwb")
        if (!peerAdvertisedUwb) return ProximityOutcome.Downgraded("peer-not-uwb-capable")
        val raw = peerToken ?: return ProximityOutcome.Downgraded("no-peer-token")
        val token = UwbTokenCodec.decode(raw) ?: return ProximityOutcome.Downgraded("unreadable-peer-token")

        val peerIsAppleStack = token is UwbToken.Opaque
        if (peerIsAppleStack != localIsAppleStack) {
            // Both sides have UWB and still cannot range: Apple's peer protocol is not FiRa. Detected
            // here so the UI falls straight back to the tap list instead of waiting out a session
            // that will never converge.
            return ProximityOutcome.Downgraded("cross-platform-uwb-unsupported")
        }
        return null // worth attempting; the platform ranger takes it from here
    }
}
