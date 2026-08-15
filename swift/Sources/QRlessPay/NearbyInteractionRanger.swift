// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors.
//
// NOT built by `swift test` on macOS — NearbyInteraction's peer-ranging API is iOS-only, so the
// guard below excludes this file off-device. It is still type-checked, against the real iOS SDK:
//
//   xcrun swiftc -typecheck -sdk "$(xcrun --sdk iphoneos --show-sdk-path)" \
//     -target arm64-apple-ios16.0 Sources/QRlessPay/*.swift
//
// which catches the whole class of errors a file like this actually suffers from — wrong delegate
// signatures, renamed initialisers, availability mistakes. What it does not catch is behaviour on a
// radio, and nothing short of two devices will.
#if canImport(NearbyInteraction) && os(iOS)
import Foundation
import NearbyInteraction

/// iOS UWB ranging over NearbyInteraction — the optional enhancement of spec §5.
///
/// **Apple's model is symmetric where Android's is not.** There is no controller/controlee split
/// here: each side publishes an opaque `NIDiscoveryToken`, hands the peer's back to the framework,
/// and both range. That asymmetry between the platforms is why the wire token is tagged by kind —
/// an opaque Apple token must be *rejected* by a FiRa parser, not read as malformed parameters.
///
/// Everything is best-effort. No hardware, an older OS, a declined permission, a cross-platform
/// peer or a session that never converges all end as `.downgraded`, and the payer carries on with
/// the RSSI baseline and the §6 confirmation.
@available(iOS 16.0, *)
public final class NearbyInteractionRanger: NSObject {

    private var session: NISession?
    private var continuation: CheckedContinuation<ProximityOutcome, Never>?
    private var timeoutTask: Task<Void, Never>?

    /// Whether this device can do precise distance measurement. False on the SE and on anything
    /// before the iPhone 11, which is a large share of devices in circulation.
    public static var isSupported: Bool {
        NISession.deviceCapabilities.supportsPreciseDistanceMeasurement
    }

    /// This device's token, to be published for the peer to read.
    public func localToken() -> [UInt8]? {
        let session = self.session ?? NISession()
        self.session = session
        guard let token = session.discoveryToken,
              let data = try? NSKeyedArchiver.archivedData(withRootObject: token, requiringSecureCoding: true)
        else { return nil }
        return UwbTokenCodec.encode(.opaque(blob: Array(data)))
    }

    /// Ranges against `peerToken` and resolves with the first distance, or a downgrade.
    public func range(peerToken: [UInt8], timeout: TimeInterval = 6) async -> ProximityOutcome {
        guard Self.isSupported else { return .downgraded(why: "no-local-uwb") }
        guard let decoded = UwbTokenCodec.decode(peerToken) else {
            return .downgraded(why: "unreadable-peer-token")
        }
        guard case let .opaque(blob) = decoded else {
            // A FiRa token where an Apple one was expected. Detected rather than attempted: a
            // session built from a foreign token never converges, and waiting for it is worse than
            // falling back immediately.
            return .downgraded(why: "cross-platform-uwb-unsupported")
        }
        guard let token = try? NSKeyedUnarchiver.unarchivedObject(
            ofClass: NIDiscoveryToken.self, from: Data(blob)
        ) else {
            return .downgraded(why: "unreadable-peer-token")
        }

        let session = self.session ?? NISession()
        self.session = session
        session.delegate = self

        return await withCheckedContinuation { continuation in
            self.continuation = continuation
            self.timeoutTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                self?.finish(.downgraded(why: "ranging-timed-out"))
            }
            session.run(NINearbyPeerConfiguration(peerToken: token))
        }
    }

    public func stop() {
        session?.invalidate()
        session = nil
        finish(.downgraded(why: "cancelled"))
    }

    /// One exit path for every outcome, so a delegate callback arriving after the timeout cannot
    /// resume the continuation a second time.
    private func finish(_ outcome: ProximityOutcome) {
        timeoutTask?.cancel()
        timeoutTask = nil
        guard let continuation else { return }
        self.continuation = nil
        continuation.resume(returning: outcome)
    }
}

@available(iOS 16.0, *)
extension NearbyInteractionRanger: NISessionDelegate {
    public func session(_ session: NISession, didUpdate nearbyObjects: [NINearbyObject]) {
        guard let distance = nearbyObjects.first?.distance else { return }
        finish(.ranged(metres: Double(distance)))
    }

    public func session(_ session: NISession, didRemove nearbyObjects: [NINearbyObject], reason: NINearbyObject.RemovalReason) {
        finish(.downgraded(why: "peer-left"))
    }

    public func session(_ session: NISession, didInvalidateWith error: Error) {
        finish(.downgraded(why: "ranging-failed"))
    }

    public func sessionWasSuspended(_ session: NISession) {
        finish(.downgraded(why: "suspended"))
    }
}
#endif
