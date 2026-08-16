// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors.
import XCTest
@testable import QRlessPay

/// Property-based fuzzing of the decoders — threat-model §8 gate 2.
///
/// The negative corpus covers the malformed inputs someone thought of. This covers the rest, which
/// for a parser is the half that matters: the corpus proves the decoder rejects what we predicted,
/// and only fuzzing probes what we did not.
///
/// **Seeded, never random.** A fuzzer whose failures cannot be reproduced reports a crash nobody
/// can act on. Every case here is a pure function of a `UInt64` seed, printed on failure, so a red
/// CI run hands back the exact input.
///
/// ### The oracles
///
/// "It did not crash" is a weak property, and on its own it would pass against a decoder that
/// accepted everything. Two stronger ones are checked:
///
/// 1. **Canonical round-trip.** The profile pins exactly one encoding per bundle, so a decode that
///    succeeds must re-encode to the *input bytes*. Any input that decodes but re-encodes
///    differently is a non-canonical encoding the decoder let through — precisely the class of bug
///    the strictness exists to stop, and the one that lets two dialects appear.
/// 2. **No decode without full consumption.** Trailing bytes are a framing error; an accepted input
///    with bytes left over would mean a payload could carry an unnoticed passenger.
final class FuzzTests: XCTestCase {

    /// xorshift64*, written out so the corpus is identical for a given seed on any machine and any
    /// Swift version — `SystemRandomNumberGenerator` is none of those things.
    private struct Rng {
        private var state: UInt64
        init(seed: UInt64) { state = seed == 0 ? 0x9E3779B97F4A7C15 : seed }
        mutating func next() -> UInt64 {
            state ^= state >> 12
            state ^= state << 25
            state ^= state >> 27
            return state &* 2685821657736338717
        }
        mutating func int(_ bound: Int) -> Int { bound <= 0 ? 0 : Int(next() % UInt64(bound)) }
        mutating func byte() -> UInt8 { UInt8(next() & 0xFF) }
    }

    private func hex(_ b: [UInt8]) -> String { b.map { String(format: "%02x", $0) }.joined() }

    /// A valid bundle to mutate. Mutation beats pure random by orders of magnitude here: random
    /// bytes almost never survive the map header, so they exercise the first check and nothing
    /// behind it.
    private func seedBundle() -> [UInt8] {
        QRlessPayProtocol.encode(QRlessPay.Bundle(
            version: 1,
            sid: [0x20, 0x21, 0x22, 0x23],
            spayd: "SPD*1.0*ACC:CZ6508000000192000145399*AM:250.00*CC:CZK*RN:Jiri",
            nonce: Array(0x24...0x33),
            exp: 1_090,
            pk: Array(repeating: 0xAB, count: 32),
            sig: Array(repeating: 0xCD, count: 64)
        ))
    }

    private func mutate(_ input: [UInt8], _ rng: inout Rng) -> [UInt8] {
        var out = input
        switch rng.int(6) {
        case 0 where !out.isEmpty:                       // flip a bit
            let i = rng.int(out.count)
            out[i] ^= UInt8(1 << rng.int(8))
        case 1 where !out.isEmpty:                      // replace a byte
            out[rng.int(out.count)] = rng.byte()
        case 2 where !out.isEmpty:                      // truncate
            out = Array(out.prefix(rng.int(out.count)))
        case 3:                                         // append junk
            out += (0..<(1 + rng.int(8))).map { _ in rng.byte() }
        case 4 where !out.isEmpty:                      // splice out a run
            let i = rng.int(out.count)
            let n = min(1 + rng.int(8), out.count - i)
            out.removeSubrange(i..<(i + n))
        default:                                        // insert a run
            let i = rng.int(out.count + 1)
            out.insert(contentsOf: (0..<(1 + rng.int(8))).map { _ in rng.byte() }, at: i)
        }
        return out
    }

    // MARK: - Bundle decoder

    func testBundleDecoderSurvivesMutationAndStaysCanonical() {
        var accepted = 0
        for seed in UInt64(1)...20_000 {
            var rng = Rng(seed: seed)
            var input = seedBundle()
            for _ in 0...rng.int(4) { input = mutate(input, &rng) }

            guard let decoded = QRlessPayProtocol.decode(input) else { continue }
            accepted += 1
            XCTAssertEqual(
                hex(QRlessPayProtocol.encode(decoded)), hex(input),
                "seed \(seed): decoded but did not re-encode to its input — a non-canonical encoding was accepted"
            )
        }
        // If nothing was ever accepted the round-trip assertion never ran, and this test would be
        // green while proving nothing. Mutation must sometimes produce a still-valid bundle.
        XCTAssertGreaterThan(accepted, 0, "no mutated input was ever accepted — the oracle never fired")
    }

    func testBundleDecoderSurvivesPureRandom() {
        for seed in UInt64(1)...20_000 {
            var rng = Rng(seed: seed &+ 1_000_000)
            let input = (0..<rng.int(300)).map { _ in rng.byte() }
            if let decoded = QRlessPayProtocol.decode(input) {
                XCTAssertEqual(hex(QRlessPayProtocol.encode(decoded)), hex(input), "seed \(seed)")
            }
        }
    }

    // MARK: - Beacon decoder
    //
    // The advert is the only thing that crosses the air unsigned and unencrypted, so its parser is
    // the one an attacker reaches without any cooperation from the payee. It had no negative corpus
    // at all before this.

    func testBeaconDecoderSurvivesMutationAndStaysCanonical() {
        let seedBeacon = BeaconCodec.encode(BeaconPayload(
            version: 1, name: "Jiri", sid: [0x20, 0x21, 0x22, 0x23], keyHash: [0x56, 0x47], amountMinor: 25_000
        ))
        var accepted = 0
        for seed in UInt64(1)...20_000 {
            var rng = Rng(seed: seed &+ 2_000_000)
            var input = seedBeacon
            for _ in 0...rng.int(3) { input = mutate(input, &rng) }

            guard let decoded = BeaconCodec.decode(input) else { continue }
            accepted += 1
            // The advert is not length-prefixed as a whole, so trailing bytes are legal and a
            // re-encode is not required to equal the input. What must hold is idempotence:
            // whatever was decoded must survive a re-encode and a second decode unchanged.
            let reencoded = BeaconCodec.encode(decoded)
            let second = BeaconCodec.decode(reencoded)
            XCTAssertEqual(second?.name, decoded.name, "seed \(seed)")
            XCTAssertEqual(second?.sid, decoded.sid, "seed \(seed)")
            XCTAssertEqual(second?.keyHash, decoded.keyHash, "seed \(seed)")
            XCTAssertEqual(second?.amountMinor, decoded.amountMinor, "seed \(seed)")
        }
        XCTAssertGreaterThan(accepted, 0, "no mutated advert was ever accepted — the oracle never fired")
    }

    /// The regression the fuzzer actually found, pinned as a named case so it cannot silently
    /// regress once the corpus shifts.
    ///
    /// A hostile payee does not have to call `encode` — it hand-builds the advert bytes. Nothing
    /// then stopped it putting a combining mark or an RTL override into the name, which is the one
    /// field the payer reads off an unauthenticated tile.
    func testDecodedNameCarriesOnlyPrintableAscii() {
        let hostile = Array("Jiri\u{036A}\u{202E}".utf8)
        let advert: [UInt8] = [1 << 4, UInt8(hostile.count)] + hostile
            + [0x20, 0x21, 0x22, 0x23] + [0x56, 0x47]

        XCTAssertEqual(BeaconCodec.decode(advert)?.name, "Jiri")
    }

    // MARK: - UWB token decoder

    func testUwbTokenDecoderSurvivesMutationAndStaysCanonical() {
        let seedToken = UwbTokenCodec.encode(
            .controller(address: [0xAB, 0xCD], channel: 9, preambleIndex: 10, sessionId: 0x01020304)
        )
        var accepted = 0
        for seed in UInt64(1)...20_000 {
            var rng = Rng(seed: seed &+ 3_000_000)
            var input = seedToken
            for _ in 0...rng.int(3) { input = mutate(input, &rng) }

            guard let decoded = UwbTokenCodec.decode(input) else { continue }
            accepted += 1
            XCTAssertEqual(
                hex(UwbTokenCodec.encode(decoded)), hex(input),
                "seed \(seed): token decoded but did not re-encode to its input"
            )
        }
        XCTAssertGreaterThan(accepted, 0, "no mutated token was ever accepted — the oracle never fired")
    }

    // MARK: - Verification under hostile input
    //
    // Decoding is only half the surface: a well-formed bundle carrying hostile *values* still
    // reaches the cryptography. Nothing here may be accepted, because none of these carry a
    // signature anyone made.

    func testNoFuzzedBundleEverVerifies() {
        let advert = BeaconPayload(
            version: 1, name: "Jiri", sid: [0x20, 0x21, 0x22, 0x23], keyHash: [0x56, 0x47], amountMinor: nil
        )
        for seed in UInt64(1)...5_000 {
            var rng = Rng(seed: seed &+ 4_000_000)
            var input = seedBundle()
            for _ in 0...rng.int(3) { input = mutate(input, &rng) }
            guard let bundle = QRlessPayProtocol.decode(input) else { continue }

            let result = QRlessPayProtocol.verify(
                advert: advert, bundle: bundle, nowEpochSec: 1_010, replayGuard: TtlReplayGuard(now: { 1_010 })
            )
            guard case .rejected = result else {
                return XCTFail("seed \(seed): a fuzzed bundle verified — \(hex(input))")
            }
        }
    }
}
