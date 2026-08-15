// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors.
import Foundation

/// Enhanced proximity (wire spec §5) — optional, never required.
///
/// **The spec describes UWB in prose and gives it no wire format.** It calls the capability
/// "negotiated, best-effort" without defining how a device announces it or how ranging parameters
/// cross between two phones. This is therefore a **v1.1 proposal**, mirroring `Uwb.kt`, and marked
/// as such wherever it surfaces.
///
/// Two facts decide the design, both unwelcome: **Apple's Nearby Interaction and Android's FiRa
/// stack do not interoperate**, so a cross-platform pair cannot range at any effort; and **UWB
/// hardware is a minority**, so RSSI stays the baseline and UWB may only sharpen it. Nothing here
/// may become a precondition for paying.
public enum Uwb {
    /// Advert flag proposed for "this device can range". Reserved-unused until the spec adopts it.
    public static let flagUwbCapable: UInt8 = 0x8
    /// Proposed fourth characteristic, for exchanging the token below.
    public static let tokenCharUuid = "0000C3A6-2F3B-4E8A-9A5E-0B0E6F1C2D3A"
}

/// A ranging token as it crosses the air.
///
/// The platforms disagree about what a token is. FiRa needs concrete session parameters and splits
/// **controller** (owns them) from **controlee** (joins); Apple's `NIDiscoveryToken` is an opaque
/// blob with no such split. All three shapes are carried and, critically, tagged — so an Apple
/// token arriving at a FiRa parser is *rejected* rather than read as a malformed FiRa token.
///
/// That rejection is the point: silently misparsing a foreign token yields a session reporting
/// distances derived from nonsense, which is worse than no ranging — it would show a payer a
/// confident "0.3 m" for a peer that could be anywhere.
public enum UwbToken: Equatable {
    case controller(address: [UInt8], channel: Int, preambleIndex: Int, sessionId: Int)
    case controlee(address: [UInt8])
    /// Apple `NIDiscoveryToken`, opaque by design — never parsed, only handed back to the platform.
    case opaque(blob: [UInt8])
}

/// `[magic 'Q'][version][kind][payload]`.
///
/// The magic byte and version exist so a token from the other platform, or a future revision, fails
/// to decode rather than decoding into something wrong.
public enum UwbTokenCodec {

    private static let magic = UInt8(ascii: "Q")
    private static let version: UInt8 = 1
    private static let kindController: UInt8 = 1
    private static let kindControlee: UInt8 = 2
    private static let kindOpaque: UInt8 = 3
    private static let header = 3
    private static let addressBytes = 2
    private static let controllerPayload = 2 + 1 + 1 + 4

    public static func encode(_ token: UwbToken) -> [UInt8] {
        switch token {
        case let .controller(address, channel, preambleIndex, sessionId):
            var out: [UInt8] = [magic, version, kindController]
            out += Array(address.prefix(addressBytes))
            out += [UInt8(channel & 0xFF), UInt8(preambleIndex & 0xFF)]
            out += (0..<4).reversed().map { UInt8((sessionId >> (8 * $0)) & 0xFF) }
            return out
        case let .controlee(address):
            return [magic, version, kindControlee] + Array(address.prefix(addressBytes))
        case let .opaque(blob):
            return [magic, version, kindOpaque] + blob
        }
    }

    /// Nil for anything that is not a token of this version — including the other platform's.
    public static func decode(_ bytes: [UInt8]) -> UwbToken? {
        guard bytes.count >= header, bytes[0] == magic, bytes[1] == version else { return nil }
        let payload = Array(bytes[header...])
        switch bytes[2] {
        case kindController:
            guard payload.count == controllerPayload else { return nil }
            let sessionId = payload[4...7].reduce(0) { ($0 << 8) | Int($1) }
            return .controller(
                address: Array(payload[0..<addressBytes]),
                channel: Int(payload[addressBytes]),
                preambleIndex: Int(payload[addressBytes + 1]),
                sessionId: sessionId
            )
        case kindControlee:
            guard payload.count == addressBytes else { return nil }
            return .controlee(address: payload)
        case kindOpaque:
            guard !payload.isEmpty else { return nil }
            return .opaque(blob: payload)
        default:
            return nil
        }
    }
}

/// What a ranging attempt concluded.
public enum ProximityOutcome: Equatable {
    /// UWB ranged and the peer is within `metres`.
    case ranged(metres: Double)
    /// No UWB session was possible. **Not an error** — the RSSI baseline already gated this tile,
    /// and the §6 confirmation authorises the payment either way.
    case downgraded(why: String)
}

/// Decides whether a ranging attempt is worth starting, before any radio is touched.
///
/// Every downgrade below is a session that would otherwise be attempted and never converge — the
/// cross-platform case most of all, where both devices have working UWB hardware and still cannot
/// range each other.
public enum ProximityPolicy {
    /// Returns a downgrade reason, or nil when ranging is worth attempting.
    public static func attempt(
        localSupportsUwb: Bool,
        peerAdvertisedUwb: Bool,
        peerToken: [UInt8]?,
        localIsAppleStack: Bool
    ) -> ProximityOutcome? {
        guard localSupportsUwb else { return .downgraded(why: "no-local-uwb") }
        guard peerAdvertisedUwb else { return .downgraded(why: "peer-not-uwb-capable") }
        guard let raw = peerToken else { return .downgraded(why: "no-peer-token") }
        guard let token = UwbTokenCodec.decode(raw) else { return .downgraded(why: "unreadable-peer-token") }

        let peerIsAppleStack: Bool
        if case .opaque = token { peerIsAppleStack = true } else { peerIsAppleStack = false }
        if peerIsAppleStack != localIsAppleStack {
            return .downgraded(why: "cross-platform-uwb-unsupported")
        }
        return nil
    }
}
