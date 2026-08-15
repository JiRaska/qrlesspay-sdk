// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors.
import Foundation
import CryptoKit

/// Signing side of the profile. Verification lives in `QRlessPayProtocol.verify`; this is only
/// what a payee needs.
///
/// The key is per-session and memory-only by construction — there is no API here to persist one.
/// It proves "same device, this session", never identity: the spec is explicit that long-term
/// identity comes from the IBAN plus VoP where a scheme exists, and that there is no in-protocol
/// identity assertion (§4, §11).
public enum Ed25519 {

    public struct SessionKey {
        private let key: Curve25519.Signing.PrivateKey
        public let publicKey: [UInt8]

        init(key: Curve25519.Signing.PrivateKey) {
            self.key = key
            self.publicKey = Array(key.publicKey.rawRepresentation)
        }

        /// **CryptoKit's Ed25519 signing is randomized, not the deterministic construction RFC 8032
        /// describes.** Measured: signing one message twice with one key yields two different
        /// signatures, and both verify. Kotlin's `curve25519` library is deterministic, so the same
        /// seed and message produce *different* signature bytes on the two platforms.
        ///
        /// This is not a defect on either side — a verifier accepts both — but it does decide what
        /// a conformance suite may assert. Signature bytes are comparable across implementations
        /// only for a *fixed vector*, never for freshly minted output; everything else in the
        /// bundle (sid, nonce, exp, public key, the signed byte string and the CBOR encoding) is
        /// reproducible and is what the vectors pin.
        public func sign(_ message: [UInt8]) -> [UInt8] {
            guard let sig = try? key.signature(for: Data(message)) else {
                preconditionFailure("Ed25519 signing cannot fail for a valid key")
            }
            return Array(sig)
        }
    }

    /// Derives the session key from a 32-byte seed. Taking a seed rather than generating internally
    /// is what lets a test drive this deterministically without a second code path in production.
    public static func keyFromSeed(_ seed: [UInt8]) -> SessionKey {
        guard seed.count == 32, let key = try? Curve25519.Signing.PrivateKey(rawRepresentation: Data(seed)) else {
            preconditionFailure("seed must be 32 bytes")
        }
        return SessionKey(key: key)
    }

    /// `SHA-256(pk)[:2]` — the two bytes the advert carries to bind itself to the bundle it
    /// announces (spec §2, §3 step 1).
    public static func keyHash(_ publicKey: [UInt8]) -> [UInt8] {
        Array(SHA256.hash(data: Data(publicKey)).prefix(QP.keyHashBytes))
    }
}
