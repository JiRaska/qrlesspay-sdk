// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors.
import SwiftUI
import QRlessPay

/// Minimal integration of the QRlessPay SDK — one screen per role, and nothing else.
///
/// It exists for two reasons. It shows an adopting bank what the integration actually costs, which
/// is about as much code as is on this screen. And it is the vehicle for the two-device lab run the
/// rollout gates require: a demo app is a cleaner harness than a flag flipped inside a production
/// banking app, because it exercises the same SDK without putting a dormant capability one merge
/// away from shipping.
///
/// **No money moves here, and none could.** The SDK stops at a verified proposal; a real integration
/// continues into the bank's own confirmation screen and SCA. This one prints the SPAYD instead.
@main
struct QRlessPayExampleApp: App {
    var body: some Scene {
        WindowGroup { RootView() }
    }
}

struct RootView: View {
    var body: some View {
        NavigationStack {
            List {
                Section {
                    NavigationLink("Receive — advertise a request") { PayeeView() }
                    NavigationLink("Pay — find someone nearby") { PayerView() }
                } footer: {
                    Text(RootCopy.howToRun)
                }

                Section("What the profile guarantees here") {
                    ForEach(RootCopy.guarantees, id: \.self) { Bullet($0) }
                }
            }
            .navigationTitle("QRlessPay")
        }
    }
}

/// Hoisted for the same reason as in PayerView: concatenated literals inside a ViewBuilder are
/// what exhausts SwiftUI's type-checker.
private enum RootCopy {
    static let howToRun = """
        Run one role on each device. Both need Bluetooth on; neither needs a network, an account, \
        or anything at all on the other side of the radio.
        """
    static let guarantees = [
        "Only a first name goes on the air. The IBAN travels in the signed bundle, which the payer pulls over a direct connection.",
        "Every bundle is verified before it can become a proposal: signature, advert binding, expiry, single use.",
        "A weak signal is never offered as a tile. RSSI is the baseline and is spoofable, so it gates rather than proves.",
    ]
}

struct Bullet: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Text("•")
            Text(text).font(.footnote).foregroundStyle(.secondary)
        }
    }
}
