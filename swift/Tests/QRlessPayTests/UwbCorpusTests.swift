// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors.
import XCTest
@testable import QRlessPay

/// The UWB token corpus, shared with the Kotlin implementation.
///
/// The rejection cases carry the weight. A token from the other platform must **fail** to decode —
/// misreading one produces a ranging session whose distances derive from nonsense, which is worse
/// than no ranging: it shows a payer a confident "0.3 m" for a peer that could be anywhere.
final class UwbCorpusTests: XCTestCase {

    private func hexBytes(_ s: String) -> [UInt8] {
        var out: [UInt8] = []
        var i = s.startIndex
        while i < s.endIndex, let j = s.index(i, offsetBy: 2, limitedBy: s.endIndex) {
            out.append(UInt8(s[i..<j], radix: 16)!)
            i = j
        }
        return out
    }

    private func hexString(_ b: [UInt8]) -> String { b.map { String(format: "%02x", $0) }.joined() }

    private func corpus() throws -> [String: Any] {
        let url = try XCTUnwrap(Foundation.Bundle.module.url(forResource: "uwb-vectors", withExtension: "json"))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    func testValidTokensDecodeAndReEncodeToTheSameBytes() throws {
        for c in try XCTUnwrap(corpus()["valid"] as? [[String: Any]]) {
            let id = c["id"] as! String
            let token = try XCTUnwrap(UwbTokenCodec.decode(hexBytes(c["hex"] as! String)), id)
            switch (c["kind"] as! String, token) {
            case let ("controller", .controller(address, channel, preambleIndex, sessionId)):
                XCTAssertEqual(hexString(address), c["address"] as! String, id)
                XCTAssertEqual(channel, c["channel"] as! Int, id)
                XCTAssertEqual(preambleIndex, c["preambleIndex"] as! Int, id)
                XCTAssertEqual(sessionId, c["sessionId"] as! Int, id)
            case let ("controlee", .controlee(address)):
                XCTAssertEqual(hexString(address), c["address"] as! String, id)
            case let ("opaque", .opaque(blob)):
                XCTAssertEqual(hexString(blob), c["blob"] as! String, id)
            default:
                XCTFail("\(id): decoded to the wrong kind")
            }
            XCTAssertEqual(hexString(UwbTokenCodec.encode(token)), c["hex"] as! String, "\(id): re-encode")
        }
    }

    func testEveryRejectionCaseFailsToDecode() throws {
        for c in try XCTUnwrap(corpus()["rejected"] as? [[String: Any]]) {
            XCTAssertNil(
                UwbTokenCodec.decode(hexBytes(c["hex"] as! String)),
                "\(c["id"] as! String): \(c["why"] as! String)"
            )
        }
    }

    func testThePolicyDowngradesExactlyWhereItShould() throws {
        for c in try XCTUnwrap(corpus()["policy"] as? [[String: Any]]) {
            let id = c["id"] as! String
            let token = (c["peerTokenHex"] as? String).map { hexBytes($0) }
            let outcome = ProximityPolicy.attempt(
                localSupportsUwb: c["localSupportsUwb"] as! Bool,
                peerAdvertisedUwb: c["peerAdvertisedUwb"] as! Bool,
                peerToken: token,
                localIsAppleStack: c["localIsAppleStack"] as! Bool
            )
            if c["expect"] as! String == "attempt" {
                XCTAssertNil(outcome, "\(id): ranging is worth attempting here")
            } else {
                XCTAssertEqual(outcome, .downgraded(why: c["expect"] as! String), id)
            }
        }
    }
}
