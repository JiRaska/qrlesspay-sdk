# Known divergences between the spec and the reference implementation

Both of these were found by building the second implementation. Neither was visible from either
side alone, which is the argument for the conformance suite in one paragraph.

## 1. The bundle's CBOR encoding does not match the spec

**Spec** (`qrlesspay-v1.md` §3) pins a map with **unsigned-integer keys** and **byte-string**
values:

```
{ 1: ver, 2: sid (bstr,4), 3: spayd, 4: nonce (bstr,16), 5: exp, 6: pk (bstr,32), 7: sig (bstr,64) }
```

**The reference implementation** (`openbank-app`, `NearPayBundle.toCbor()`) uses
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

**Why now is the cheap moment to fix it.** The payer path is dormant behind
`NearPay.PAYER_DISCOVERY_ENABLED = false` and nothing is deployed, so no bundle has ever been
exchanged between two devices in the field. Changing the encoding today costs a code change;
changing it after rollout is a wire-breaking change to a money-path protocol.

**Recommendation:** fix the implementation, not the spec. The spec's shape is the better one
independently of who is right — it is 40% smaller on a payload that has to fit a GATT read.

This SDK implements **the spec**. Its decoder rejects the reference encoding rather than tolerating
it (`testCanonicalDecoderRejectsTheReferenceEncoding`), because silently accepting both is how two
dialects become permanent.

## 2. The spec's size estimate was never measured

The spec calls the bundle "compact, ~140–180 B". A canonical, integer-keyed, byte-string encoding
of a realistic bundle is **197 B**, and cannot be much smaller: `pk` (32) + `sig` (64) + `nonce`
(16) + `sid` (4) is 116 bytes of incompressible material before the SPAYD string, which is 61 bytes
in the reference vector.

Nothing depends on the wrong number today, but a design that budgets 180 and gets 197 will be
re-litigated the first time someone tunes MTU or adds a field. The spec should state the measured
figure and the floor, so the next person adding an optional field knows what headroom actually
exists.

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
