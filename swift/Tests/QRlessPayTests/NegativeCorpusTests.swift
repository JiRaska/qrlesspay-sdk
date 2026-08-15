// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors.
import XCTest
@testable import QRlessPay

/// Runs the same `conformance/negative-vectors.json` the Kotlin implementation runs.
///
/// This is where conformance is actually decided. Two implementations agree on the happy path by
/// construction — both were written from the same spec by someone who wanted them to work — and
/// diverge on which malformed inputs they notice. A corpus of valid bundles proves neither is
/// broken; only a corpus of invalid ones proves they are the *same*.
final class NegativeCorpusTests: XCTestCase {

    private func hexBytes(_ s: String) -> [UInt8] {
        var out: [UInt8] = []
        var i = s.startIndex
        while i < s.endIndex, let j = s.index(i, offsetBy: 2, limitedBy: s.endIndex) {
            out.append(UInt8(s[i..<j], radix: 16)!)
            i = j
        }
        return out
    }

    private func corpus() throws -> [String: Any] {
        let url = try XCTUnwrap(Foundation.Bundle.module.url(forResource: "negative-vectors", withExtension: "json"))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    func testEveryStructuralCaseIsRefusedByTheDecoder() throws {
        let cases = try XCTUnwrap(corpus()["structural"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(cases.count, 10, "expected the full structural corpus")
        for c in cases {
            let id = c["id"] as! String
            XCTAssertNil(
                QRlessPayProtocol.decode(hexBytes(c["bundleCborHex"] as! String)),
                "\(id): \(c["why"] as! String)"
            )
        }
    }

    func testEverySemanticCaseIsRejectedForItsOwnReason() throws {
        let cases = try XCTUnwrap(corpus()["semantic"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(cases.count, 9, "expected the full semantic corpus")
        for c in cases {
            let id = c["id"] as! String
            let now = UInt64(c["nowEpochSec"] as! Int)
            let advert = try XCTUnwrap(BeaconCodec.decode(hexBytes(c["advertHex"] as! String)), "\(id): advert")
            let bundle = try XCTUnwrap(
                QRlessPayProtocol.decode(hexBytes(c["bundleCborHex"] as! String)),
                "\(id): bundle did not decode, but this case is meant to reach verification"
            )
            let result = QRlessPayProtocol.verify(
                advert: advert, bundle: bundle, nowEpochSec: now, replayGuard: TtlReplayGuard(now: { now })
            )
            // The reason matters as much as the rejection: two implementations that refuse the same
            // payload for different reasons disagree the moment either acts on the reason.
            XCTAssertEqual(result, .rejected(reason: c["reason"] as! String), "\(id): \(c["why"] as! String)")
        }
    }

    func testTheReplayCaseIsAcceptedOnceThenRejected() throws {
        let c = try XCTUnwrap(corpus()["replay"] as? [String: Any])
        let now = UInt64(c["nowEpochSec"] as! Int)
        let advert = try XCTUnwrap(BeaconCodec.decode(hexBytes(c["advertHex"] as! String)))
        let bundle = try XCTUnwrap(QRlessPayProtocol.decode(hexBytes(c["bundleCborHex"] as! String)))
        let guardOne = TtlReplayGuard(now: { now })

        guard case .ok = QRlessPayProtocol.verify(advert: advert, bundle: bundle, nowEpochSec: now, replayGuard: guardOne) else {
            return XCTFail("first presentation must be accepted")
        }
        XCTAssertEqual(
            QRlessPayProtocol.verify(advert: advert, bundle: bundle, nowEpochSec: now, replayGuard: guardOne),
            .rejected(reason: c["reason"] as! String)
        )
    }
}
