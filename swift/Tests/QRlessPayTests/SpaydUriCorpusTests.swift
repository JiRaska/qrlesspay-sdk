// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors.
import XCTest
@testable import QRlessPay

/// The SPAYD-URI corpus, as `kmp/.../SpaydUriCorpusTest.kt` runs it. Both cores asserting the same
/// URIs *and the same refusal reasons* is what keeps the camera path one profile: a bank whose
/// scanner accepts a malformed URI that the next bank's refuses is a support ticket with two
/// innocent parties.
final class SpaydUriCorpusTests: XCTestCase {

    private func vectors() throws -> [String: Any] {
        let url = try XCTUnwrap(Foundation.Bundle.module.url(forResource: "spayd-uri-vectors", withExtension: "json"))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    func testPositiveVectorsWrapToTheCommittedUriAndBack() throws {
        let cases = try XCTUnwrap(vectors()["positive"] as? [[String: Any]])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as! String
            let spayd = c["spayd"] as! String
            let code = c["bankCode"] as? String
            let uri = c["uri"] as! String
            XCTAssertEqual(try SpaydUri.wrap(spayd, bankCode: code), uri, name)
            guard case .ok(let u) = SpaydUri.parse(uri) else { return XCTFail("\(name): refused") }
            XCTAssertEqual(u.spayd, spayd, name)
            XCTAssertEqual(u.target.bankCode, code, name)
            XCTAssertEqual(SpaydUri.unwrap(uri), spayd, name)
        }
    }

    func testToleratedFormsUnwrapToTheSameDescriptor() throws {
        let cases = try XCTUnwrap(vectors()["tolerated"] as? [[String: Any]])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            XCTAssertEqual(SpaydUri.unwrap(c["uri"] as! String), c["expectedSpayd"] as? String, c["name"] as! String)
        }
    }

    func testNegativeVectorsAreRefusedForTheStatedReason() throws {
        let cases = try XCTUnwrap(vectors()["negative"] as? [[String: Any]])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as! String
            guard case .rejected(let reason) = SpaydUri.parse(c["uri"] as! String) else { return XCTFail("\(name): accepted") }
            XCTAssertEqual(reason.rawValue, c["reason"] as! String, name)
            // `unwrap` deliberately accepts a bare descriptor that `parse` refuses as not-a-URI.
            if !(c["uri"] as! String).hasPrefix("SPD*") { XCTAssertNil(SpaydUri.unwrap(c["uri"] as! String), name) }
        }
    }

    func testABareDescriptorPassesThroughUnwrapUntouched() {
        let spayd = "SPD*1.0*ACC:CZ6508000000192000145399*AM:250.00*CC:CZK*RN:Jiri"
        XCTAssertEqual(SpaydUri.unwrap(spayd), spayd)
        XCTAssertEqual(SpaydUri.unwrap("SPD*1.0*AM:250.00"), nil)
    }

    func testWrapRefusesWhatEveryReceiverWouldReject() {
        XCTAssertThrowsError(try SpaydUri.wrap("hello"))
        XCTAssertThrowsError(try SpaydUri.wrap("SPD*1.0*ACC:CZ6508000000192000145399", bankCode: "80"))
        XCTAssertNil(SpaydUri.relayScheme(bankCode: "08OO"))
        XCTAssertEqual(SpaydUri.relayScheme(bankCode: "0800"), "spayd-0800")
    }
}
