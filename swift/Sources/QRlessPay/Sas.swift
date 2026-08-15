// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors.
import Foundation
import CryptoKit

/// Short Authentication String — the numeric code two people compare out loud (wire spec §4).
///
/// It defeats an **active MITM on the GATT link** without any PKI: an attacker who terminates the
/// link and runs a separate key agreement with each side ends up with two different shared secrets,
/// so the two humans read out different numbers and stop. That is the whole mechanism, and there is
/// nothing to check against a directory — which is what makes it usable in a protocol with no
/// backend (§11).
///
/// **§4 is prose and this fills the gaps it leaves**, mirroring `Sas.kt`: it names X25519, HKDF and
/// the label `QP-SAS` and stops before the parts two implementations must get identical — what goes
/// into the transcript, how many digits, and how digits are drawn from key material. A **v1.1
/// proposal** until the spec adopts or replaces it.
///
/// ### What the code attests to
///
/// A SAS over the raw shared secret alone would prove only that *some* key agreement was not
/// intercepted. The payer needs more: the code must attest to **the payment they are about to
/// make**, or an attacker can run an honest exchange on one session and splice it onto a different
/// bundle. So the transcript binds the session id and the payee's Ed25519 key from the bundle
/// alongside both ephemeral public keys.
public enum Sas {

    /// §10 proposes six digits over §4's four: ~20 bits of comparison entropy rather than ~13.
    public static let defaultDigits = 6

    /// The `sas` characteristic of §1 — read/write, ephemeral-DH public keys.
    public static let charSasUuid = "0000C3A7-2F3B-4E8A-9A5E-0B0E6F1C2D3A"

    private static let label = Data("QP-SAS-v1".utf8)
    private static let okmBytes = 8

    /// An ephemeral X25519 keypair, valid for exactly one request screen.
    public struct KeyPair {
        fileprivate let privateKey: Curve25519.KeyAgreement.PrivateKey
        public let publicKey: [UInt8]

        public init() {
            let key = Curve25519.KeyAgreement.PrivateKey()
            self.privateKey = key
            self.publicKey = Array(key.publicKey.rawRepresentation)
        }

        /// Deterministic construction, for conformance vectors only. Ephemeral is not a style
        /// choice in production — reusing a keypair would let an attacker who recorded an earlier
        /// exchange replay its code — so this takes raw material rather than a seed to derive from.
        public init(privateKeyBytes: [UInt8]) throws {
            let key = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(privateKeyBytes))
            self.privateKey = key
            self.publicKey = Array(key.publicKey.rawRepresentation)
        }
    }

    /// The bytes both sides hash.
    public static func transcript(
        sid: [UInt8],
        payeeEd25519Pk: [UInt8],
        payeeEphemeralPk: [UInt8],
        payerEphemeralPk: [UInt8]
    ) -> [UInt8] {
        [QP.version] + sid + payeeEd25519Pk + payeeEphemeralPk + payerEphemeralPk
    }

    /// The code both sides display: identical when the link is honest, different when it is not.
    public static func code(sharedSecret: [UInt8], transcript: [UInt8], digits: Int = defaultDigits) -> String {
        precondition((4...9).contains(digits), "digits must be 4...9")

        // HKDF-SHA256 with the transcript as salt, matching Sas.kt byte for byte.
        let prk = HMAC<SHA256>.authenticationCode(for: Data(sharedSecret), using: SymmetricKey(data: Data(transcript)))
        var okm = Data()
        var previous = Data()
        var counter: UInt8 = 1
        while okm.count < okmBytes {
            previous = Data(HMAC<SHA256>.authenticationCode(
                for: previous + label + Data([counter]),
                using: SymmetricKey(data: Data(prk))
            ))
            okm += previous
            counter += 1
        }

        // Eight bytes folded into a positive 63-bit value, then reduced. The modulo bias is why the
        // width matters: 2^63 over 10^6 makes the most likely code more likely than the least by
        // about one part in 10^13, far below anything a human comparison can be gamed on.
        var value: UInt64 = 0
        for byte in okm.prefix(okmBytes) { value = (value << 8) | UInt64(byte) }
        value &= UInt64(Int64.max)

        var modulus: UInt64 = 1
        for _ in 0..<digits { modulus *= 10 }
        return String(format: "%0\(digits)llu", value % modulus)
    }

    /// Agreement plus derivation, for the side holding the peer's ephemeral key.
    public static func codeFor(
        localKeyPair: KeyPair,
        peerEphemeralPk: [UInt8],
        sid: [UInt8],
        payeeEd25519Pk: [UInt8],
        payeeEphemeralPk: [UInt8],
        payerEphemeralPk: [UInt8],
        digits: Int = defaultDigits
    ) throws -> String {
        let peer = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: Data(peerEphemeralPk))
        let shared = try localKeyPair.privateKey.sharedSecretFromKeyAgreement(with: peer)
        let sharedBytes = shared.withUnsafeBytes { Array($0) }
        return code(
            sharedSecret: sharedBytes,
            transcript: transcript(
                sid: sid,
                payeeEd25519Pk: payeeEd25519Pk,
                payeeEphemeralPk: payeeEphemeralPk,
                payerEphemeralPk: payerEphemeralPk
            ),
            digits: digits
        )
    }
}
