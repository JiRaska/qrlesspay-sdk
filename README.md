# QRlessPay SDK

Open, bank-agnostic phone-to-phone payment over BLE — an extension of SPAYD, carried over the radio
instead of a QR image. Protocol: [`qrlesspay-v1`](https://github.com/JiRaska/open-bank-oss/blob/main/docs/specs/qrlesspay-v1.md) ·
Decision: ADR-0095 · Licence: Apache-2.0.

**Status: early. Read this section before believing anything below it.**

| Component | State | Verified how |
|---|---|---|
| `conformance/vectors.json` | **real** | generated from the running reference implementation (`openbank-app` @ `db6e29f3d`) |
| `swift/` — native Swift core | **real, 19 tests green** | `swift test`; verifies the reference implementation's own signatures |
| `swift/` — CoreBluetooth transport | **written, compiles, not exercised on hardware** | advertise + GATT server + scan + GATT client; driven end-to-end by a loopback transport in tests. **No two-device run has happened** |
| `kmp/` — Kotlin Multiplatform core | **real, builds, 19 tests green** | `:kmp:jvmTest` (11) + `:kmp:testDebugUnitTest` (8); produces byte-identical CBOR to the Swift implementation |
| `react-native/` — TypeScript API + iOS bridge | **TS type-checks; bridge not compiled here** | `tsc --noEmit`; the bridge compiles inside a host RN app, which this repo does not contain |
| `kmp/` — Android BLE transport | **written, compiles, produces an AAR, not exercised on hardware** | `:kmp:assembleDebug`; advertise + GATT server + scan + GATT client |
| `react-native/` — Android bridge | **written, not compiled here** | needs `com.facebook.react:react-android`, which resolves only inside a host RN app. Its KMP-facing calls are compile-checked by `BridgeApiSurfaceTest` |
| UWB — portable token codec + downgrade policy | **real, 19 corpus cases in both languages** | `swift test` and `:kmp:jvmTest` over the shared `uwb-vectors.json` |
| UWB — Android ranger (`androidx.core.uwb`) | **written, compiles** | `:kmp:compileDebugKotlinAndroid` |
| UWB — iOS ranger (NearbyInteraction) | **written, type-checks against the iOS SDK** | `swiftc -typecheck -sdk iphoneos`; excluded from the macOS build by an `os(iOS)` guard |
| Negative conformance corpus | **real, 20 cases, run by both implementations** | generated from the reference implementation; falsified by weakening each decoder in turn |

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

1. **The reference implementation's CBOR did not match the spec** — text keys from Kotlin property
   names, byte arrays as arrays of integers, indefinite-length containers. 326 B against 197 B for
   a canonical encoding, and unreadable by anything written from the spec.
   **Fixed** in `openbank-app` `db6e29f3d`; both implementations now agree byte-for-byte and both
   reject the old encoding.
2. **The spec's own "~140–180 B" size estimate was never measured.** The floor is ~197 B. **Open** —
   it belongs in `open-bank-oss`, so the next person adding an optional field knows the real
   headroom.

Neither was findable by testing: the app's suite round-tripped its own encoder into its own
decoder, which cannot fail while both halves are wrong the same way. That is the concrete argument
for this repo existing at all.

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
swift/                     native Swift package (SPM) — protocol core + CoreBluetooth transport
kmp/                       Kotlin Multiplatform core (Gradle)
react-native/              TypeScript API + iOS bridge
KNOWN-DIVERGENCES.md       what the second implementation found
```

## Running it

```
cd swift && swift test                      # 19 tests
./gradlew :kmp:jvmTest                      # 11 tests, needs JDK 20
./gradlew :kmp:testDebugUnitTest            # 8 tests on the Android target
./gradlew :kmp:assembleDebug                # produces kmp-debug.aar

# the iOS-only ranger, which the macOS build excludes:
cd swift && xcrun swiftc -typecheck -sdk "$(xcrun --sdk iphoneos --show-sdk-path)" \
  -target arm64-apple-ios16.0 Sources/QRlessPay/*.swift
cd react-native && npm install && npx tsc --noEmit
```

Swift 5.9+ (developed on 6.3). The KMP module declares iOS targets but only the JVM target is
built here — the Kotlin/Native toolchain download is not something CI does yet.

## UWB is a proposal, not part of the spec

The wire spec describes UWB in prose (§5: "negotiated, best-effort") and gives it **no wire
format** — no capability flag, no channel for exchanging ranging parameters. Implementing it
therefore required inventing those bits, and they are marked as a **v1.1 proposal** everywhere they
appear rather than presented as normative: advert flag `0x8` for UWB-capable, and a fourth GATT
characteristic carrying a tagged token. Tracked in `open-bank-oss`.

Two facts shape it, both unwelcome. **Apple's Nearby Interaction and Android's FiRa stack do not
interoperate**, so a cross-platform pair cannot range at any effort — the codec detects that and
downgrades rather than starting a session that never converges. And **UWB hardware is a minority**,
so RSSI stays the baseline. UWB is the only cryptographic answer to a relay attack and it is
unavailable on most pairs; nothing about it may become a precondition for paying.

## One cross-platform gotcha worth knowing before you port

**CryptoKit's Ed25519 signing is randomised; Kotlin's `curve25519` is deterministic.** Measured:
signing the same message twice with the same key under CryptoKit yields two different signatures,
and both verify. Neither is wrong, but it decides what a conformance suite may assert — signature
bytes are comparable against a *fixed vector*, never between two fresh mints. Everything else (sid,
nonce, expiry, public key, the signed byte string, the CBOR encoding) is reproducible and is what
the vectors pin.

## Not in this repo

No backend, no registry, no trust anchor, no key directory. QRlessPay is verifiable from the bytes
in hand plus the payer's own clock, radio and history — that is what makes two banks' apps
interoperate off the profile alone, and it is why bank attestation was dropped from v1
(spec §11). An SDK that offered a lookup would invite adopters to build toward something that does
not exist.
