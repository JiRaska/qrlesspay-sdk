# Divergences between the spec and the reference implementation

Both were found by building the second implementation. Neither was visible from either side alone,
which is the argument for the conformance suite in one paragraph.

**Status: #1 is fixed, #2 is open.**

## 1. The bundle's CBOR encoding did not match the spec — FIXED

**Resolved 2026-08-14.** The reference implementation now emits the spec's encoding, and its test suite asserts byte-for-byte equality
against hex produced by the Swift implementation in this repo. The account below is kept because
the *shape* of the mistake is worth more than the fix.

**Spec** (`qrlesspay-v1.md` §3) pins a map with **unsigned-integer keys** and **byte-string**
values:

```
{ 1: ver, 2: sid (bstr,4), 3: spayd, 4: nonce (bstr,16), 5: exp, 6: pk (bstr,32), 7: sig (bstr,64) }
```

**The reference implementation** (a production Kotlin Multiplatform client) uses
kotlinx-serialization-cbor with default settings, which emits:

- an **indefinite-length** map (`0xbf … 0xff`) rather than a definite-length one;
- **text keys taken from the Kotlin property names** — `"version"`, `"sid"`, `"spayd"`, `"nonce"`,
  `"exp"`, `"pk"`, `"sig"`;
- every `ByteArray` as an **indefinite-length array of integers**, not a byte string. A 4-byte
  `sid` goes on the wire as `9f 1820 1821 1822 1823 ff` — 14 bytes for 4 bytes of data.

Measured on the first vector:

| | bytes |
|---|---|
| Reference implementation | **326** |
| Spec-conformant canonical encoding | **197** |
| Spec's stated estimate | ~140–180 |

**Consequence.** An implementation written from the spec and the reference implementation cannot
read each other's bundles at all. This is the whole ballgame for a profile whose value proposition
is that any two banks' apps interoperate off the bytes alone.

**Why it was cheap to fix.** The payer path is dormant behind
a disabled feature flag and nothing is deployed, so no bundle had ever been
exchanged between two devices in the field. It cost a code change; after rollout it would have been
a wire-breaking change to a money-path protocol, needing version negotiation to undo. The window
was open only because the feature had never shipped — that is luck, not process.

**What was fixed, and how.** The implementation, not the spec: the spec's shape is better
independently of who was right, at 40% smaller on a payload that has to fit a GATT read. The
serialization library was replaced with a hand-rolled codec, because a library that can express
encodings the spec does not describe is one that can drift back into them.

Both sides now **reject** the old encoding rather than tolerating it
(`testCanonicalDecoderRejectsTheReferenceEncoding` here, `theOldLibraryEncodingIsRejected` there).
Accepting both is how two dialects become permanent.

`referenceCborHex` in `conformance/vectors.json` is retained as a negative vector: it is what a
conformant decoder must refuse.

## 2. The spec's size estimate was never measured

The spec calls the bundle "compact, ~140–180 B". A canonical, integer-keyed, byte-string encoding
of a realistic bundle is **197 B**, and cannot be much smaller: `pk` (32) + `sig` (64) + `nonce`
(16) + `sid` (4) is 116 bytes of incompressible material before the SPAYD string, which is 61 bytes
in the reference vector.

Nothing depends on the wrong number today, but a design that budgets 180 and gets 197 will be
re-litigated the first time someone tunes MTU or adds a field. The spec should state the measured
figure and the floor, so the next person adding an optional field knows what headroom actually
exists.

## 3. Four decoder defects found by fuzzing — ALL FIXED

Threat-model §8 gate 2 asks for the decoders to be fuzzed. Doing it (`FuzzTests` / `FuzzTest`, run
in both languages) turned up four defects that the golden vectors and the 20-case negative corpus
could not, because a corpus only contains the malformed inputs somebody thought of.

Three of the four existed in **one implementation only**, which is the argument for this repository
restated as evidence rather than as a claim.

| # | Where | Defect |
|---|---|---|
| 3a | both | `decode` accepted any UTF-8 in the advert's name while `encode` folded it to ASCII |
| 3b | Kotlin | `fromCbor` replaced invalid UTF-8 in the SPAYD with U+FFFD instead of rejecting |
| 3c | Kotlin | `BeaconCodec.encode` wrote the constant `NearPay.VERSION`, ignoring the payload's own |
| 3d | Kotlin | the ASCII fold trimmed *before* filtering, so it was not idempotent |

**3a is the one with a security argument.** The advert is the only thing that crosses the air
unauthenticated, and the name is the only field a payer reads off a tile before tapping it. A
hostile payee does not have to call `encode` — it hand-builds the bytes — so nothing stopped it
shipping combining marks, an RTL override or zero-width joiners into that label. Both decoders now
fold on the way **in**, and the fold keeps printable ASCII only rather than `isASCII`, which had
been letting control characters through.

**3b concerns the payment string itself.** Kotlin's `decodeToString()` substitutes U+FFFD for a
malformed sequence by default, so a corrupted SPAYD decoded "successfully" into a *silently altered*
one; Swift rejected the same bundle. Kotlin is now strict, and the two agree on what is a bundle.

**3c and 3d are interop, not security**, and both are the same shape: two implementations quietly
disagreeing on bytes for an input no test had thought to supply.

### What made the fuzzing find them

Not crash-freedom — a decoder that accepts everything never crashes. The oracles are:

1. **canonical round-trip** — a bundle that decodes must re-encode to *its input bytes*, since the
   profile pins exactly one encoding (this is what caught 3b);
2. **idempotence** — an advert that decodes must survive re-encode and re-decode unchanged (3a, 3c,
   3d);
3. **nothing fuzzed ever verifies** — no mutated bundle may pass §3 verification.

Each test also asserts that it accepted *something*, so it cannot pass by rejecting every input, and
every case is a pure function of a printed seed so a red run hands back the exact bytes. The suites
were falsified by weakening each decoder in turn: tolerating trailing bytes in the Swift bundle
decoder, for instance, goes red at seed 1.

## Everything else agrees

Worth stating explicitly, because it is the part that gives confidence the two implementations are
the same protocol:

- the advert is **byte-identical** in both directions;
- the bytes covered by the signature are **byte-identical**;
- CryptoKit verifies Ed25519 signatures produced by the Kotlin `curve25519` library over those
  bytes;
- `SHA-256(pk)[:2]` matches the advert's binding hash;
- the full verification order accepts the reference bundles and rejects the second presentation of
  one as `replayed`.
