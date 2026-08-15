<!-- SPDX-License-Identifier: Apache-2.0 -->
# QRlessPay example

A minimal iOS integration: one screen per role and nothing else. It exists for two reasons.

**It shows what adopting costs.** The whole integration is `PayeeView` and `PayerView` — roughly a
hundred lines, most of it copy explaining the profile to the user rather than driving it.

**It is the two-device lab harness.** The rollout gates need a real run on real hardware, and a demo
app is a cleaner vehicle than a flag flipped inside a production banking app: it exercises the same
SDK without leaving a dormant capability one merge away from shipping.

No money moves and none could — the SDK stops at a verified proposal, and this app prints it.

## Running it

```
brew install xcodegen        # once
cd example && xcodegen generate
open QRlessPayExample.xcodeproj
```

The `.xcodeproj` is generated and gitignored, same as the app this SDK was extracted from.

Build to **two physical devices** — one takes *Receive*, the other *Pay*. The simulator cannot do
this: iOS Simulator has no Bluetooth hardware, so `CBCentralManager` never leaves `.unsupported` and
no peer will ever appear.

## What to look for

| | Expected |
|---|---|
| Payee advertising, payer scanning | A tile appears with the first name and amount, and an RSSI reading |
| Move the devices apart | The tile disappears below the gate (−70 dBm), rather than staying and being tappable |
| Tap the tile | A verified proposal with the **full** IBAN, which was never broadcast — only the name was |
| Tap the same tile again | Refused as *"You have already paid this request"*, not silence and not "try again" |
| Two payees with the same first name | A warning above the list that the names cannot be told apart |
| Close the Receive screen | Advertising stops; the tile vanishes on the other device within a few seconds |

## The one thing this run is really for

Both roles create their own `CBCentralManager`, and **this app has never run two of them at once
against a real radio**. Scanning is not an exclusive resource the way advertising is, so it is
expected to work — that word is doing real work, and only this run removes it.

## Permissions

`NSBluetoothAlwaysUsageDescription` and `NSBluetoothPeripheralUsageDescription` are in the generated
Info.plist. Without them iOS does not warn — it **crashes** the moment a manager is constructed.
