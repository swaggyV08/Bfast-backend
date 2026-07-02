import Foundation
import Flutter
import CoreBluetooth
import os.log

/// iOS GATT server — protocol-identical to Android's GattServerHandler.kt.
///
/// Service:       FFF0
/// HandshakeChar: FFF2  WRITE + WRITE_NO_RESPONSE   (sender → receiver)
/// ResponseChar:  FFF3  READ + NOTIFY                (receiver → sender via poll;
///                                                    NOTIFY used only for disconnect detection)
///
/// Flutter ↔ Native (MethodChannel "com.bfast.app/gatt_server"):
///   startGattServer()                      → build service, start advertising
///   stopGattServer()                       → tear down everything
///   setSessionActive(active: Bool)         → gate new connections when in session
///   setResponse(bytes: [Int])              → write bytes into FFF3 for sender to poll
///
/// Flutter ↔ Native (EventChannel "com.bfast.app/gatt_server/events"):
///   { type: "connected",      deviceAddress: String }
///   { type: "disconnected",   deviceAddress: String }
///   { type: "data", charType: "handshake", data: [Int] }
///   { type: "busy_rejected",  deviceAddress: String }
class GattServerHandler: NSObject {

    // ── GATT UUIDs ───────────────────────────────────────────────────────────
    static let serviceUUID   = CBUUID(string: "FFF0")
    static let handshakeUUID = CBUUID(string: "FFF2")
    static let responseUUID  = CBUUID(string: "FFF3")
    private static let tag   = "GattServerHandler"

    // ── Flutter channels ─────────────────────────────────────────────────────
    private let methodChannel: FlutterMethodChannel
    private let eventChannel:  FlutterEventChannel
    private var eventSink: FlutterEventSink?

    // ── CoreBluetooth ────────────────────────────────────────────────────────
    private var peripheralManager: CBPeripheralManager?
    private var responseChar: CBMutableCharacteristic?

    // ── Session state ────────────────────────────────────────────────────────
    private var connectedCentralId: String?
    private var sessionActive = false
    private var pendingStart  = false

    // ── Advertising info (set by BleAdvertiseHandler before startGattServer) ─
    var advertisingDisplayName = ""
    var advertisingDeviceId    = ""

    // ── Watchdog: fires if Android sender drops without unsubscribing ────────
    private var watchdogTimer: Timer?
    private static let watchdogInterval: TimeInterval = 60

    // ── Init ─────────────────────────────────────────────────────────────────

    init(messenger: FlutterBinaryMessenger) {
        methodChannel = FlutterMethodChannel(
            name: "com.bfast.app/gatt_server",
            binaryMessenger: messenger
        )
        eventChannel = FlutterEventChannel(
            name: "com.bfast.app/gatt_server/events",
            binaryMessenger: messenger
        )
        super.init()
        methodChannel.setMethodCallHandler(handleMethod)
        eventChannel.setStreamHandler(self)
    }

    // ── MethodChannel ────────────────────────────────────────────────────────

    private func handleMethod(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {

        case "startGattServer":
            startServer()
            result(nil)

        case "stopGattServer":
            stopServer()
            result(nil)

        case "setSessionActive":
            let active = (call.arguments as? [String: Any])?["active"] as? Bool ?? false
            sessionActive = active
            if !active {
                // Allow a new connection once session ends / receiver resets
                connectedCentralId = nil
            }
            log("setSessionActive → \(active)")
            result(nil)

        case "setResponse":
            let ints = (call.arguments as? [String: Any])?["bytes"] as? [Int] ?? []
            let data = Data(ints.map { UInt8(clamping: $0) })
            responseChar?.value = data
            if ints.count >= 2 {
                log("setResponse \(ints.count)B  seq=\(ints[0])  msgType=0x\(String(ints[1], radix: 16))")
            }
            result(nil)

        default:
            result(FlutterMethodNotImplemented)
        }
    }

    // ── Server lifecycle ─────────────────────────────────────────────────────

    func startServer() {
        log("startServer requested")
        if peripheralManager == nil {
            // Dedicated queue keeps BLE callbacks off the main thread
            let queue = DispatchQueue(label: "com.bfast.gatt.peripheral", qos: .userInitiated)
            peripheralManager = CBPeripheralManager(delegate: self, queue: queue)
        }
        if peripheralManager?.state == .poweredOn {
            buildAndAddService()
        } else {
            pendingStart = true
        }
    }

    private func buildAndAddService() {
        guard let pm = peripheralManager, pm.state == .poweredOn else { return }
        pm.stopAdvertising()
        pm.removeAllServices()

        // Reset session state for a fresh server instance
        connectedCentralId = nil
        sessionActive      = false
        cancelWatchdog()

        // FFF2: WRITE + WRITE_NO_RESPONSE  (sender writes all protocol messages here)
        let hsChar = CBMutableCharacteristic(
            type:        GattServerHandler.handshakeUUID,
            properties:  [.write, .writeWithoutResponse],
            value:       nil,
            permissions: [.writeable]
        )

        // FFF3: READ + NOTIFY
        //   READ  — sender polls every 150 ms (matching Android poll pattern)
        //   NOTIFY — used purely for iOS-sender disconnect detection;
        //            the server never calls updateValue, so no data flows via notify
        let respChar = CBMutableCharacteristic(
            type:        GattServerHandler.responseUUID,
            properties:  [.read, .notify],
            value:       nil,
            permissions: [.readable]
        )
        responseChar = respChar

        let service = CBMutableService(type: GattServerHandler.serviceUUID, primary: true)
        service.characteristics = [hsChar, respChar]
        pm.add(service)
        // Advertising starts in peripheralManager(_:didAdd:error:)
    }

    func stopServer() {
        cancelWatchdog()
        peripheralManager?.stopAdvertising()
        peripheralManager?.removeAllServices()
        connectedCentralId = nil
        sessionActive      = false
        responseChar       = nil
        pendingStart       = false
        log("stopServer: done")
    }

    func cleanup() {
        stopServer()
        peripheralManager = nil
    }

    // ── Advertising ──────────────────────────────────────────────────────────

    private func startAdvertising() {
        guard let pm = peripheralManager, pm.state == .poweredOn else { return }
        let safe = String(advertisingDisplayName
            .replacingOccurrences(of: "_", with: "")
            .prefix(12))
        let localName = "BFAST_\(safe.isEmpty ? "Device" : safe)"
        pm.startAdvertising([
            CBAdvertisementDataLocalNameKey:    localName,
            CBAdvertisementDataServiceUUIDsKey: [GattServerHandler.serviceUUID],
        ])
        log("advertising as '\(localName)'")
    }

    // ── Watchdog: fallback disconnect for Android senders (no NOTIFY sub) ───

    private func resetWatchdog() {
        // Timer must be created and invalidated on the same thread with an active
        // RunLoop. The peripheral delegate queue has no RunLoop, so cross-thread
        // invalidation causes EXC_BAD_ACCESS. Dispatch to main — the only queue
        // guaranteed to have a RunLoop at all times.
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.watchdogTimer?.invalidate()
            self.watchdogTimer = Timer.scheduledTimer(
                withTimeInterval: GattServerHandler.watchdogInterval,
                repeats: false
            ) { [weak self] _ in
                guard let self, let id = self.connectedCentralId else { return }
                self.log("watchdog: no writes for \(Int(GattServerHandler.watchdogInterval))s — emitting disconnected")
                self.connectedCentralId = nil
                self.emit(["type": "disconnected", "deviceAddress": id])
            }
        }
    }

    private func cancelWatchdog() {
        DispatchQueue.main.async { [weak self] in
            self?.watchdogTimer?.invalidate()
            self?.watchdogTimer = nil
        }
    }

    // ── Event emission ───────────────────────────────────────────────────────

    private func emit(_ event: [String: Any]) {
        DispatchQueue.main.async { [weak self] in
            self?.eventSink?(event)
        }
    }

    private static let logger = OSLog(
        subsystem: Bundle.main.bundleIdentifier ?? "com.bfast.app",
        category: "GattServer"
    )

    private func log(_ msg: String) {
        os_log("%{private}@", log: GattServerHandler.logger, type: .debug, msg)
    }
}

// ── CBPeripheralManagerDelegate ───────────────────────────────────────────────

extension GattServerHandler: CBPeripheralManagerDelegate {

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        switch peripheral.state {
        case .poweredOn:
            log("poweredOn")
            if pendingStart {
                pendingStart = false
                buildAndAddService()
            } else if responseChar != nil && !peripheral.isAdvertising {
                // Radio regained after AirDrop/system preemption — restart advertising.
                log("radio regained — restarting advertising")
                startAdvertising()
            }
        case .poweredOff:
            log("poweredOff")
        case .unauthorized:
            log("unauthorized — add NSBluetoothAlwaysUsageDescription to Info.plist")
        case .unsupported:
            log("unsupported — BLE peripheral mode unavailable on this device")
        default:
            break
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           didAdd service: CBService, error: Error?) {
        if let err = error {
            log("failed to add service FFF0: \(err.localizedDescription)")
            return
        }
        log("service FFF0 added (FFF2=write, FFF3=read+notify) — starting advertising")
        startAdvertising()
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager,
                                              error: Error?) {
        if let err = error {
            log("advertising failed: \(err.localizedDescription) — will retry when radio is available")
            // Mark pendingStart so peripheralManagerDidUpdateState restarts advertising
            // once the system (AirDrop, Handoff, etc.) releases the BLE radio slot.
            pendingStart = true
        } else {
            pendingStart = false
            log("advertising started")
        }
    }

    // ── WRITE requests on FFF2 ───────────────────────────────────────────────

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           didReceiveWrite requests: [CBATTRequest]) {
        var resultCode = CBATTError.Code.success
        var shouldRespond = false

        for req in requests {
            guard req.characteristic.uuid == GattServerHandler.handshakeUUID else { continue }
            let centralId = req.central.identifier.uuidString

            // Track whether this request expects an ATT response
            if req.characteristic.properties.contains(.write) {
                shouldRespond = true
            }

            // ── Connection gating ──────────────────────────────────────────
            if connectedCentralId == nil {
                if sessionActive {
                    log("busy (session locked): rejecting \(centralId)")
                    emit(["type": "busy_rejected", "deviceAddress": centralId])
                    resultCode = .insufficientAuthentication
                    continue
                }
                // Accept this central
                connectedCentralId = centralId
                responseChar?.value = Data()  // clean FFF3 for new session
                log("connected: \(centralId)")
                emit(["type": "connected", "deviceAddress": centralId])
                resetWatchdog()

            } else if connectedCentralId != centralId {
                log("busy (different central): rejecting \(centralId)")
                emit(["type": "busy_rejected", "deviceAddress": centralId])
                resultCode = .insufficientAuthentication
                continue

            } else {
                // Known central — keep session alive
                resetWatchdog()
            }

            // ── Forward payload to Dart ────────────────────────────────────
            let bytes = [UInt8](req.value ?? Data())
            let msgByte = bytes.first.map { Int($0) } ?? -1
            log("write \(bytes.count)B  msgType=0x\(String(msgByte, radix: 16))")
            emit([
                "type":     "data",
                "charType": "handshake",
                "data":     bytes.map { Int($0) },
            ])
        }

        // Respond exactly once to the whole batch (Apple docs requirement)
        if shouldRespond, let first = requests.first {
            peripheral.respond(to: first, withResult: resultCode)
        }
    }

    // ── READ requests on FFF3 ────────────────────────────────────────────────

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           didReceiveRead request: CBATTRequest) {
        guard request.characteristic.uuid == GattServerHandler.responseUUID else {
            peripheral.respond(to: request, withResult: .requestNotSupported)
            return
        }
        let value  = responseChar?.value ?? Data()
        let offset = request.offset
        guard offset <= value.count else {
            peripheral.respond(to: request, withResult: .invalidOffset)
            return
        }
        request.value = value.subdata(in: offset..<value.count)
        peripheral.respond(to: request, withResult: .success)
        if !value.isEmpty {
            log("READ FFF3: \(value.count)B  seq=\(value[0])  msgType=0x\(value.count > 1 ? String(value[1], radix: 16) : "?")")
        }
    }

    // ── NOTIFY subscriptions (iOS sender → iOS receiver fast disconnect) ─────

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           central: CBCentral,
                           didSubscribeTo characteristic: CBCharacteristic) {
        guard characteristic.uuid == GattServerHandler.responseUUID else { return }
        let centralId = central.identifier.uuidString
        log("central \(centralId) subscribed to FFF3 NOTIFY")

        // If first contact from this central via subscribe (before first write)
        if connectedCentralId == nil {
            guard !sessionActive else {
                log("busy (session locked): ignoring subscribe from \(centralId)")
                return
            }
            connectedCentralId = centralId
            responseChar?.value = Data()
            log("connected (via subscribe): \(centralId)")
            emit(["type": "connected", "deviceAddress": centralId])
            resetWatchdog()
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           central: CBCentral,
                           didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard characteristic.uuid == GattServerHandler.responseUUID else { return }
        let centralId = central.identifier.uuidString
        guard connectedCentralId == centralId else { return }
        log("central \(centralId) unsubscribed from FFF3 — disconnected")
        cancelWatchdog()
        connectedCentralId = nil
        emit(["type": "disconnected", "deviceAddress": centralId])
    }
}

// ── FlutterStreamHandler ──────────────────────────────────────────────────────

extension GattServerHandler: FlutterStreamHandler {

    func onListen(withArguments arguments: Any?,
                  eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        eventSink = events
        log("EventChannel: listening")
        return nil
    }

    func onCancel(withArguments arguments: Any?) -> FlutterError? {
        eventSink = nil
        log("EventChannel: cancelled")
        return nil
    }
}
