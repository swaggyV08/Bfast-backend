import Foundation
import Flutter
import CoreBluetooth
import os.log

/// BLE peripheral advertising for the SENDER role on iOS.
///
/// RECEIVER role: advertising is handled by GattServerHandler (it starts when
/// the GATT service is added). This handler stores the device name/ID so
/// GattServerHandler can use it, then returns immediately.
///
/// SENDER role: advertise local name "BFAST_<displayName>" + service UUID FFF0.
/// iOS foreground advertising cannot include arbitrary manufacturer data
/// (OS restriction), so we use local name + service UUID as the discovery signal.
/// The scanner's fallback path in ble_service.dart already handles name-prefix
/// matching when manufacturer data is absent.
///
/// MethodChannel: "com.bfast.app/ble_advertise"
///   startAdvertising(deviceId: String, displayName: String, isSender: Bool)
///   stopAdvertising()
class BleAdvertiseHandler: NSObject {

    private static let tag = "BleAdvertiseHandler"

    private let methodChannel: FlutterMethodChannel
    private weak var gattHandler: GattServerHandler?

    // Dedicated CBPeripheralManager for sender-only advertising.
    // Kept separate from GattServerHandler's manager to avoid state conflicts.
    private var senderPeripheralMgr: CBPeripheralManager?
    private var pendingSenderAd: (deviceId: String, displayName: String)?

    private static let serviceUUID = GattServerHandler.serviceUUID

    // ── Init ─────────────────────────────────────────────────────────────────

    init(messenger: FlutterBinaryMessenger, gattHandler: GattServerHandler) {
        self.methodChannel = FlutterMethodChannel(
            name: "com.bfast.app/ble_advertise",
            binaryMessenger: messenger
        )
        self.gattHandler = gattHandler
        super.init()
        methodChannel.setMethodCallHandler(handle)
    }

    // ── MethodChannel handler ─────────────────────────────────────────────────

    private func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {

        case "startAdvertising":
            guard let args        = call.arguments as? [String: Any],
                  let deviceId    = args["deviceId"]    as? String,
                  let displayName = args["displayName"] as? String
            else {
                result(FlutterError(code: "INVALID_ARGS",
                                    message: "deviceId and displayName required",
                                    details: nil))
                return
            }
            let isSender = args["isSender"] as? Bool ?? false
            startAdvertising(deviceId: deviceId, displayName: displayName, isSender: isSender)
            result(nil)

        case "stopAdvertising":
            stopAdvertising()
            result(nil)

        default:
            result(FlutterMethodNotImplemented)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    func startAdvertising(deviceId: String, displayName: String, isSender: Bool) {
        if isSender {
            startSenderAdvertising(deviceId: deviceId, displayName: displayName)
        } else {
            // Receiver role: GattServerHandler owns advertising.
            // Store the name so it can build the correct local name when the
            // service is added (startGattServer is called right after this).
            gattHandler?.advertisingDeviceId    = deviceId
            gattHandler?.advertisingDisplayName = displayName
            log("receiver ad info stored → GattServerHandler will advertise as 'BFAST_\(displayName.prefix(12))'")
        }
    }

    func stopAdvertising() {
        senderPeripheralMgr?.stopAdvertising()
        senderPeripheralMgr = nil
        pendingSenderAd     = nil
        log("sender advertising stopped")
    }

    // ── Sender advertising ───────────────────────────────────────────────────

    private func startSenderAdvertising(deviceId: String, displayName: String) {
        pendingSenderAd = (deviceId, displayName)
        if senderPeripheralMgr == nil {
            // Use a dedicated background queue so AirDrop UI activity on the main
            // thread cannot delay our CBPeripheralManagerDelegate callbacks.
            let queue = DispatchQueue(label: "com.bfast.ble.sender", qos: .userInitiated)
            senderPeripheralMgr = CBPeripheralManager(delegate: self, queue: queue)
        }
        if senderPeripheralMgr?.state == .poweredOn {
            doAdvertiseSender()
        }
        // Otherwise waits for peripheralManagerDidUpdateState callback
    }

    private func doAdvertiseSender() {
        guard let pm = senderPeripheralMgr, pm.state == .poweredOn,
              let pending = pendingSenderAd else { return }
        pm.stopAdvertising()
        let safe = String(pending.displayName
            .replacingOccurrences(of: "_", with: "")
            .prefix(12))
        let localName = "BFAST_\(safe.isEmpty ? "User" : safe)"
        // iOS foreground advertising: local name + service UUID only.
        // Manufacturer data (role byte) is suppressed by the OS in foreground.
        pm.startAdvertising([
            CBAdvertisementDataLocalNameKey:    localName,
            CBAdvertisementDataServiceUUIDsKey: [BleAdvertiseHandler.serviceUUID],
        ])
        log("sender advertising as '\(localName)'")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static let logger = OSLog(
        subsystem: Bundle.main.bundleIdentifier ?? "com.bfast.app",
        category: "BleAdvertise"
    )

    private func log(_ msg: String) {
        os_log("%{private}@", log: BleAdvertiseHandler.logger, type: .debug, msg)
    }
}

// ── CBPeripheralManagerDelegate (sender manager only) ─────────────────────────

extension BleAdvertiseHandler: CBPeripheralManagerDelegate {

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        guard peripheral === senderPeripheralMgr else { return }
        if peripheral.state == .poweredOn {
            doAdvertiseSender()
        }
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager,
                                              error: Error?) {
        guard peripheral === senderPeripheralMgr else { return }
        if let err = error {
            log("sender advertising failed: \(err.localizedDescription) — will retry on next poweredOn")
            // pendingSenderAd is still set; doAdvertiseSender() will be called
            // again when peripheralManagerDidUpdateState fires with .poweredOn
            // after the system (AirDrop, Handoff) releases the BLE radio slot.
        } else {
            log("sender advertising started successfully")
        }
    }
}
