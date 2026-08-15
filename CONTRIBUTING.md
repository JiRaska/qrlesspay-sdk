<!-- SPDX-License-Identifier: Apache-2.0 -->
# Contributing

## Sign your commits

Every commit needs a `Signed-off-by` line certifying the [DCO](https://developercertificate.org/):

```
git commit -s
```

DCO rather than a CLA on purpose: it asks you to certify you have the right to contribute the code,
and asks nothing else of you. Copyright stays yours.

## The two rules that are not style

**Never weaken a decoder to make something pass.** The strictness is the product. If a payload has
to be accepted that currently is not, that is a specification change, and it belongs in
[`open-bank-oss`](https://github.com/JiRaska/open-bank-oss) before it belongs here.

**Conformance vectors are generated, never hand-edited.** They come from the reference
implementation:

```
./gradlew :kmp:jvmTest --tests '*ZzNegativeCorpusGenerator*' --tests '*ZzSasVectorGenerator*' \
  -Dqrlesspay.writeVectors=true -Dqrlesspay.vectorsDir="$PWD/conformance"
```

CI regenerates and diffs them, so an edited vector fails. A corpus that can be adjusted to match the
code constrains nothing.

## Adding to the protocol

Any addition has to pass the same test the profile itself does:

> **Can the payer decide it from the bytes in hand and its own device state?**

If it needs a lookup — a registry, a directory, a trust anchor — it is not a SPAYD extension, and it
is what stops two banks' apps interoperating off the profile alone. That is why bank attestation is
not here.

## Changing behaviour that two implementations share

A change to the codec, the verification order or a derivation lands in **both** Swift and Kotlin in
the same pull request, with vectors covering it. One implementation moving alone is how a profile
becomes two dialects — and it is invisible until an adopter's build breaks, because each side's
tests keep passing.

## Running everything

```
cd swift && swift test
./gradlew :kmp:jvmTest :kmp:testDebugUnitTest
cd react-native && npm install && npx tsc --noEmit
```

Gradle needs **JDK 20**. JDK 26 fails at configuration time with a bare `26.0.2`, which reads like
an NDK version and is not one.
