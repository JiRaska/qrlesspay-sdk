// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors.
import XCTest
import CryptoKit
@testable import QRlessPay

private struct VectorFile: Decodable {
    struct Advert: Decodable {
        let beaconHex: String
        let name: String
        let sidHex: String
        let keyHashHex: String
        let amountMinor: Int?
    }
    struct Bundle: Decodable {
        let version: Int
        let sidHex: String
        let spayd: String
        let nonceHex: String
        let exp: UInt64
        let pkHex: String
        let sigHex: String
    }
    struct Vector: Decodable {
        let id: String
        let advert: Advert
        let bundle: Bundle
        let signingBytesHex: String
        let nowEpochSec: UInt64
        let referenceCborBytes: Int
    }
    let vectors: [Vector]
}

private func hex(_ s: String) -> [UInt8] {
    var out: [UInt8] = []
    var i = s.startIndex
    while i < s.endIndex, let j = s.index(i, offsetBy: 2, limitedBy: s.endIndex) {
        out.append(UInt8(s[i..<j], radix: 16)!)
        i = j
    }
    return out
}

private func hexString(_ b: [UInt8]) -> String { b.map { String(format: "%02x", $0) }.joined() }

/// These are the checks that matter: they run this implementation against bytes produced by a
/// *different* one. Anything this file asserts about round-tripping our own output is a weaker
/// claim and is kept separate, in EncodingTests.
final class ConformanceTests: XCTestCase {

    private func loadVectors() throws -> [VectorFile.Vector] {
        let url = try XCTUnwrap(Foundation.Bundle.module.url(forResource: "vectors", withExtension: "json"))
        return try JSONDecoder().decode(VectorFile.self, from: Data(contentsOf: url)).vectors
    }

    /// The advert is byte-identical across implementations, so this is a real interop check.
    func testBeaconDecodesToTheReferenceFields() throws {
        for v in try loadVectors() {
            let decoded = try XCTUnwrap(BeaconCodec.decode(hex(v.advert.beaconHex)), v.id)
            XCTAssertEqual(decoded.version, 1, v.id)
            XCTAssertEqual(decoded.name, v.advert.name, v.id)
            XCTAssertEqual(hexString(decoded.sid), v.advert.sidHex, v.id)
            XCTAssertEqual(hexString(decoded.keyHash), v.advert.keyHashHex, v.id)
            XCTAssertEqual(decoded.amountMinor, v.advert.amountMinor, v.id)
        }
    }

    func testBeaconReEncodesToTheReferenceBytes() throws {
        for v in try loadVectors() {
            let decoded = try XCTUnwrap(BeaconCodec.decode(hex(v.advert.beaconHex)), v.id)
            XCTAssertEqual(hexString(BeaconCodec.encode(decoded)), v.advert.beaconHex, v.id)
        }
    }

    /// The bytes under the signature must agree exactly, or two implementations can both "verify"
    /// and disagree about what they verified.
    func testSigningBytesMatchTheReference() throws {
        for v in try loadVectors() {
            let produced = QRlessPayProtocol.signingBytes(
                version: UInt8(v.bundle.version),
                sid: hex(v.bundle.sidHex),
                nonce: hex(v.bundle.nonceHex),
                exp: v.bundle.exp,
                pk: hex(v.bundle.pkHex),
                spayd: v.bundle.spayd
            )
            XCTAssertEqual(hexString(produced), v.signingBytesHex, v.id)
        }
    }

    /// CryptoKit verifying a signature produced by a completely different Ed25519 library, over
    /// bytes this implementation reconstructed itself. If any of the three disagreed, this fails.
    func testCryptoKitVerifiesTheReferenceSignatures() throws {
        for v in try loadVectors() {
            let key = try Curve25519.Signing.PublicKey(rawRepresentation: Data(hex(v.bundle.pkHex)))
            XCTAssertTrue(
                key.isValidSignature(Data(hex(v.bundle.sigHex)), for: Data(hex(v.signingBytesHex))),
                "\(v.id): reference signature must verify under CryptoKit"
            )
        }
    }

    func testKeyHashBindsTheAdvertToTheBundleKey() throws {
        for v in try loadVectors() {
            let digest = SHA256.hash(data: Data(hex(v.bundle.pkHex)))
            XCTAssertEqual(hexString(Array(digest.prefix(2))), v.advert.keyHashHex, v.id)
        }
    }

    /// End to end over the reference material, through the real verification order.
    func testFullVerificationAcceptsTheReferenceBundles() throws {
        for v in try loadVectors() {
            let advert = try XCTUnwrap(BeaconCodec.decode(hex(v.advert.beaconHex)), v.id)
            let bundle = QRlessPay.Bundle(
                version: UInt8(v.bundle.version),
                sid: hex(v.bundle.sidHex),
                spayd: v.bundle.spayd,
                nonce: hex(v.bundle.nonceHex),
                exp: v.bundle.exp,
                pk: hex(v.bundle.pkHex),
                sig: hex(v.bundle.sigHex)
            )
            let guardOne = TtlReplayGuard(now: { v.nowEpochSec })
            XCTAssertEqual(
                QRlessPayProtocol.verify(advert: advert, bundle: bundle, nowEpochSec: v.nowEpochSec, replayGuard: guardOne),
                .ok(spayd: v.bundle.spayd),
                v.id
            )
            // Same guard, same bundle, second time.
            XCTAssertEqual(
                QRlessPayProtocol.verify(advert: advert, bundle: bundle, nowEpochSec: v.nowEpochSec, replayGuard: guardOne),
                .rejected(reason: "replayed"),
                v.id
            )
        }
    }

    /// Two findings this suite made measurable, asserted rather than described.
    ///
    /// 1. The reference implementation's CBOR is ~1.6x the spec-conformant encoding, because it
    ///    serialises property *names* as text keys and byte arrays as arrays of integers.
    /// 2. The spec's own "~140–180 B" estimate is not reachable either: with a realistic 61-byte
    ///    SPAYD, `pk` + `sig` alone are 96 bytes and a canonical encoding lands at ~197 B. The
    ///    number in the spec was never measured against a real payload.
    ///
    /// The bound below is the measured floor plus a little room, not a target anyone chose. If a
    /// future change pushes past it, that is a size regression worth a decision — GATT reads are
    /// MTU-bound and this payload has to fit one.
    func testEncodingSizesAreWhatWeMeasuredNotWhatTheSpecClaims() throws {
        for v in try loadVectors() {
            let spec = QRlessPayProtocol.encode(QRlessPay.Bundle(
                version: UInt8(v.bundle.version), sid: hex(v.bundle.sidHex), spayd: v.bundle.spayd,
                nonce: hex(v.bundle.nonceHex), exp: v.bundle.exp, pk: hex(v.bundle.pkHex), sig: hex(v.bundle.sigHex)
            )).count

            XCTAssertLessThanOrEqual(spec, 210, "\(v.id): canonical encoding grew to \(spec) B")
            XCTAssertGreaterThan(v.referenceCborBytes, spec + 100,
                                 "\(v.id): reference \(v.referenceCborBytes) B vs canonical \(spec) B")
            XCTAssertGreaterThan(v.referenceCborBytes, 180,
                                 "\(v.id): reference encoding is outside the spec's stated budget")
        }
    }

    /// Round-tripping our own encoder is a weaker claim than the interop checks above, so it says
    /// so — it catches an encoder/decoder pair drifting together, nothing more.
    func testCanonicalEncodingRoundTripsWithinThisImplementation() throws {
        for v in try loadVectors() {
            let bundle = QRlessPay.Bundle(
                version: UInt8(v.bundle.version), sid: hex(v.bundle.sidHex), spayd: v.bundle.spayd,
                nonce: hex(v.bundle.nonceHex), exp: v.bundle.exp, pk: hex(v.bundle.pkHex), sig: hex(v.bundle.sigHex)
            )
            let decoded = try XCTUnwrap(QRlessPayProtocol.decode(QRlessPayProtocol.encode(bundle)), v.id)
            XCTAssertEqual(decoded, bundle, v.id)
        }
    }

    /// The decoder must reject the reference encoding rather than half-read it: text keys and
    /// indefinite lengths are not this profile, and silently tolerating them is how two dialects
    /// become permanent.
    func testCanonicalDecoderRejectsTheReferenceEncoding() throws {
        let url = try XCTUnwrap(Foundation.Bundle.module.url(forResource: "vectors", withExtension: "json"))
        let raw = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
        let vectors = raw["vectors"] as! [[String: Any]]
        for v in vectors {
            let refHex = v["referenceCborHex"] as! String
            XCTAssertNil(QRlessPayProtocol.decode(hex(refHex)), v["id"] as! String)
        }
    }
}
