// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors.
import SwiftUI
import QRlessPay

/// The payee half: mint a request and advertise it while this screen is open.
@MainActor
final class PayeeModel: ObservableObject {
    @Published var firstName = "Jiří"
    @Published var iban = "CZ6508000000192000145399"
    @Published var amount = "250.00"
    @Published var isAdvertising = false

    private let controller = QRlessPayController(transport: CoreBluetoothTransport())

    /// SPAYD is just a string — that is the whole reason this profile interoperates. QRlessPay
    /// carries the same descriptor a QR code would, over a different medium.
    var spayd: String {
        "SPD*1.0*ACC:\(iban)*AM:\(amount)*CC:CZK*RN:\(firstName)"
    }

    func start() {
        let minor = Int((Double(amount) ?? 0) * 100)
        controller.startReceiving(firstName: firstName, spayd: spayd, amountMinor: minor > 0 ? minor : nil)
        isAdvertising = true
    }

    /// Stopping is not tidiness. An advert left running keeps broadcasting a first name after the
    /// user believes they closed the screen, which is the one privacy promise this profile makes.
    func stop() {
        controller.stopReceiving()
        isAdvertising = false
    }
}

struct PayeeView: View {
    @StateObject private var model = PayeeModel()

    var body: some View {
        Form {
            Section("Request") {
                TextField("First name", text: $model.firstName)
                TextField("IBAN", text: $model.iban).font(.system(.body, design: .monospaced))
                TextField("Amount", text: $model.amount).keyboardType(.decimalPad)
            }

            Section {
                Button(model.isAdvertising ? "Stop" : "Start advertising") {
                    model.isAdvertising ? model.stop() : model.start()
                }
                .tint(model.isAdvertising ? .red : .accentColor)
            } footer: {
                if model.isAdvertising {
                    Label(
                        "Broadcasting your first name and a one-off session id. Your account number "
                        + "is not on the air — a payer gets it only by connecting, and only signed.",
                        systemImage: "antenna.radiowaves.left.and.right"
                    )
                }
            }

            Section("On the air") {
                LabeledContent("Name", value: model.firstName)
                LabeledContent("Account", value: "not broadcast")
                LabeledContent("Session", value: model.isAdvertising ? "live, expires in 90 s" : "—")
            }
            .font(.footnote)
        }
        .navigationTitle("Receive")
        .navigationBarTitleDisplayMode(.inline)
        // Dispose stops the radio: leaving by any route — back, app switcher, a phone call — must
        // end the broadcast, not just tapping the button.
        .onDisappear { model.stop() }
    }
}
