// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors.
import Foundation

/// A discovered peer, before its advert has been decoded.
public struct DiscoveredPeer: Equatable {
    public let id: String
    public let beacon: [UInt8]
    public let rssi: Int

    public init(id: String, beacon: [UInt8], rssi: Int) {
        self.id = id
        self.beacon = beacon
        self.rssi = rssi
    }
}

/// What the payer's "nearby" list shows for a discovered payee. `firstName` and `amountMinor` are
/// display hints from the unauthenticated advert — the authoritative values come from the signed
/// bundle, after `resolve` (spec §2).
public struct NearbyTile: Equatable {
    public let peerId: String
    public let firstName: String
    public let amountMinor: Int?
    public let rssi: Int
    let beacon: [UInt8]
}

/// The radio, abstracted so the protocol layer can be driven by a fake in tests. Everything above
/// this line is pure and portable; everything below it is CoreBluetooth.
public protocol QRlessPayTransport: AnyObject {
    /// Advertise `beacon` and serve `bundle` to whoever reads the characteristic.
    func startReceiving(beacon: [UInt8], bundle: [UInt8])
    func stopReceiving()

    /// Scan; `onPeers` is called with the current set whenever it changes.
    func startDiscovery(onPeers: @escaping ([DiscoveredPeer]) -> Void)
    func stopDiscovery()

    /// Connect to `peerId`, read the bundle characteristic, disconnect. Nil on any failure —
    /// the caller cannot act on the difference and a typed error here would only invite it to try.
    func fetchBundle(peerId: String) async -> [UInt8]?
}

/// Orchestrates the two roles over a [QRlessPayTransport]. No money moves here: `resolve` yields a
/// verified SPAYD for the host app to turn into its own confirmation screen and SCA.
public final class QRlessPayController {
    private let transport: QRlessPayTransport
    private let random: (Int) -> [UInt8]
    private let now: () -> UInt64
    private let replayGuard: ReplayGuard

    public init(
        transport: QRlessPayTransport,
        random: @escaping (Int) -> [UInt8] = QRlessPayController.secureRandom,
        now: @escaping () -> UInt64 = { UInt64(Date().timeIntervalSince1970) },
        replayGuard: ReplayGuard? = nil
    ) {
        self.transport = transport
        self.random = random
        self.now = now
        // Per controller, so single-use tracking spans the taps of one payer screen — which is the
        // window a double payment happens in — and is discarded with it.
        self.replayGuard = replayGuard ?? TtlReplayGuard(now: now)
    }

    // MARK: Payee

    public func startReceiving(firstName: String, spayd: String, amountMinor: Int? = nil) {
        let minted = mint(firstName: firstName, spayd: spayd, amountMinor: amountMinor)
        transport.startReceiving(beacon: BeaconCodec.encode(minted.advert), bundle: QRlessPayProtocol.encode(minted.bundle))
    }

    public func stopReceiving() { transport.stopReceiving() }

    // MARK: Payer

    /// Surfaces discovered payees as tiles, strongest signal first, with anything below
    /// `QP.rssiGateDbm` withheld. The §5 proximity baseline is applied here rather than in a
    /// screen, so no UI can present a tile that failed it by forgetting to filter.
    public func startDiscovery(onTiles: @escaping ([NearbyTile]) -> Void) {
        transport.startDiscovery { peers in
            let tiles = peers.compactMap { p -> NearbyTile? in
                guard let b = BeaconCodec.decode(p.beacon) else { return nil }
                return NearbyTile(peerId: p.id, firstName: b.name, amountMinor: b.amountMinor, rssi: p.rssi, beacon: p.beacon)
            }
            .filter { $0.rssi >= QP.rssiGateDbm }
            .sorted { $0.rssi > $1.rssi }
            onTiles(tiles)
        }
    }

    public func stopDiscovery() { transport.stopDiscovery() }

    /// Fetches and verifies the selected payee's bundle. The proximity gate above and the host
    /// app's mandatory confirmation + SCA sit on either side of this.
    public func resolve(tile: NearbyTile) async -> VerifyResult {
        guard let advert = BeaconCodec.decode(tile.beacon) else { return .rejected(reason: "bad-advert") }
        guard let raw = await transport.fetchBundle(peerId: tile.peerId) else { return .rejected(reason: "fetch-failed") }
        guard let bundle = QRlessPayProtocol.decode(raw) else { return .rejected(reason: "bad-bundle") }
        return QRlessPayProtocol.verify(advert: advert, bundle: bundle, nowEpochSec: now(), replayGuard: replayGuard)
    }

    // MARK: Minting

    struct Minted { let advert: BeaconPayload; let bundle: Bundle }

    func mint(firstName: String, spayd: String, amountMinor: Int?, ttlSec: UInt64 = QP.maxTtlSeconds) -> Minted {
        let seed = random(32)
        let sid = random(QP.sidBytes)
        let nonce = random(QP.nonceBytes)
        let key = Ed25519.keyFromSeed(seed)
        let exp = now() + min(max(ttlSec, 1), QP.maxTtlSeconds)
        let signed = QRlessPayProtocol.signingBytes(version: QP.version, sid: sid, nonce: nonce, exp: exp, pk: key.publicKey, spayd: spayd)
        let sig = key.sign(signed)
        let bundle = Bundle(version: QP.version, sid: sid, spayd: spayd, nonce: nonce, exp: exp, pk: key.publicKey, sig: sig)
        let advert = BeaconPayload(version: QP.version, name: firstName, sid: sid, keyHash: Ed25519.keyHash(key.publicKey), amountMinor: amountMinor)
        return Minted(advert: advert, bundle: bundle)
    }

    public static func secureRandom(_ count: Int) -> [UInt8] {
        var bytes = [UInt8](repeating: 0, count: count)
        let rc = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        precondition(rc == errSecSuccess, "SecRandomCopyBytes failed: \(rc)")
        return bytes
    }
}
