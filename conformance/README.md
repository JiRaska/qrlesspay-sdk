# Conformance vectors

`vectors.json` is generated from the running reference implementation (`openbank-app`, the
`NearPay` protocol core), not hand-written. Regenerating it means running that implementation, so a
vector can never quietly drift to match a bug in this SDK.

Fields usable as cross-implementation checks as-is: `advert.beaconHex`, `signingBytesHex`,
`bundle.pkHex`, `bundle.sigHex`, `advert.keyHashHex`.

`referenceCborHex` is recorded for the divergence described in ../KNOWN-DIVERGENCES.md. It is NOT
the spec's encoding, and a conformant decoder must reject it.

The negative corpus (truncated CBOR, wrong key hash, expired, oversize SPAYD) is not generated yet
— it is the part that matters most, because implementations agree on the happy path and diverge on
which failures they notice.
