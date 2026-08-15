// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors.
#if canImport(CoreBluetooth)
import Foundation
import CoreBluetooth

/// CoreBluetooth implementation of [QRlessPayTransport].
///
/// Two independent radio roles, deliberately not sharing a manager:
///
/// - **Payee** — `CBPeripheralManager` advertises the beacon and serves the bundle from a read-only
///   characteristic. iOS carries **no service data** in a foreground advertisement, so the beacon
///   rides base64 in the local name; Android peers put the same bytes in service data, and a
///   conformant scanner must accept both forms (spec §2).
/// - **Payer** — `CBCentralManager` scans, then connects as a GATT client to read the bundle.
///
/// Only advertising is an exclusive resource: iOS runs one active advertisement at a time, so a
/// host app that already advertises something else must stop it before taking the payee role.
/// Scanning carries no such limit, which is why the two roles are separable here.
public final class CoreBluetoothTransport: NSObject, QRlessPayTransport {

    private let serviceUuid = CBUUID(string: QP.serviceUuid)
    private let bundleCharUuid = CBUUID(string: QP.bundleCharUuid)
    private let peerTtl: TimeInterval
    private let fetchTimeout: TimeInterval

    public init(peerTtl: TimeInterval = 6, fetchTimeout: TimeInterval = 8) {
        self.peerTtl = peerTtl
        self.fetchTimeout = fetchTimeout
        super.init()
    }

    // MARK: - Payee

    private var peripheralManager: CBPeripheralManager?
    private var servedBundle = Data()
    private var pendingBeaconBase64: String?
    private var serviceAdded = false

    public func startReceiving(beacon: [UInt8], bundle: [UInt8]) {
        servedBundle = Data(bundle)
        pendingBeaconBase64 = Data(beacon).base64EncodedString()
        if peripheralManager == nil {
            // Creating the manager is what triggers the OS Bluetooth permission prompt, so it is
            // deferred to here rather than done at init: constructing a transport must be free of
            // user-visible effects.
            peripheralManager = CBPeripheralManager(delegate: self, queue: nil)
        } else {
            beginAdvertising()
        }
    }

    public func stopReceiving() {
        pendingBeaconBase64 = nil
        servedBundle = Data()
        if peripheralManager?.state == .poweredOn { peripheralManager?.stopAdvertising() }
    }

    private func beginAdvertising() {
        guard let mgr = peripheralManager, mgr.state == .poweredOn, let beacon = pendingBeaconBase64 else { return }
        if !serviceAdded {
            let characteristic = CBMutableCharacteristic(type: bundleCharUuid, properties: [.read], value: nil, permissions: [.readable])
            let service = CBMutableService(type: serviceUuid, primary: true)
            service.characteristics = [characteristic]
            mgr.add(service)
            serviceAdded = true
        }
        mgr.stopAdvertising()
        mgr.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [serviceUuid],
            CBAdvertisementDataLocalNameKey: beacon,
        ])
    }

    // MARK: - Payer

    private var centralManager: CBCentralManager?
    private var onPeers: (([DiscoveredPeer]) -> Void)?
    private var peers: [String: (peer: DiscoveredPeer, seen: Date)] = [:]
    private var pruneTimer: Timer?

    private var connecting: [UUID: CheckedContinuation<[UInt8]?, Never>] = [:]
    private var discovered: [UUID: CBPeripheral] = [:]
    private var retained: [UUID: CBPeripheral] = [:]

    public func startDiscovery(onPeers: @escaping ([DiscoveredPeer]) -> Void) {
        self.onPeers = onPeers
        if centralManager == nil {
            centralManager = CBCentralManager(delegate: self, queue: nil)
        } else {
            beginScan()
        }
        pruneTimer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in self?.prune() }
    }

    public func stopDiscovery() {
        onPeers = nil
        pruneTimer?.invalidate()
        pruneTimer = nil
        peers.removeAll()
        if centralManager?.state == .poweredOn { centralManager?.stopScan() }
    }

    private func beginScan() {
        // Duplicates are wanted: RSSI is the proximity gate's only input, so a peer that moves
        // closer must be re-reported rather than remembered at the distance it was first seen.
        centralManager?.scanForPeripherals(withServices: [serviceUuid], options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
    }

    private func prune() {
        let cutoff = Date().addingTimeInterval(-peerTtl)
        let before = peers.count
        peers = peers.filter { $0.value.seen > cutoff }
        if peers.count != before { emit() }
    }

    private func emit() {
        onPeers?(peers.values.map(\.peer))
    }

    public func fetchBundle(peerId: String) async -> [UInt8]? {
        guard let uuid = UUID(uuidString: peerId), let peripheral = discovered[uuid], let central = centralManager else { return nil }

        let result: [UInt8]? = await withCheckedContinuation { continuation in
            connecting[uuid] = continuation
            retained[uuid] = peripheral      // CBCentralManager does not retain peripherals
            peripheral.delegate = self
            central.connect(peripheral, options: nil)

            DispatchQueue.main.asyncAfter(deadline: .now() + fetchTimeout) { [weak self] in
                // A GATT read that never lands must fail the tap rather than hang the screen.
                self?.finish(uuid, with: nil)
            }
        }
        return result
    }

    private func finish(_ uuid: UUID, with value: [UInt8]?) {
        guard let continuation = connecting.removeValue(forKey: uuid) else { return }
        if let peripheral = retained.removeValue(forKey: uuid) {
            // Connections are short-lived by design (spec §7): read, then drop.
            centralManager?.cancelPeripheralConnection(peripheral)
        }
        continuation.resume(returning: value)
    }
}

// MARK: - Payee delegate

extension CoreBluetoothTransport: CBPeripheralManagerDelegate {
    public func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        if peripheral.state == .poweredOn { beginAdvertising() }
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        guard request.characteristic.uuid == bundleCharUuid else {
            peripheral.respond(to: request, withResult: .attributeNotFound)
            return
        }
        guard request.offset <= servedBundle.count else {
            peripheral.respond(to: request, withResult: .invalidOffset)
            return
        }
        // The bundle exceeds a default ATT MTU, so CoreBluetooth reads it in offset chunks and the
        // peripheral must honour them — answering the whole value to a non-zero offset silently
        // corrupts the payload for the payer.
        request.value = servedBundle.subdata(in: request.offset..<servedBundle.count)
        peripheral.respond(to: request, withResult: .success)
    }
}

// MARK: - Payer delegate

extension CoreBluetoothTransport: CBCentralManagerDelegate, CBPeripheralDelegate {
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn, onPeers != nil { beginScan() }
    }

    public func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        guard let beacon = Self.beacon(from: advertisementData) else { return }
        discovered[peripheral.identifier] = peripheral
        let id = peripheral.identifier.uuidString
        peers[id] = (DiscoveredPeer(id: id, beacon: beacon, rssi: RSSI.intValue), Date())
        emit()
    }

    /// Accepts both carriages the spec requires: service data (Android) and the local name (iOS,
    /// which drops service data from a foreground advertisement).
    static func beacon(from advertisementData: [String: Any]) -> [UInt8]? {
        if let serviceData = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data],
           let data = serviceData[CBUUID(string: QP.dataUuid16)] ?? serviceData.values.first {
            return Array(data)
        }
        if let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String,
           let data = Data(base64Encoded: name) {
            return Array(data)
        }
        return nil
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([serviceUuid])
    }

    public func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        finish(peripheral.identifier, with: nil)
    }

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil, let service = peripheral.services?.first(where: { $0.uuid == serviceUuid }) else {
            finish(peripheral.identifier, with: nil)
            return
        }
        peripheral.discoverCharacteristics([bundleCharUuid], for: service)
    }

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil, let characteristic = service.characteristics?.first(where: { $0.uuid == bundleCharUuid }) else {
            finish(peripheral.identifier, with: nil)
            return
        }
        peripheral.readValue(for: characteristic)
    }

    public func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, let value = characteristic.value else {
            finish(peripheral.identifier, with: nil)
            return
        }
        finish(peripheral.identifier, with: Array(value))
    }
}
#endif
