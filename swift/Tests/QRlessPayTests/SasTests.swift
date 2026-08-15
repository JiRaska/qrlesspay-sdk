// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors.
import XCTest
import CryptoKit
@testable import QRlessPay

/// SAS derivation against vectors produced by the Kotlin implementation.
///
/// The claim being checked is narrow and important: **two implementations must show a payer the
/// same digits for an honest link.** If they disagree, the mechanism inverts — two people holding
/// genuinely un-intercepted phones read out different numbers and abandon a legitimate payment,
/// which trains everyone to ignore the check.
final class SasTests: XCTestCase {

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

    private func vectors() throws -> [String: Any] {
        let url = try XCTUnwrap(Foundation.Bundle.module.url(forResource: "sas-vectors", withExtension: "json"))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    func testKeyAgreementMatchesTheKotlinImplementation() throws {
        let v = try vectors()
        let payee = try Sas.KeyPair(privateKeyBytes: hexBytes(v["payeeScalarHex"] as! String))
        let payer = try Sas.KeyPair(privateKeyBytes: hexBytes(v["payerScalarHex"] as! String))

        // CryptoKit clamps the scalar per RFC 7748, as any conforming X25519 does; the public keys
        // must therefore match the ones Kotlin derived from the same raw bytes.
        XCTAssertEqual(hexString(payee.publicKey), v["payeeEphemeralPkHex"] as! String)
        XCTAssertEqual(hexString(payer.publicKey), v["payerEphemeralPkHex"] as! String)
    }

    func testTranscriptMatchesTheKotlinImplementation() throws {
        let v = try vectors()
        let produced = Sas.transcript(
            sid: hexBytes(v["sidHex"] as! String),
            payeeEd25519Pk: hexBytes(v["payeeEd25519PkHex"] as! String),
            payeeEphemeralPk: hexBytes(v["payeeEphemeralPkHex"] as! String),
            payerEphemeralPk: hexBytes(v["payerEphemeralPkHex"] as! String)
        )
        XCTAssertEqual(hexString(produced), v["transcriptHex"] as! String)
    }

    func testCodesMatchTheKotlinImplementationAtEveryWidth() throws {
        let v = try vectors()
        let shared = hexBytes(v["sharedSecretHex"] as! String)
        let transcript = hexBytes(v["transcriptHex"] as! String)
        for c in v["codes"] as! [[String: Any]] {
            let digits = c["digits"] as! Int
            XCTAssertEqual(
                Sas.code(sharedSecret: shared, transcript: transcript, digits: digits),
                c["code"] as! String,
                "digits=\(digits)"
            )
        }
    }

    /// End to end through the public API: both sides agree, independently, on the same code.
    func testBothSidesDeriveTheSameCodeFromTheirOwnKey() throws {
        let v = try vectors()
        let payee = try Sas.KeyPair(privateKeyBytes: hexBytes(v["payeeScalarHex"] as! String))
        let payer = try Sas.KeyPair(privateKeyBytes: hexBytes(v["payerScalarHex"] as! String))
        let sid = hexBytes(v["sidHex"] as! String)
        let ed = hexBytes(v["payeeEd25519PkHex"] as! String)

        let fromPayee = try Sas.codeFor(
            localKeyPair: payee, peerEphemeralPk: payer.publicKey,
            sid: sid, payeeEd25519Pk: ed, payeeEphemeralPk: payee.publicKey, payerEphemeralPk: payer.publicKey
        )
        let fromPayer = try Sas.codeFor(
            localKeyPair: payer, peerEphemeralPk: payee.publicKey,
            sid: sid, payeeEd25519Pk: ed, payeeEphemeralPk: payee.publicKey, payerEphemeralPk: payer.publicKey
        )
        XCTAssertEqual(fromPayee, fromPayer)
        XCTAssertEqual(fromPayee, (v["codes"] as! [[String: Any]]).first { $0["digits"] as! Int == 6 }!["code"] as! String)
    }

    /// The mechanism, asserted rather than described: a man in the middle who agrees separately
    /// with each side produces two different codes, which is exactly what the humans notice.
    func testAManInTheMiddleProducesDifferentCodesOnEachSide() throws {
        let v = try vectors()
        let payee = try Sas.KeyPair(privateKeyBytes: hexBytes(v["payeeScalarHex"] as! String))
        let payer = try Sas.KeyPair(privateKeyBytes: hexBytes(v["payerScalarHex"] as! String))
        let attacker = Sas.KeyPair()
        let sid = hexBytes(v["sidHex"] as! String)
        let ed = hexBytes(v["payeeEd25519PkHex"] as! String)

        // Payee believes it is talking to the attacker's key; payer likewise. Each side's transcript
        // carries the key it actually saw, so neither matches the other.
        let payeeSide = try Sas.codeFor(
            localKeyPair: payee, peerEphemeralPk: attacker.publicKey,
            sid: sid, payeeEd25519Pk: ed, payeeEphemeralPk: payee.publicKey, payerEphemeralPk: attacker.publicKey
        )
        let payerSide = try Sas.codeFor(
            localKeyPair: payer, peerEphemeralPk: attacker.publicKey,
            sid: sid, payeeEd25519Pk: ed, payeeEphemeralPk: attacker.publicKey, payerEphemeralPk: payer.publicKey
        )
        XCTAssertNotEqual(payeeSide, payerSide, "an intercepted link must not produce matching codes")
    }

    /// The transcript is what makes the code attest to *this payment*: change the bundle it is
    /// bound to and the digits change, so an honest exchange cannot be spliced onto another bundle.
    func testTheCodeIsBoundToTheBundleNotJustTheLink() throws {
        let v = try vectors()
        let shared = hexBytes(v["sharedSecretHex"] as! String)
        let sid = hexBytes(v["sidHex"] as! String)
        let ed = hexBytes(v["payeeEd25519PkHex"] as! String)
        let payeePk = hexBytes(v["payeeEphemeralPkHex"] as! String)
        let payerPk = hexBytes(v["payerEphemeralPkHex"] as! String)

        let real = Sas.code(
            sharedSecret: shared,
            transcript: Sas.transcript(sid: sid, payeeEd25519Pk: ed, payeeEphemeralPk: payeePk, payerEphemeralPk: payerPk)
        )
        var otherEd = ed
        otherEd[0] ^= 0x01
        let spliced = Sas.code(
            sharedSecret: shared,
            transcript: Sas.transcript(sid: sid, payeeEd25519Pk: otherEd, payeeEphemeralPk: payeePk, payerEphemeralPk: payerPk)
        )
        XCTAssertNotEqual(real, spliced)
    }
}
