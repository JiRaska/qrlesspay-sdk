// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors.
import SwiftUI
import QRlessPay

/// The payer half: discover, tap, verify, and stop at the proposal.
@MainActor
final class PayerModel: ObservableObject {
    @Published var tiles: [NearbyTile] = []
    @Published var resolving: String?
    @Published var proposal: SpaydData?
    @Published var rejection: String?

    private let controller = QRlessPayController(transport: CoreBluetoothTransport())

    /// Names shown by more than one visible peer. The advert carries a first name and nothing else
    /// identifying, so the list genuinely cannot tell two Jiřís apart — and neither can an attacker
    /// advertising a victim's name with their own account. Saying so is the control that remains.
    var ambiguous: Set<String> {
        Set(ambiguousDisplayNames(tiles.map(\.firstName)))
    }

    func start() {
        controller.startDiscovery { [weak self] tiles in
            Task { @MainActor in self?.tiles = tiles }
        }
    }

    func stop() { controller.stopDiscovery() }

    func tap(_ tile: NearbyTile) {
        resolving = tile.peerId
        rejection = nil
        Task { @MainActor in
            let outcome = await controller.resolve(tile: tile)
            resolving = nil
            switch outcome {
            case let .ok(spayd):
                // A real integration hands this to its own confirmation screen and SCA. The SDK
                // deliberately ends here: no money moves in this protocol, and the payer's explicit
                // confirmation is the authorising act.
                proposal = Spayd.parse(spayd)
            case let .rejected(reason):
                rejection = reason
            }
        }
    }

    /// A refused tap must never be silent. It matters most for `replayed`: the payment already went
    /// through, so saying "try again" would invite paying twice.
    func message(for reason: String) -> String {
        switch reason {
        case "replayed": return "You have already paid this request."
        case "expired": return "That request expired. Ask them to show it again."
        case "fetch-failed": return "Could not reach that device. Move closer and try again."
        default: return "Could not verify that request."
        }
    }
}

/// String literals concatenated with `+` inside a ViewBuilder are what makes SwiftUI's type-checker
/// give up — the error only says "reasonable time" and names no cause. Hoisted here so the body
/// stays trivially checkable.
private enum Copy {
    static let ambiguousNames = """
        More than one person nearby is showing the same name. The list cannot tell them apart — \
        check the account number before you confirm.
        """
    static let searching = "Looking… hold the phones close."
    static let hintsAreNotAuthority = """
        Name and amount here are unverified display hints from an open broadcast. The real \
        recipient and amount come from the signed request, after you tap.
        """
}

struct PayerView: View {
    @StateObject private var model = PayerModel()

    var body: some View {
        List {
            ambiguityWarning
            nearbySection
            rejectionSection
        }
        .navigationTitle("Pay")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { model.start() }
        .onDisappear { model.stop() }
        .sheet(item: proposalBinding) { box in
            ProposalView(spayd: box.spayd)
        }
    }

    @ViewBuilder
    private var ambiguityWarning: some View {
        if !model.ambiguous.isEmpty {
            Section {
                Label(Copy.ambiguousNames, systemImage: "exclamationmark.triangle")
                    .font(.footnote)
            }
        }
    }

    private var nearbySection: some View {
        Section {
            if model.tiles.isEmpty {
                Text(Copy.searching).foregroundStyle(.secondary)
            }
            ForEach(model.tiles, id: \.peerId) { tile in
                Button { model.tap(tile) } label: {
                    TileRow(tile: tile, isResolving: model.resolving == tile.peerId)
                }
            }
        } header: {
            Text("Nearby")
        } footer: {
            Text(Copy.hintsAreNotAuthority)
        }
    }

    @ViewBuilder
    private var rejectionSection: some View {
        if let reason = model.rejection {
            Section {
                Text(model.message(for: reason)).foregroundStyle(.red)
            }
        }
    }

    private var proposalBinding: Binding<ProposalBox?> {
        Binding(
            get: { model.proposal.map { ProposalBox(spayd: $0) } },
            set: { if $0 == nil { model.proposal = nil } }
        )
    }
}

/// Extracted from the list body: SwiftUI's type-checker gives up on a nested stack with a
/// conditional inside a `ForEach` label, and the error it produces names only "reasonable time".
private struct TileRow: View {
    let tile: NearbyTile
    let isResolving: Bool

    private var amountText: String? {
        tile.amountMinor.map { String(format: "%.2f CZK", Double($0) / 100) }
    }

    var body: some View {
        HStack {
            VStack(alignment: .leading) {
                Text(tile.firstName)
                if let amountText {
                    Text(amountText).font(.footnote).foregroundStyle(.secondary)
                }
            }
            Spacer()
            if isResolving {
                ProgressView()
            } else {
                Text("\(tile.rssi) dBm").font(.caption2).foregroundStyle(.tertiary)
            }
        }
    }
}

private struct ProposalBox: Identifiable {
    let spayd: SpaydData
    var id: String { spayd.iban + (spayd.amount ?? "") }
}

/// Where a real bank's confirmation screen would be, with its own SCA behind it.
struct ProposalView: View {
    let spayd: SpaydData

    private var maskedIban: String {
        guard spayd.iban.count > 8 else { return spayd.iban }
        return spayd.iban.prefix(4) + "…" + spayd.iban.suffix(4)
    }

    var body: some View {
        VStack(spacing: 16) {
            Text("Verified request").font(.headline)
            LabeledContent("To", value: spayd.recipientName ?? "—")
            // Shown prominently and on purpose: with bank attestation out of the profile and VoP
            // absent on the CZ domestic rail, the masked account number plus this confirmation is
            // what the payer's decision actually rests on.
            LabeledContent("Account", value: maskedIban)
            LabeledContent("Amount", value: "\(spayd.amount ?? "—") \(spayd.currency ?? "")")

            Text(
                "A real integration would run SCA from here. This example stops: the SDK never "
                + "moves money, and the payer's confirmation is the authorising act."
            )
            .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }
        .padding()
        .presentationDetents([.medium])
    }
}
