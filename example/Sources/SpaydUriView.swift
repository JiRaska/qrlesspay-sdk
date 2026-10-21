// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors.
import SwiftUI
import UIKit
import QRlessPay

/// The camera path: what a bank app does when iOS hands it a `spayd://` (or `spayd-NNNN://`) URI.
///
/// Three outcomes, in the order the code checks them:
///  1. The URI is not a SPAYD-URI, or is malformed — refused, with the corpus reason shown.
///  2. It was addressed to this bank's relay scheme, or no other bank app is installed — straight
///     to the confirmation screen, exactly as a QR scanned inside the app would be.
///  3. It arrived on the common `spayd` scheme and other banks are installed — the relay chooser.
///     iOS opened *this* app because it registered `spayd`; the payer may bank elsewhere, and only
///     an app can offer that choice, because the OS will not.
///
/// **A relay forwards bytes, never trust.** The receiving app re-runs the same parse on what it is
/// handed; nothing here claims the payload was validated upstream.
@MainActor
final class SpaydUriModel: ObservableObject {
    /// Pilot stand-in. A real bank uses its own ČNB bank code, which is also the scheme it
    /// registers in `CFBundleURLTypes` (`spayd-<code>`), see `project.yml`.
    static let thisBankCode = "9999"

    /// Bank codes this pilot can relay to. Every entry must also appear in
    /// `LSApplicationQueriesSchemes` as `spayd-<code>`, or `canOpenURL` answers false regardless of
    /// what is installed — iOS refuses to answer about undeclared schemes.
    static let knownBankCodes = ["0100", "0300", "0600", "0800", "2010", "2700", "3030", "5500", "6210", "9999"]

    @Published var received: String?
    @Published var proposal: SpaydData?
    @Published var target: SpaydUri.Target?
    @Published var rejection: String?
    @Published var otherBanks: [String] = []

    func handle(_ url: URL) { handle(url.absoluteString) }

    func handle(_ raw: String) {
        received = raw
        proposal = nil
        target = nil
        rejection = nil
        otherBanks = []

        switch SpaydUri.parse(raw) {
        case let .rejected(reason):
            rejection = reason.rawValue
        case let .ok(unwrapped):
            target = unwrapped.target
            proposal = Spayd.parse(unwrapped.spayd)
            // Addressed to us by name, or on the common scheme: only the latter asks who else is here.
            if unwrapped.target.bankCode == nil {
                otherBanks = Self.installedRelayTargets().filter { $0 != Self.thisBankCode }
            }
        }
    }

    /// The URI to hand another bank: the same SPAYD, re-wrapped on that bank's relay scheme.
    func relayUrl(to bankCode: String) -> URL? {
        guard let raw = received, case let .ok(u) = SpaydUri.parse(raw),
              let s = try? SpaydUri.wrap(u.spayd, bankCode: bankCode) else { return nil }
        return URL(string: s)
    }

    func relay(to bankCode: String) {
        guard let url = relayUrl(to: bankCode) else { return }
        UIApplication.shared.open(url)
    }

    /// Which of the known banks are installed, by probing each relay scheme. Device-local, no
    /// network: the same "from the bytes and the device's own state" test as the rest of the profile.
    static func installedRelayTargets() -> [String] {
        knownBankCodes.filter { code in
            guard let scheme = SpaydUri.relayScheme(bankCode: code),
                  let probe = URL(string: "\(scheme)://v1/") else { return false }
            return UIApplication.shared.canOpenURL(probe)
        }
    }
}

struct SpaydUriView: View {
    @ObservedObject var model: SpaydUriModel
    @State private var pasted = ""

    var body: some View {
        List {
            Section {
                TextField("spayd://v1/SPD*1.0*ACC:…", text: $pasted)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(.footnote.monospaced())
                Button("Handle as if the camera opened it") { model.handle(pasted) }
                    .disabled(pasted.isEmpty)
            } header: {
                Text("Lab input")
            } footer: {
                Text(UriCopy.labHint)
            }

            if let r = model.rejection {
                Section("Refused") {
                    Text(r).font(.body.monospaced()).foregroundStyle(.red)
                    Text(UriCopy.refusedHint).font(.footnote).foregroundStyle(.secondary)
                }
            }

            if let p = model.proposal {
                Section("Payment (unverified, from the URI)") {
                    LabeledContent("Account", value: p.iban)
                    if let a = p.amount { LabeledContent("Amount", value: "\(a) \(p.currency ?? "")") }
                    if let n = p.recipientName { LabeledContent("Recipient", value: n) }
                    if let m = p.message { LabeledContent("Message", value: m) }
                    LabeledContent("Addressed to", value: model.target?.bankCode.map { "spayd-\($0)" } ?? "spayd (common)")
                }
            }

            if !model.otherBanks.isEmpty {
                Section {
                    Button("Pay here (\(SpaydUriModel.thisBankCode))") { model.otherBanks = [] }
                    ForEach(model.otherBanks, id: \.self) { code in
                        Button("Pay in bank \(code)") { model.relay(to: code) }
                    }
                } header: {
                    Text("Pay with")
                } footer: {
                    Text(UriCopy.relayHint)
                }
            }

            if let raw = model.received {
                Section("Received") {
                    Text(raw).font(.footnote.monospaced()).textSelection(.enabled)
                }
            }
        }
        .navigationTitle("Camera QR")
    }
}

private enum UriCopy {
    static let labHint = """
        On a device, scan a QR holding a spayd:// URI with the stock Camera app instead; iOS offers \
        "Open in QRlessPay" and this screen receives it. The field is for the simulator and for \
        the conformance corpus.
        """
    static let refusedHint = """
        Reasons are the conformance corpus names — a bank's scanner and this one must refuse the \
        same inputs for the same reasons.
        """
    static let relayHint = """
        iOS opened this app because it registered the common scheme; it does not offer a choice \
        between apps. The list is every known bank whose relay scheme is installed, and forwarding \
        hands over the same bytes — the receiving app verifies them again.
        """
}
