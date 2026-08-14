// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors.
import Foundation

// MARK: - Beacon codec (spec §2)

public enum BeaconCodec {

    /// `[verFlags 1][nameLen 1][name ≤12][sid 4][keyHash 2][amount 3?]`
    public static func encode(_ p: BeaconPayload) -> [UInt8] {
        var flags: UInt8 = 0
        if p.amountMinor != nil { flags |= QP.flagAmount }
        let name = Array(truncateToBytes(foldToAscii(p.name), QP.nameMaxBytes).utf8)

        var out: [UInt8] = [(p.version << 4) | flags, UInt8(name.count)]
        out += name
        out += p.sid
        out += p.keyHash
        if let amount = p.amountMinor {
            let capped = min(max(amount, 0), QP.amountMaxMinor)
            out += [UInt8((capped >> 16) & 0xFF), UInt8((capped >> 8) & 0xFF), UInt8(capped & 0xFF)]
        }
        return out
    }

    public static func decode(_ bytes: [UInt8]) -> BeaconPayload? {
        guard bytes.count >= 2 else { return nil }
        let version = bytes[0] >> 4
        let flags = bytes[0] & 0x0F
        let nameLen = Int(bytes[1])
        guard nameLen <= QP.nameMaxBytes else { return nil }

        var i = 2
        guard bytes.count >= i + nameLen + QP.sidBytes + QP.keyHashBytes else { return nil }
        guard let name = String(bytes: bytes[i..<(i + nameLen)], encoding: .utf8) else { return nil }
        i += nameLen

        let sid = Array(bytes[i..<(i + QP.sidBytes)]); i += QP.sidBytes
        let keyHash = Array(bytes[i..<(i + QP.keyHashBytes)]); i += QP.keyHashBytes

        var amount: Int?
        if flags & QP.flagAmount != 0 {
            guard bytes.count >= i + 3 else { return nil }
            amount = (Int(bytes[i]) << 16) | (Int(bytes[i + 1]) << 8) | Int(bytes[i + 2])
            i += 3
        }
        return BeaconPayload(version: version, name: name, sid: sid, keyHash: keyHash, amountMinor: amount)
    }

    /// Czech diacritics folded to ASCII — the advert budget cannot afford multi-byte glyphs, and
    /// a name that arrives mangled is worse than one that arrives plain.
    static func foldToAscii(_ s: String) -> String {
        s.folding(options: [.diacriticInsensitive], locale: Locale(identifier: "en_US_POSIX"))
            .unicodeScalars
            .filter { $0.isASCII }
            .reduce(into: "") { $0.unicodeScalars.append($1) }
    }

    /// Truncates on a character boundary, never mid-codepoint.
    static func truncateToBytes(_ s: String, _ maxBytes: Int) -> String {
        var out = ""
        for ch in s {
            if out.utf8.count + String(ch).utf8.count > maxBytes { break }
            out.append(ch)
        }
        return out
    }
}

// MARK: - SPAYD (spec §3 step 4)

public struct SpaydData: Equatable {
    public let iban: String
    public let amount: String?
    public let currency: String?
    public let recipientName: String?
    public let message: String?
}

public enum Spayd {
    /// Minimal, strict parse: a valid `ACC` is required, everything else is optional. This is the
    /// gate the verification order calls, so it must reject rather than repair.
    public static func parse(_ s: String) -> SpaydData? {
        let parts = s.components(separatedBy: "*")
        guard parts.count >= 2, parts[0] == "SPD" else { return nil }
        var fields: [String: String] = [:]
        for part in parts.dropFirst(2) {
            guard let colon = part.firstIndex(of: ":") else { continue }
            fields[String(part[part.startIndex..<colon])] = String(part[part.index(after: colon)...])
        }
        guard let iban = fields["ACC"], !iban.isEmpty, !iban.contains(" ") else { return nil }
        return SpaydData(
            iban: iban,
            amount: fields["AM"],
            currency: fields["CC"],
            recipientName: fields["RN"],
            message: fields["MSG"]
        )
    }
}

// MARK: - Replay (spec §3 step 3)

/// Single-use tracking. Device-local by necessity: there is no server in this protocol (spec §11),
/// so "not seen before" can only mean "not seen by this device". That bounds the guarantee to the
/// same payer being handed the same bundle twice; a capture replayed to a *different* device is
/// left to the mandatory confirmation and SCA, which no replayed bundle can satisfy on its own.
/// Class-bound on purpose: the guard is shared state that must survive being passed into
/// `verify`, and a value-type copy would silently give every call its own empty history.
public protocol ReplayGuard: AnyObject {
    func firstUse(sid: [UInt8], nonce: [UInt8], expEpochSec: UInt64) -> Bool
}

public final class TtlReplayGuard: ReplayGuard {
    private var seen: [String: UInt64] = [:]
    private let now: () -> UInt64
    private let maxEntries: Int

    public init(now: @escaping () -> UInt64, maxEntries: Int = 512) {
        self.now = now
        self.maxEntries = maxEntries
    }

    public func firstUse(sid: [UInt8], nonce: [UInt8], expEpochSec: UInt64) -> Bool {
        let nowSec = now()
        seen = seen.filter { $0.value > nowSec }
        let key = (sid + nonce).map { String(format: "%02x", $0) }.joined()
        if seen[key] != nil { return false }
        // Second bound, independent of the clock: eviction is driven by a clock this type does not
        // own, and a stuck or backwards one must not let the set grow.
        if seen.count >= maxEntries, let oldest = seen.min(by: { $0.value < $1.value })?.key {
            seen.removeValue(forKey: oldest)
        }
        seen[key] = expEpochSec
        return true
    }

    public var count: Int { seen.count }
}

// MARK: - Payer-side guards (device-local, spec §11)

/// Display names advertised by more than one visible peer. The advert carries a first name and
/// nothing else identifying, so the list cannot tell two of them apart — and neither can the payer.
public func ambiguousDisplayNames(_ names: [String]) -> Set<String> {
    var counts: [String: Int] = [:]
    for n in names {
        counts[n.trimmingCharacters(in: .whitespaces).lowercased(), default: 0] += 1
    }
    return Set(counts.filter { $0.value > 1 }.keys)
}
