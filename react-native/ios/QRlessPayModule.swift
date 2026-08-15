// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors.
import Foundation
import React
import QRlessPay

/// iOS bridge. Owns one `QRlessPayController` over the real CoreBluetooth transport and forwards
/// results to JavaScript.
///
/// Nothing here decides anything: no verification, no filtering, no re-ordering. The controller
/// has already applied the proximity gate and the full §3 verification order, and duplicating any
/// of that on this side would create a second place for it to be wrong.
@objc(QRlessPay)
final class QRlessPayModule: RCTEventEmitter {

    private let controller = QRlessPayController(transport: CoreBluetoothTransport())
    private var tiles: [String: NearbyTile] = [:]
    private var hasListeners = false

    override static func requiresMainQueueSetup() -> Bool { false }
    override func supportedEvents() -> [String] { ["qrlesspay:tiles"] }
    override func startObserving() { hasListeners = true }
    override func stopObserving() { hasListeners = false }

    // MARK: Payee

    @objc(startReceiving:spayd:amountMinor:resolver:rejecter:)
    func startReceiving(
        _ firstName: String,
        spayd: String,
        amountMinor: NSNumber?,
        resolver resolve: RCTPromiseResolveBlock,
        rejecter reject: RCTPromiseRejectBlock
    ) {
        controller.startReceiving(firstName: firstName, spayd: spayd, amountMinor: amountMinor?.intValue)
        resolve(nil)
    }

    @objc(stopReceiving:rejecter:)
    func stopReceiving(resolver resolve: RCTPromiseResolveBlock, rejecter reject: RCTPromiseRejectBlock) {
        controller.stopReceiving()
        resolve(nil)
    }

    // MARK: Payer

    @objc(startDiscovery:rejecter:)
    func startDiscovery(resolver resolve: @escaping RCTPromiseResolveBlock, rejecter reject: RCTPromiseRejectBlock) {
        controller.startDiscovery { [weak self] tiles in
            guard let self else { return }
            // The native tile is retained here and only its display fields cross the bridge. The
            // beacon bytes stay on this side deliberately — JavaScript has no use for them that
            // is not a re-implementation of verification.
            self.tiles = Dictionary(uniqueKeysWithValues: tiles.map { ($0.peerId, $0) })
            guard self.hasListeners else { return }
            self.sendEvent(withName: "qrlesspay:tiles", body: tiles.map {
                ["peerId": $0.peerId, "firstName": $0.firstName, "amountMinor": $0.amountMinor as Any, "rssi": $0.rssi]
            })
        }
        resolve(nil)
    }

    @objc(stopDiscovery:rejecter:)
    func stopDiscovery(resolver resolve: RCTPromiseResolveBlock, rejecter reject: RCTPromiseRejectBlock) {
        controller.stopDiscovery()
        tiles.removeAll()
        resolve(nil)
    }

    @objc(resolve:resolver:rejecter:)
    func resolve(
        _ peerId: String,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard let tile = tiles[peerId] else {
            // A tile that has aged out of the list is an ordinary outcome, not an exception: the
            // peer simply stopped advertising. Rejecting the promise would make the host app treat
            // it as a crash-worthy error rather than a tap to explain.
            resolve(["ok": false, "reason": "fetch-failed"])
            return
        }
        Task {
            switch await self.controller.resolve(tile: tile) {
            case .ok(let spayd):
                resolve(["ok": true, "spayd": spayd])
            case .rejected(let reason):
                resolve(["ok": false, "reason": reason])
            }
        }
    }
}
