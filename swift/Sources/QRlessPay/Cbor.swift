// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors.
import Foundation

/// The slice of CBOR (RFC 8949) this profile needs, encoder and decoder, written out rather than
/// pulled in.
///
/// The wire spec pins a **definite-length map with unsigned-integer keys and byte-string values**.
/// That is a deliberately tiny surface, and owning it buys two things a general CBOR library does
/// not: a bundle can be rejected for being *encoded* unexpectedly (indefinite lengths, text keys,
/// arrays where bytes belong) rather than quietly accepted, and the encoder cannot drift from the
/// spec because it has no other modes to drift into.
enum Cbor {

    enum DecodeError: Error, Equatable {
        case truncated
        case unexpectedMajorType(UInt8)
        case indefiniteLength
        case nonCanonicalInteger
        case duplicateKey(UInt64)
        case trailingBytes
    }

    // MARK: - Encoding

    static func encodeUInt(_ value: UInt64, majorType: UInt8) -> [UInt8] {
        let mt = majorType << 5
        switch value {
        case 0...23:
            return [mt | UInt8(value)]
        case 24...0xFF:
            return [mt | 24, UInt8(value)]
        case 0x100...0xFFFF:
            return [mt | 25, UInt8(value >> 8), UInt8(value & 0xFF)]
        case 0x1_0000...0xFFFF_FFFF:
            return [mt | 26] + (0..<4).reversed().map { UInt8((value >> (8 * UInt64($0))) & 0xFF) }
        default:
            return [mt | 27] + (0..<8).reversed().map { UInt8((value >> (8 * UInt64($0))) & 0xFF) }
        }
    }

    static func encodeBytes(_ bytes: [UInt8]) -> [UInt8] {
        encodeUInt(UInt64(bytes.count), majorType: 2) + bytes
    }

    static func encodeText(_ string: String) -> [UInt8] {
        let utf8 = Array(string.utf8)
        return encodeUInt(UInt64(utf8.count), majorType: 3) + utf8
    }

    // MARK: - Decoding

    struct Reader {
        private let bytes: [UInt8]
        private var index: Int = 0

        init(_ bytes: [UInt8]) { self.bytes = bytes }

        var isAtEnd: Bool { index >= bytes.count }

        mutating func readByte() throws -> UInt8 {
            guard index < bytes.count else { throw DecodeError.truncated }
            defer { index += 1 }
            return bytes[index]
        }

        /// Reads a head and returns (majorType, argument). Rejects indefinite lengths and
        /// non-canonical integer encodings — both are ways the same value can be spelled twice,
        /// and a payload that carries a signature must have exactly one spelling.
        mutating func readHead() throws -> (UInt8, UInt64) {
            let initial = try readByte()
            let major = initial >> 5
            let info = initial & 0x1F
            switch info {
            case 0...23:
                return (major, UInt64(info))
            case 24:
                let v = UInt64(try readByte())
                guard v >= 24 else { throw DecodeError.nonCanonicalInteger }
                return (major, v)
            case 25:
                var v: UInt64 = 0
                for _ in 0..<2 { v = (v << 8) | UInt64(try readByte()) }
                guard v > 0xFF else { throw DecodeError.nonCanonicalInteger }
                return (major, v)
            case 26:
                var v: UInt64 = 0
                for _ in 0..<4 { v = (v << 8) | UInt64(try readByte()) }
                guard v > 0xFFFF else { throw DecodeError.nonCanonicalInteger }
                return (major, v)
            case 27:
                var v: UInt64 = 0
                for _ in 0..<8 { v = (v << 8) | UInt64(try readByte()) }
                guard v > 0xFFFF_FFFF else { throw DecodeError.nonCanonicalInteger }
                return (major, v)
            case 31:
                throw DecodeError.indefiniteLength
            default:
                throw DecodeError.unexpectedMajorType(major)
            }
        }

        mutating func readBytes(_ count: Int) throws -> [UInt8] {
            guard count >= 0, index + count <= bytes.count else { throw DecodeError.truncated }
            defer { index += count }
            return Array(bytes[index..<(index + count)])
        }
    }
}
