<!-- SPDX-License-Identifier: Apache-2.0 -->
# Security policy

QRlessPay carries payment instructions between two phones. A defect here can cost someone money,
so please treat findings accordingly.

## Reporting

**Do not open a public issue for a security finding.** Use GitHub's private vulnerability reporting
on this repository (Security → Report a vulnerability), which reaches the maintainers without
disclosing anything.

Useful in a report: which implementation and version, the bytes or conditions that trigger it, and
what an attacker gains. A proof of concept is welcome and never required.

## What is in scope

The protocol as implemented here, and the profile it implements:

- the bundle codec and its strictness — anything that makes a malformed or hostile payload decode;
- the verification order, and anything that lets a step be skipped or reordered;
- single-use tracking, the proximity gate, the SAS derivation and the UWB token handling;
- the transports, including anything that leaks the IBAN onto the air, where the profile puts only
  a first name.

**The wire profile itself is in scope even where this SDK implements it correctly.** If the spec
says something exploitable, that is the more valuable finding. The specification lives in
[`open-bank-oss`](https://github.com/JiRaska/open-bank-oss/blob/main/docs/specs/qrlesspay-v1.md)
and its threat model is published alongside it.

## What is already known, and not a finding

Stated so nobody spends time rediscovering what the threat model already admits:

- **Identity is at parity with a QR scan, not better.** The per-session key proves device continuity,
  not IBAN ownership. Bank attestation was deliberately dropped (spec §11) because every workable
  trust anchor is interbank coordination, and the profile's value is that it needs none.
- **RSSI is spoofable.** It is the required baseline and is necessary-not-sufficient; the payer's
  confirmation is what authorises a payment. UWB is the only cryptographic answer to a relay and is
  unavailable on most device pairs.
- **A first name is broadcast** while a request screen is open. That is a deliberate privacy/UX
  trade, documented and pending a DPIA.

## What this SDK has not been through

Say it plainly rather than let a badge imply otherwise:

- **No independent cryptographic review.** Nobody outside the implementing team has examined key
  handling, and the SDK is pre-1.0 for that reason.
- **No fuzzing.** The decoders are strict and unit-tested against a negative corpus; a fuzzer has
  never run against them.
- **Never exercised on real hardware.** Every transport is driven by loopback and unit tests. No
  two-device run has taken place.
