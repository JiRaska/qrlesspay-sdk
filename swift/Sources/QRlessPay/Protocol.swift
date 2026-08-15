// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors.
import Foundation
import CryptoKit

/// QRlessPay wire constants — `docs/specs/qrlesspay-v1.md`.
public enum QP {
    public static let version: UInt8 = 1

    // Identifiers. These are the values the reference implementation uses today; the spec's own
    // table still says `QP01`, which is not valid hex, and the 16-bit alias is squatted rather
    // than assigned. Tracked in open-bank-oss#4865 — do not treat them as final.
    public static let serviceUuid = "0000C3A4-2F3B-4E8A-9A5E-0B0E6F1C2D3A"
    public static let bundleCharUuid = "0000C3A5-2F3B-4E8A-9A5E-0B0E6F1C2D3A"
    public static let dataUuid16 = "0000F0B2-0000-1000-8000-00805F9B34FB"

    public static let sidBytes = 4
    public static let nonceBytes = 16
    public static let keyHashBytes = 2
    public static let pubKeyBytes = 32
    public static let sigBytes = 64
    public static let nameMaxBytes = 12
    public static let maxTtlSeconds: UInt64 = 90
    public static let amountMaxMinor = 16_777_215

    public static let flagAmount: UInt8 = 0x1
    /// Reserved, never emitted, never read — spec §11.
    public static let flagReserved: UInt8 = 0x2
    public static let flagSas: UInt8 = 0x4

    /// Baseline anti-relay gate (spec §5). Necessary, never sufficient.
    public static let rssiGateDbm = -70
}

/// The discovery advert (spec §2). `name` and `amount` are display hints with no authority.
public struct BeaconPayload: Equatable {
    public let version: UInt8
    public let name: String
    public let sid: [UInt8]
    public let keyHash: [UInt8]
    public let amountMinor: Int?

    public init(version: UInt8, name: String, sid: [UInt8], keyHash: [UInt8], amountMinor: Int?) {
        self.version = version
        self.name = name
        self.sid = sid
        self.keyHash = keyHash
        self.amountMinor = amountMinor
    }
}

/// The signed bundle served over GATT (spec §3).
public struct Bundle: Equatable {
    public let version: UInt8
    public let sid: [UInt8]
    public let spayd: String
    public let nonce: [UInt8]
    public let exp: UInt64
    public let pk: [UInt8]
    public let sig: [UInt8]

    public init(version: UInt8, sid: [UInt8], spayd: String, nonce: [UInt8], exp: UInt64, pk: [UInt8], sig: [UInt8]) {
        self.version = version
        self.sid = sid
        self.spayd = spayd
        self.nonce = nonce
        self.exp = exp
        self.pk = pk
        self.sig = sig
    }
}

public enum VerifyResult: Equatable {
    case ok(spayd: String)
    case rejected(reason: String)
}

public enum QRlessPayProtocol {

    // MARK: - Bundle codec (spec §3)

    /// CBOR keys, as the spec pins them. Integers, not property names — a text key costs
    /// 7–8 bytes each on a payload that has to survive a GATT read.
    private enum Key: UInt64 {
        case version = 1, sid = 2, spayd = 3, nonce = 4, exp = 5, pk = 6, sig = 7
    }

    public static func encode(_ bundle: Bundle) -> [UInt8] {
        var out: [UInt8] = Cbor.encodeUInt(7, majorType: 5) // definite-length map, 7 pairs
        out += Cbor.encodeUInt(Key.version.rawValue, majorType: 0) + Cbor.encodeUInt(UInt64(bundle.version), majorType: 0)
        out += Cbor.encodeUInt(Key.sid.rawValue, majorType: 0) + Cbor.encodeBytes(bundle.sid)
        out += Cbor.encodeUInt(Key.spayd.rawValue, majorType: 0) + Cbor.encodeText(bundle.spayd)
        out += Cbor.encodeUInt(Key.nonce.rawValue, majorType: 0) + Cbor.encodeBytes(bundle.nonce)
        out += Cbor.encodeUInt(Key.exp.rawValue, majorType: 0) + Cbor.encodeUInt(bundle.exp, majorType: 0)
        out += Cbor.encodeUInt(Key.pk.rawValue, majorType: 0) + Cbor.encodeBytes(bundle.pk)
        out += Cbor.encodeUInt(Key.sig.rawValue, majorType: 0) + Cbor.encodeBytes(bundle.sig)
        return out
    }

    /// Returns nil for anything that is not exactly one well-formed bundle. Every rejection here
    /// is structural — a malformed payload never reaches the cryptography.
    public static func decode(_ bytes: [UInt8]) -> Bundle? {
        var r = Cbor.Reader(bytes)
        guard let (major, pairs) = try? r.readHead(), major == 5, pairs == 7 else { return nil }

        var version: UInt8?
        var sid: [UInt8]?
        var spayd: String?
        var nonce: [UInt8]?
        var exp: UInt64?
        var pk: [UInt8]?
        var sig: [UInt8]?
        var seen = Set<UInt64>()

        for _ in 0..<pairs {
            guard let (keyMajor, key) = try? r.readHead(), keyMajor == 0 else { return nil }
            guard seen.insert(key).inserted else { return nil }
            guard let k = Key(rawValue: key) else { return nil }

            switch k {
            case .version:
                guard let (m, v) = try? r.readHead(), m == 0, v <= UInt64(UInt8.max) else { return nil }
                version = UInt8(v)
            case .exp:
                guard let (m, v) = try? r.readHead(), m == 0 else { return nil }
                exp = v
            case .spayd:
                guard let (m, len) = try? r.readHead(), m == 3,
                      let raw = try? r.readBytes(Int(len)),
                      let s = String(bytes: raw, encoding: .utf8) else { return nil }
                spayd = s
            case .sid, .nonce, .pk, .sig:
                guard let (m, len) = try? r.readHead(), m == 2,
                      let raw = try? r.readBytes(Int(len)) else { return nil }
                switch k {
                case .sid: sid = raw
                case .nonce: nonce = raw
                case .pk: pk = raw
                default: sig = raw
                }
            }
        }
        // Trailing bytes are a framing error, not slack to ignore.
        guard r.isAtEnd else { return nil }
        guard let version, let sid, let spayd, let nonce, let exp, let pk, let sig else { return nil }
        return Bundle(version: version, sid: sid, spayd: spayd, nonce: nonce, exp: exp, pk: pk, sig: sig)
    }

    /// The exact bytes covered by the signature. Length-prefixed nothing: every field is fixed
    /// width except the SPAYD, which comes last, so no two field sets can produce the same input.
    public static func signingBytes(version: UInt8, sid: [UInt8], nonce: [UInt8], exp: UInt64, pk: [UInt8], spayd: String) -> [UInt8] {
        var out: [UInt8] = [version]
        out += sid
        out += nonce
        out += (0..<8).reversed().map { UInt8((exp >> (8 * UInt64($0))) & 0xFF) }
        out += pk
        out += Array(spayd.utf8)
        return out
    }

    // MARK: - Verification (spec §3)

    /// Verifies in the spec's order and reports a stable machine reason. `replayGuard` has no
    /// default: the reference implementation shipped single use as "the caller's responsibility"
    /// and no caller ever took it, so this argument is the version a compiler enforces.
    public static func verify(
        advert: BeaconPayload,
        bundle: Bundle,
        nowEpochSec: UInt64,
        replayGuard: ReplayGuard
    ) -> VerifyResult {
        guard bundle.version == QP.version, advert.version == QP.version else { return .rejected(reason: "version") }
        guard bundle.sid.count == QP.sidBytes, bundle.nonce.count == QP.nonceBytes else { return .rejected(reason: "field-size") }
        guard bundle.pk.count == QP.pubKeyBytes, bundle.sig.count == QP.sigBytes else { return .rejected(reason: "key-or-sig-size") }
        guard bundle.sid == advert.sid else { return .rejected(reason: "sid-mismatch") }

        let digest = SHA256.hash(data: Data(bundle.pk))
        guard Array(digest.prefix(QP.keyHashBytes)) == advert.keyHash else { return .rejected(reason: "advert-bundle-binding") }

        guard bundle.exp > nowEpochSec else { return .rejected(reason: "expired") }
        guard bundle.exp <= nowEpochSec + QP.maxTtlSeconds else { return .rejected(reason: "exp-too-far") }

        guard let key = try? Curve25519.Signing.PublicKey(rawRepresentation: Data(bundle.pk)) else {
            return .rejected(reason: "key-or-sig-size")
        }
        let signed = signingBytes(version: QP.version, sid: bundle.sid, nonce: bundle.nonce, exp: bundle.exp, pk: bundle.pk, spayd: bundle.spayd)
        guard key.isValidSignature(Data(bundle.sig), for: Data(signed)) else { return .rejected(reason: "bad-signature") }

        guard Spayd.parse(bundle.spayd) != nil else { return .rejected(reason: "bad-spayd") }

        // Last: a bundle rejected for any other reason must leave the session re-payable, and a
        // payload that has not been proven authentic must not be able to consume someone's nonce.
        guard replayGuard.firstUse(sid: bundle.sid, nonce: bundle.nonce, expEpochSec: bundle.exp) else {
            return .rejected(reason: "replayed")
        }
        return .ok(spayd: bundle.spayd)
    }
}
