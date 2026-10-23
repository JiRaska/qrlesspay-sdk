// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import Foundation

/// SPAYD-URI — a SPAYD descriptor carried as a URI, so the phone's own camera hands it to a bank app.
///
/// A bare `SPD*1.0*…` string is text to every OS camera: shown, never offered to an app. Only a URI
/// reaches the app-chooser path, so the camera-scannable form is the same descriptor behind a
/// registered scheme:
///
///     spayd://v1/SPD*1.0*ACC:CZ6508000000192000145399*AM:250.00*CC:CZK*MSG:Faktura%20123
///
/// Two schemes, spec: `SPAYD-URI.md` at the repository root.
///  - `spayd` — the common scheme every bank registers. On Android, several registrants give the
///    system's own chooser; on iOS the OS opens exactly one of them, which is why the second exists.
///  - `spayd-<bank code>` — the per-bank relay scheme (`spayd-0800`, `spayd-0300`, …). The app iOS
///    picked for `spayd` lists the *other* installed banks (`canOpenURL` on each relay scheme) and
///    forwards the identical payload to the one the payer chooses.
///
/// This changes nothing about what a SPAYD is: no backend, no lookup, no registry (spec §11). The URI
/// is unwrapped on the device and the bytes inside are exactly what a printed QR would carry, so
/// `unwrap` accepts both forms and a scanner migrated to it keeps reading every code already printed.
///
/// Mirrors `kmp/.../SpaydUri.kt`; both run `conformance/spayd-uri-vectors.json`.
public enum SpaydUri {

    /// The common scheme; every bank app registers it.
    public static let scheme = "spayd"

    /// The envelope version segment after the authority slashes; bumps only if the encoding does.
    public static let versionSegment = "v1"

    private static let spaydPrefix = "SPD*"

    /// The domain-verified envelope. A custom scheme can be squatted by any app; a universal link
    /// can only be opened by apps listed in the host's association file, which the host's operator
    /// controls, and iOS caches that file at install time so an installed bank opens the link
    /// offline. `https://open-bank.tech/pay/v1?SPD*…` is the common link, `…/pay/v1/<code>?…` one
    /// bank's. Pilot host; the standard's host is for the association to choose.
    public static let linkHost = "open-bank.tech"
    public static let linkPath = "/pay/v1"

    public enum Envelope: Equatable { case scheme, link }

    /// Where a URI asks to be opened: the common scheme, or one bank's relay scheme.
    public struct Target: Equatable {
        public let bankCode: String?
        public init(bankCode: String?) { self.bankCode = bankCode }
        /// The scheme this target is addressed by, lower case.
        public var scheme: String { bankCode.map { "\(SpaydUri.scheme)-\($0)" } ?? SpaydUri.scheme }
    }

    public struct Unwrapped: Equatable {
        public let spayd: String
        public let target: Target
        public let envelope: Envelope
        public init(spayd: String, target: Target, envelope: Envelope = .scheme) {
            self.spayd = spayd; self.target = target; self.envelope = envelope
        }
    }

    /// Why `parse` refused. Asserted by the conformance corpus — the reason is part of the contract.
    public enum Rejection: String, Equatable {
        case notSpaydUri = "not-spayd-uri"
        case badBankCode = "bad-bank-code"
        case badVersion = "bad-version"
        case badPercentEncoding = "bad-percent-encoding"
        case illegalCharacter = "illegal-character"
        case notSpayd = "not-spayd"
        case badLinkHost = "bad-link-host"
    }

    public enum Result: Equatable {
        case ok(Unwrapped)
        case rejected(Rejection)
    }

    public enum WrapError: Error, Equatable {
        case notASpayd
        case badBankCode
    }

    /// Wrap a SPAYD as a URI. `bankCode` selects the relay scheme and must be exactly four digits;
    /// nil gives the common scheme a QR code should carry. A string that is not a SPAYD is refused
    /// rather than wrapped into something every receiver will reject.
    public static func wrap(_ spayd: String, bankCode: String? = nil) throws -> String {
        guard Spayd.parse(spayd) != nil else { throw WrapError.notASpayd }
        if let code = bankCode, !isBankCode(code) { throw WrapError.badBankCode }
        return Target(bankCode: bankCode).scheme + "://" + versionSegment + "/" + percentEncode(spayd)
    }

    /// Wrap a SPAYD as a domain-verified link — the form a QR should carry where the link host's
    /// association files are deployed. `bankCode` addresses one bank's path; nil is the common link.
    public static func wrapLink(_ spayd: String, bankCode: String? = nil) throws -> String {
        guard Spayd.parse(spayd) != nil else { throw WrapError.notASpayd }
        if let code = bankCode, !isBankCode(code) { throw WrapError.badBankCode }
        let path = bankCode.map { "\(linkPath)/\($0)" } ?? linkPath
        return "https://" + linkHost + path + "?" + percentEncode(spayd, forQuery: true)
    }

    /// Accept a scanned payload in either form — bare `SPD*…` or a SPAYD-URI — and return the SPAYD.
    /// The one call a QR scanner should make. Nil for anything else; use `parse` for the reason.
    public static func unwrap(_ raw: String) -> String? {
        let s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix(spaydPrefix) { return Spayd.parse(s) != nil ? s : nil }
        if case .ok(let u) = parse(s) { return u.spayd }
        return nil
    }

    /// Strict parse of a SPAYD-URI with the reason on refusal. A bare `SPD*…` is `.notSpaydUri`.
    public static func parse(_ uri: String) -> Result {
        let s = uri.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let colon = s.firstIndex(of: ":") else { return .rejected(.notSpaydUri) }
        let schemePart = s[s.startIndex..<colon].lowercased()
        if schemePart == "https" { return parseLink(String(s[s.index(after: colon)...])) }
        let target: Target
        if schemePart == scheme {
            target = Target(bankCode: nil)
        } else if schemePart.hasPrefix(scheme + "-") {
            let code = String(schemePart.dropFirst(scheme.count + 1))
            guard isBankCode(code) else { return .rejected(.badBankCode) }
            target = Target(bankCode: code)
        } else {
            return .rejected(.notSpaydUri)
        }

        var rest = Substring(s[s.index(after: colon)...])
        // Tolerated on input, never produced: `spayd:SPD*…` and `spayd://SPD*…`.
        if rest.hasPrefix("//") {
            rest = rest.dropFirst(2)
            if let slash = rest.firstIndex(of: "/") {
                let head = rest[rest.startIndex..<slash]
                if head.lowercased() == versionSegment {
                    rest = rest[rest.index(after: slash)...]
                } else if !head.uppercased().hasPrefix(spaydPrefix) {
                    return .rejected(.badVersion)
                }
            } else if !rest.uppercased().hasPrefix(spaydPrefix) {
                return .rejected(.badVersion)
            }
        }

        return decodePayload(String(rest), target: target, envelope: .scheme)
    }

    /// `//host/pay/v1[/code]?payload[#…]`, the part after `https:`. Host case-insensitive, path exact.
    private static func parseLink(_ afterScheme: String) -> Result {
        guard afterScheme.hasPrefix("//") else { return .rejected(.notSpaydUri) }
        let noFragment = afterScheme.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false)[0]
        let parts = noFragment.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)
        let authorityAndPath = parts[0].dropFirst(2)
        let query = parts.count > 1 ? String(parts[1]) : ""
        let host: Substring
        let path: String
        if let slash = authorityAndPath.firstIndex(of: "/") {
            host = authorityAndPath[authorityAndPath.startIndex..<slash]
            path = String(authorityAndPath[slash...])
        } else {
            host = authorityAndPath
            path = ""
        }
        guard host.lowercased() == linkHost else { return .rejected(.badLinkHost) }
        var trimmed = path
        while trimmed.hasSuffix("/") { trimmed.removeLast() }
        let target: Target
        if trimmed == linkPath {
            target = Target(bankCode: nil)
        } else if trimmed.hasPrefix(linkPath + "/") {
            let code = String(trimmed.dropFirst(linkPath.count + 1))
            guard isBankCode(code) else { return .rejected(.badBankCode) }
            target = Target(bankCode: code)
        } else {
            return .rejected(.badVersion)
        }
        return decodePayload(query, target: target, envelope: .link)
    }

    private static func decodePayload(_ encoded: String, target: Target, envelope: Envelope) -> Result {
        for u in encoded.utf8 where u <= 0x20 || u >= 0x7F { return .rejected(.illegalCharacter) }
        guard let decoded = percentDecode(encoded) else { return .rejected(.badPercentEncoding) }
        // An escape can smuggle in what a raw byte could not; a control character (NUL above all)
        // has no place in a SPAYD, and refusing it is the same decision as refusing the raw byte.
        if decoded.unicodeScalars.contains(where: { $0.value < 0x20 || $0.value == 0x7F }) { return .rejected(.illegalCharacter) }
        guard decoded.hasPrefix(spaydPrefix), Spayd.parse(decoded) != nil else { return .rejected(.notSpayd) }
        return .ok(Unwrapped(spayd: decoded, target: target, envelope: envelope))
    }

    /// The relay scheme for a bank code — what a relay app probes with `canOpenURL`. Nil for an
    /// invalid code, so a hand-maintained bank list cannot produce a scheme nobody registered.
    public static func relayScheme(bankCode: String) -> String? {
        isBankCode(bankCode) ? "\(scheme)-\(bankCode)" : nil
    }

    public static func isBankCode(_ code: String) -> Bool {
        code.utf8.count == 4 && code.utf8.allSatisfy { $0 >= 0x30 && $0 <= 0x39 }
    }

    // Unreserved (RFC 3986 §2.3) plus the sub-delims a SPAYD actually uses and a path segment permits.
    // In a query string `+` is read as a space by many form decoders, so the link form encodes it;
    // a path segment has no such convention and keeps it raw.
    private static func isRaw(_ b: UInt8, forQuery: Bool) -> Bool {
        switch b {
        case 0x41...0x5A, 0x61...0x7A, 0x30...0x39: return true
        case 0x2D, 0x2E, 0x5F, 0x7E: return true          // - . _ ~
        case 0x2A, 0x3A, 0x2C: return true                // * : ,
        case 0x2B: return !forQuery                       // +
        default: return false
        }
    }

    private static let hex = Array("0123456789ABCDEF".utf8)

    static func percentEncode(_ s: String, forQuery: Bool = false) -> String {
        var out: [UInt8] = []
        out.reserveCapacity(s.utf8.count + 16)
        for b in s.utf8 {
            if isRaw(b, forQuery: forQuery) {
                out.append(b)
            } else {
                out.append(0x25); out.append(hex[Int(b >> 4)]); out.append(hex[Int(b & 0x0F)])
            }
        }
        return String(decoding: out, as: UTF8.self)
    }

    /// Nil on a malformed escape or on bytes that are not valid UTF-8.
    static func percentDecode(_ s: String) -> String? {
        let bytes = Array(s.utf8)
        var out: [UInt8] = []
        out.reserveCapacity(bytes.count)
        var i = 0
        while i < bytes.count {
            if bytes[i] == 0x25 {
                guard i + 2 < bytes.count,
                      let hi = hexValue(bytes[i + 1]), let lo = hexValue(bytes[i + 2]) else { return nil }
                out.append((hi << 4) | lo)
                i += 3
            } else {
                out.append(bytes[i])
                i += 1
            }
        }
        // String(decoding:) substitutes U+FFFD for invalid sequences instead of failing; re-encoding
        // and comparing is how to detect that it did. Same construction as the Kotlin side.
        let decoded = String(decoding: out, as: UTF8.self)
        return Array(decoded.utf8) == out ? decoded : nil
    }

    private static func hexValue(_ b: UInt8) -> UInt8? {
        switch b {
        case 0x30...0x39: return b - 0x30
        case 0x41...0x46: return b - 0x41 + 10
        case 0x61...0x66: return b - 0x61 + 10
        default: return nil
        }
    }
}
