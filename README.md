# QRlessPay SDK

Open, bank-agnostic phone-to-phone payment over BLE — an extension of SPAYD, carried over the radio
instead of a QR image. Protocol: [`qrlesspay-v1`](https://github.com/JiRaska/open-bank-oss/blob/main/docs/specs/qrlesspay-v1.md) ·
Decision: ADR-0095 · Licence: Apache-2.0.

**Status: early. Read this section before believing anything below it.**

| Component | State | Verified how |
|---|---|---|
| `conformance/vectors.json` | **real** | generated from the running reference implementation (`openbank-app` @ `e3c57b5`) |
| `swift/` — native Swift core | **real, tests green** | 9 tests, `swift test`; verifies the reference implementation's own signatures |
| `kmp/` — extracted Kotlin core | **sources extracted, not yet building here** | the code is proven in `openbank-app`; its Gradle wiring in this repo is not done |
| BLE / GATT transport | **not started** | — |
| UWB ranging | **not started** | — |
| Android native packaging, React Native, Flutter | **not started** | — |

Nothing is published to a package registry. There is no release.

## What is actually demonstrated

The Swift package is a from-scratch native implementation — CryptoKit for Ed25519 and SHA-256, its
own minimal CBOR codec, no external dependencies. It is not a wrapper around the Kotlin code, and
that is the point: it runs the same protocol without sharing a line with the implementation it is
checked against.

`swift test` proves interoperability against bytes the reference implementation actually produced:

- the advert decodes to the same fields and re-encodes to the same bytes;
- the bytes covered by the signature are reconstructed identically;
- **CryptoKit verifies Ed25519 signatures produced by a different library**, over those bytes;
- `SHA-256(pk)[:2]` matches the advert's binding hash;
- the full §3 verification order accepts the reference bundles, and rejects a second presentation
  of one as `replayed`.

## What it found

Building the second implementation surfaced two things neither side could see alone. Both are in
[`KNOWN-DIVERGENCES.md`](KNOWN-DIVERGENCES.md):

1. **The reference implementation's CBOR does not match the spec** — text keys from Kotlin property
   names, byte arrays as arrays of integers, indefinite-length containers. 326 B against 197 B for
   a canonical encoding. An implementation written from the spec cannot read it.
2. **The spec's own "~140–180 B" size estimate was never measured.** The floor is ~197 B.

This SDK implements the spec, and its decoder rejects the reference encoding rather than tolerating
it. The reference implementation is dormant behind a flag and nothing has been exchanged in the
field, so this is the cheapest moment this fix will ever be available.

## Why a family of implementations rather than one shared core

One Kotlin Multiplatform core with thin bindings is the right shape for a product and the wrong one
for a standard. A bank with a pure-Swift app will not add a Kotlin runtime to its binary to accept
payments; a profile whose only real implementation is KMP is a profile with one implementation. And
the spec already invites anyone to implement from §9, so the single-core guarantee is gone the
moment a second bank does — the only question left is whether their implementation was checked
against anything.

So the shared artifact is the **conformance suite**, not the code.

## Layout

```
conformance/vectors.json   golden vectors from the reference implementation
swift/                     native Swift package (SPM)
kmp/                       extracted Kotlin Multiplatform core
KNOWN-DIVERGENCES.md       what the second implementation found
```

## Running it

```
cd swift && swift test
```

Requires Swift 5.9+ (developed on 6.3).

## Not in this repo

No backend, no registry, no trust anchor, no key directory. QRlessPay is verifiable from the bytes
in hand plus the payer's own clock, radio and history — that is what makes two banks' apps
interoperate off the profile alone, and it is why bank attestation was dropped from v1
(spec §11). An SDK that offered a lookup would invite adopters to build toward something that does
not exist.
