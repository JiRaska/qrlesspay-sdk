# Conformance vectors

Both files are **generated from the running reference implementation**, never hand-written. A
hand-typed hex string is a transcription error waiting to be debugged as a protocol bug, and a
corpus that drifts to match the code it constrains is worse than no corpus.

Regenerate with:

```
./gradlew :kmp:jvmTest --tests '*ZzNegativeCorpusGenerator*' \
  -Dqrlesspay.writeVectors=true -Dqrlesspay.vectorsDir="$PWD/conformance"
```

(The generator is a no-op without that property, so an ordinary test run cannot silently rewrite a
committed artifact.)

## `vectors.json` — positive

Three valid sessions. Fields usable as cross-implementation checks as-is: `advert.beaconHex`,
`signingBytesHex`, `bundle.pkHex`, `bundle.sigHex`, `advert.keyHashHex`.

`referenceCborHex` is the **pre-#450 encoding** and is retained deliberately: a conformant decoder
must refuse it. See `../KNOWN-DIVERGENCES.md`.

## `negative-vectors.json` — the corpus that decides conformance

Twenty cases. This is where conformance is actually decided: two implementations agree on the happy
path by construction, because both were written from the same spec by someone who wanted them to
work. They diverge on **which malformed inputs they notice**. A corpus of valid bundles proves
neither is broken; only a corpus of invalid ones proves they are the same.

**`structural` (10)** — must fail to decode, before any cryptography runs:
indefinite-length map with text keys (the pre-#450 encoding), truncation at the head and mid-value,
a trailing byte, a wrong pair count, an unknown key, a non-canonical integer argument, a text string
where bytes belong, empty input, and repeated map headers.

**`semantic` (9)** — decode cleanly and must be rejected, each for its **own** reason:
`version`, `field-size`, `key-or-sig-size`, `sid-mismatch`, `advert-bundle-binding`, `expired`,
`exp-too-far`, `bad-signature` (amount changed from 250.00 to 950.00 after signing — the attack the
signature exists to stop), `bad-spayd` (validly signed rubbish: a good signature over a payload that
is not a SPAYD is still rubbish).

The reason is asserted, not just the rejection. Two implementations that refuse the same payload for
different reasons disagree the moment either acts on the reason — telemetry, retry policy, or the
copy a payer reads.

**`replay` (1)** — accepted once, then rejected as `replayed`.

## Who runs it

| | Positive | Negative |
|---|---|---|
| Swift (`swift test`) | yes | yes |
| Kotlin JVM (`./gradlew :kmp:jvmTest`) | yes | yes |
| Kotlin iOS targets | no | no — reading a file from `commonTest` needs a multiplatform resource story this module does not have yet |

Both suites were **falsified**: weakening either decoder (dropping the trailing-byte check and the
unknown-key rejection) turns the structural corpus red in both languages.
