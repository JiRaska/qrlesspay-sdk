// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors.
import XCTest
@testable import QRlessPay

/// In-memory radio: one instance plays both roles, so the whole mint → advertise → discover →
/// fetch → verify path runs with no hardware. It models exactly one advertising payee.
private final class LoopbackTransport: QRlessPayTransport {
    private var beacon: [UInt8]?
    private var bundle: [UInt8]?
    private var onPeers: (([DiscoveredPeer]) -> Void)?
    var rssi = -40
    var fetchShouldFail = false

    private func peers() -> [DiscoveredPeer] {
        guard let beacon else { return [] }
        return [DiscoveredPeer(id: "peer-1", beacon: beacon, rssi: rssi)]
    }

    func startReceiving(beacon: [UInt8], bundle: [UInt8]) {
        self.beacon = beacon
        self.bundle = bundle
        onPeers?(peers())
    }
    func stopReceiving() { beacon = nil; bundle = nil; onPeers?([]) }
    func startDiscovery(onPeers: @escaping ([DiscoveredPeer]) -> Void) {
        self.onPeers = onPeers
        onPeers(peers())
    }
    func stopDiscovery() { onPeers = nil }
    func fetchBundle(peerId: String) async -> [UInt8]? { fetchShouldFail ? nil : bundle }
}

private let SPAYD = "SPD*1.0*ACC:CZ6508000000192000145399*AM:250.00*CC:CZK*RN:Jiri"

private func sequentialRandom(_ start: Int) -> (Int) -> [UInt8] {
    var c = start
    return { count in (0..<count).map { _ in defer { c += 1 }; return UInt8(c & 0xFF) } }
}

final class TransportTests: XCTestCase {

    private func controller(_ t: LoopbackTransport, seed: Int, now: UInt64) -> QRlessPayController {
        QRlessPayController(transport: t, random: sequentialRandom(seed), now: { now })
    }

    func testPayeeToPayerEndToEnd() async {
        let t = LoopbackTransport()
        controller(t, seed: 0, now: 1_000).startReceiving(firstName: "Jiří", spayd: SPAYD, amountMinor: 25_000)
        let payer = controller(t, seed: 200, now: 1_010)

        var tiles: [NearbyTile] = []
        payer.startDiscovery { tiles = $0 }
        XCTAssertEqual(tiles.count, 1)
        XCTAssertEqual(tiles.first?.firstName, "Jiri")     // diacritics folded on the air
        XCTAssertEqual(tiles.first?.amountMinor, 25_000)

        let outcome = await payer.resolve(tile: tiles[0])
        XCTAssertEqual(outcome, .ok(spayd: SPAYD))
    }

    /// The advert carries a display hint; the IBAN exists only in the signed bundle. This is the
    /// privacy property the whole profile is built around, so it is asserted rather than assumed.
    func testTheAdvertNeverCarriesTheIban() {
        let t = LoopbackTransport()
        controller(t, seed: 0, now: 1_000).startReceiving(firstName: "Jiří", spayd: SPAYD, amountMinor: 25_000)
        var tiles: [NearbyTile] = []
        controller(t, seed: 200, now: 1_010).startDiscovery { tiles = $0 }

        let onAir = String(decoding: tiles[0].beacon, as: UTF8.self)
        XCTAssertFalse(onAir.contains("CZ65"), "the advert must not carry the IBAN")
        XCTAssertFalse(onAir.contains("SPD"), "the advert must not carry the SPAYD")
    }

    func testTappingTheSameTileTwiceIsRejected() async {
        let t = LoopbackTransport()
        controller(t, seed: 0, now: 1_000).startReceiving(firstName: "Jiří", spayd: SPAYD, amountMinor: 25_000)
        let payer = controller(t, seed: 200, now: 1_010)
        var tiles: [NearbyTile] = []
        payer.startDiscovery { tiles = $0 }

        let first = await payer.resolve(tile: tiles[0])
        XCTAssertEqual(first, .ok(spayd: SPAYD))
        let second = await payer.resolve(tile: tiles[0])
        XCTAssertEqual(second, .rejected(reason: "replayed"))
    }

    func testAWeakPeerIsNeverOffered() {
        let t = LoopbackTransport()
        t.rssi = QP.rssiGateDbm - 1
        controller(t, seed: 0, now: 1_000).startReceiving(firstName: "Jiří", spayd: SPAYD, amountMinor: nil)
        var tiles: [NearbyTile] = []
        controller(t, seed: 200, now: 1_010).startDiscovery { tiles = $0 }
        XCTAssertTrue(tiles.isEmpty, "the §5 proximity gate is applied before any UI sees a tile")
    }

    func testAFailedFetchIsReportedNotSwallowed() async {
        let t = LoopbackTransport()
        controller(t, seed: 0, now: 1_000).startReceiving(firstName: "Jiří", spayd: SPAYD, amountMinor: nil)
        let payer = controller(t, seed: 200, now: 1_010)
        var tiles: [NearbyTile] = []
        payer.startDiscovery { tiles = $0 }
        t.fetchShouldFail = true
        let outcome = await payer.resolve(tile: tiles[0])
        XCTAssertEqual(outcome, .rejected(reason: "fetch-failed"))
    }

    /// Minting here, from the same seed the Kotlin reference used, reproduces **everything the two
    /// implementations must agree on**: session id, nonce, expiry, public key, the exact byte
    /// string under the signature, and the advert the payer filters on.
    ///
    /// The signature is deliberately *not* compared. CryptoKit signs randomly where Kotlin's
    /// library signs deterministically, so the bytes differ every time while both remain valid —
    /// see `Ed25519.SessionKey.sign`. Asserting signature equality here would fail for a reason
    /// that has nothing to do with interoperability, and asserting nothing would miss the parts
    /// that genuinely must match.
    func testMintReproducesEverythingBothImplementationsMustAgreeOn() {
        let t = LoopbackTransport()
        let minted = controller(t, seed: 0, now: 1_000).mint(firstName: "Jiří", spayd: SPAYD, amountMinor: 25_000)

        func hex(_ b: [UInt8]) -> String { b.map { String(format: "%02x", $0) }.joined() }

        // Vector ref-0 from conformance/vectors.json, produced by the Kotlin implementation.
        XCTAssertEqual(hex(minted.bundle.sid), "20212223")
        XCTAssertEqual(hex(minted.bundle.nonce), "2425262728292a2b2c2d2e2f30313233")
        XCTAssertEqual(minted.bundle.exp, 1_090)
        XCTAssertEqual(hex(minted.bundle.pk), "03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8")
        XCTAssertEqual(hex(BeaconCodec.encode(minted.advert)), "11044a6972692021222356470061a8")
        // Read from the vector file rather than transcribed into a literal: a 200-byte hex string
        // split across source lines is a typo waiting to be debugged as a protocol bug.
        let url = Foundation.Bundle.module.url(forResource: "vectors", withExtension: "json")!
        let raw = try! JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
        let refZero = (raw["vectors"] as! [[String: Any]]).first { $0["id"] as! String == "ref-0" }!
        XCTAssertEqual(
            hex(QRlessPayProtocol.signingBytes(
                version: 1, sid: minted.bundle.sid, nonce: minted.bundle.nonce,
                exp: minted.bundle.exp, pk: minted.bundle.pk, spayd: SPAYD
            )),
            refZero["signingBytesHex"] as! String
        )
    }

    /// Pins the randomness itself, so nobody re-adds a signature-equality assertion later and
    /// spends an afternoon on it: two mints of the same session differ in `sig` and nowhere else,
    /// and both verify.
    func testSigningIsRandomisedButBothSignaturesAreValid() {
        let t = LoopbackTransport()
        let a = controller(t, seed: 0, now: 1_000).mint(firstName: "Jiří", spayd: SPAYD, amountMinor: 25_000)
        let b = controller(t, seed: 0, now: 1_000).mint(firstName: "Jiří", spayd: SPAYD, amountMinor: 25_000)

        XCTAssertEqual(a.bundle.pk, b.bundle.pk)
        XCTAssertNotEqual(a.bundle.sig, b.bundle.sig, "CryptoKit signs randomly; if this ever passes, re-read Ed25519.sign")

        for minted in [a, b] {
            let guardOne = TtlReplayGuard(now: { 1_010 })
            XCTAssertEqual(
                QRlessPayProtocol.verify(advert: minted.advert, bundle: minted.bundle, nowEpochSec: 1_010, replayGuard: guardOne),
                .ok(spayd: SPAYD)
            )
        }
    }
}
