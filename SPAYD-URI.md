# SPAYD-URI — a SPAYD the phone's own camera can hand to a bank app

Status: **Pilot** (v1 of the envelope). Implemented in both cores, run by the conformance corpus,
wired into the example app. Not yet adopted by any bank other than the pilot stand-in.

## The problem, in one sentence

A QR holding `SPD*1.0*ACC:…` is readable only from *inside* a bank app, because every OS camera
treats it as text: shown, copied, never offered to an app. The same QR as a URI with a registered
scheme reaches the app-chooser path on both platforms, like every other QR people scan today.

## The envelope

```
spayd://v1/SPD*1.0*ACC:CZ6508000000192000145399*AM:250.00*CC:CZK*MSG:Faktura%20123
└─┬─┘   └┬┘ └──────────────────────────────┬──────────────────────────────────────┘
scheme  version              the SPAYD, verbatim, percent-encoded only where a URI forbids the byte
```

- **Scheme** `spayd` — the common scheme every bank app registers. Or `spayd-<bank code>`, the
  per-bank relay scheme (four ČNB digits: `spayd-0800`, `spayd-0300`, …), see *Choosing the app*.
- **Version segment** `v1` — the authority. Bumps only if this encoding changes.
- **Payload** — the SPAYD descriptor, unchanged. Raw: `A–Z a–z 0–9 - . _ ~ * : + ,`. Everything
  else is `%XX` of its UTF-8 bytes, upper-case hex. A typical descriptor grows by the prefix alone.

Decoders accept, but producers never emit, three looser spellings: `spayd:SPD*…` (opaque, no
slashes), `spayd://SPD*…` (no version segment) and any letter case in the scheme and hex digits.
They are in the corpus under `tolerated`.

**This changes nothing about what a SPAYD is.** No backend, no lookup, no registry — the invariant
of the wire spec (§11) holds: the URI is unwrapped on the device and the bytes inside it are exactly
what a printed QR carries. Where the SPAYD itself has a trust story (none; the payer confirms on a
screen the bank controls), the URI inherits it unchanged.

## Choosing the app — the two platforms do not behave alike, and no envelope can hide it

**Android.** Every bank registers an `intent-filter` for the `spayd` scheme. When more than one
installed app matches, the system shows its own chooser. That is the whole feature, and the OS
provides it.

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="spayd" />
    <data android:scheme="spayd-9999" />   <!-- this bank's relay scheme -->
</intent-filter>
```

**iOS.** The Camera app offers "Open in <app>" for a custom scheme — for **one** app. When several
register the same scheme iOS picks one, with no chooser and no documented rule. Nothing at the OS
level changes that, so the choice has to be offered by the app that was opened:

1. Every bank registers `spayd` **and** its own `spayd-<bank code>` in `CFBundleURLTypes`, and lists
   the other banks' relay schemes in `LSApplicationQueriesSchemes` (iOS answers `canOpenURL` only
   about declared schemes, whatever is installed — the declared list *is* the set of banks a chooser
   can ever show).
2. On a URI received on the common scheme, the app probes `canOpenURL("spayd-NNNN://v1/")` for each
   known bank. One hit (itself) → straight to confirmation, as today. More → a chooser: *Pay with:
   this bank / bank A / bank B*.
3. Choosing another bank re-wraps the **same SPAYD** on that bank's relay scheme and opens it. The
   receiving app runs the same parse again; a relay forwards bytes, never trust.

A URI received on the relay scheme skips the chooser — it was addressed by name. Android apps
register the relay scheme too, so one relay implementation serves both platforms (useful when a
bank's own UI wants to offer the hand-off; the system chooser already covers the common case).

The cost, stated plainly: every bank has to implement the relay, or its app silently keeps the scan
whenever iOS happens to pick it. The bank-code list and the two schemes belong in the standard (a
ČBA extension to SPAYD), not in any one bank's code.

## Non-breaking adoption

The QR content *must* change — the camera reacts to nothing else — so a scanner that still does
`startsWith("SPD*")` cannot read the new form. The only safe order:

1. **Decoders first.** Every scanner calls `SpaydUri.unwrap(raw)`, which returns the SPAYD for both
   the bare and the URI form. Ship it; wait for adoption.
2. **Producers emit both**, selectable, defaulting to the bare form. Every QR already printed on an
   invoice keeps working forever.
3. **Flip the default** to the URI once scanner telemetry says the old versions are gone.

QRlessPay over BLE keeps carrying the bare SPAYD — a radio does not need a scheme — and its SDK
decoder accepts both, so a payee can paste either into the payee screen.

## Security

Same model as a printed QR: the content is untrusted until the payer confirms it on a screen the
bank controls. Two additions, both device-local:

- The receiver **re-parses after a relay**. The chooser app is just another untrusted source.
- The decoder refuses what no honest producer emits, for a stated reason the corpus pins:
  a raw byte a URI forbids (`illegal-character`), a control character smuggled in through an escape
  (`%00` and friends — also `illegal-character`; a NUL would otherwise surface as a database error far
  from here), a malformed escape or non-UTF-8 bytes (`bad-percent-encoding`), an unknown version or
  host (`bad-version`), a bank code that is not four digits (`bad-bank-code`), and a payload that is
  not a SPAYD (`not-spayd`).

A look-alike scheme is refused by the parser (`spaydx://` as `not-spayd-uri`, `spayd-pay://` as
`bad-bank-code`), and by the OS before that: only registered schemes are offered.

## API

Swift (`SpaydUri`) and Kotlin (`tech.qrlesspay.sdk.SpaydUri`), identical shape:

| Call | Purpose |
|---|---|
| `wrap(spayd, bankCode: nil)` | Producer. Refuses anything `Spayd.parse` refuses, and a bank code that is not four digits. |
| `unwrap(raw)` | **The one call a scanner makes.** Bare `SPD*…` or any accepted URI form → the SPAYD; otherwise nil. |
| `parse(uri)` | Strict, with the refusal reason; reports which target (common or `bankCode`) the URI was addressed to. |
| `relayScheme(bankCode:)` | `spayd-NNNN` for a valid code, nil otherwise — so a bank list cannot produce a scheme nobody registered. |

The React Native binding does not expose it yet; `unwrap` is pure string work and belongs in the
TypeScript package rather than behind a bridge.

## Conformance

`conformance/spayd-uri-vectors.json`, generated by the Kotlin side
(`ZzSpaydUriVectorGenerator`, same no-op-unless-asked contract as the other generators) and run
by both cores: `positive` (wrap → committed URI → same bytes back, with the target), `tolerated`
(the looser input spellings → the same descriptor), `negative` (each refused, **for the stated
reason**). The reason is asserted, not just the rejection, for the same cause as the bundle
corpus: two scanners refusing the same QR for different reasons disagree the moment either tells
the payer why.

## Piloting it

The example app registers `spayd` and `spayd-9999` (a stand-in bank code) and has a *Camera QR*
screen. On a device:

1. Print or display a QR of `spayd://v1/SPD*1.0*ACC:CZ6508000000192000145399*AM:250.00*CC:CZK`.
2. Point the stock Camera app at it. iOS shows "Open in QRlessPay"; tap it. The screen shows the
   parsed payment and, if another app registering a known relay scheme is installed, the chooser.
3. Install the example twice under two bundle ids with different `spayd-NNNN` schemes to see the
   relay end to end — or paste a `spayd-9999://…` URI in the lab field to see the addressed path
   skip the chooser.

On Android there is no example app yet; the Kotlin core carries `SpaydUri` and the manifest
snippet above is the whole integration. The system chooser needs two installed apps registering
the scheme. Vendor camera apps differ in whether they hand a custom scheme to the system at all,
and nothing here has measured that yet — do it on the devices the pilot targets before promising
the flow, and record which camera app, not just which phone.
