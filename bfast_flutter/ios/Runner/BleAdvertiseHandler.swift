import Foundation
import Flutter
import CoreBluetooth

/// Manages BLE peripheral advertising for the Receiver role on iOS.
/// flutter_blue_plus only exposes central (scanner) APIs, so this native
/// handler uses CoreBluetooth's CBPeripheralManager directly.
class BleAdvertiseHandler: NSObject, CBPeripheralManagerDelegate {

    private let methodChannel:  FlutterMethodChannel
    private var peripheralMgr:  CBPeripheralManager?
    private var pendingAdData:  (deviceId: String, displayName: String)?

    private let serviceUUID  = CBUUID(string: "FFF0")
    private let tapCharUUID  = CBUUID(string: "FFF1")
    private let sessCharUUID = CBUUID(string: "FFF2")

    init(messenger: FlutterBinaryMessenger) {
        methodChannel = FlutterMethodChannel(
            name: "com.bfast.app/ble_advertise",
            binaryMessenger: messenger
        )
        super.init()
        methodChannel.setMethodCallHandler(handle)
        peripheralMgr = CBPeripheralManager(delegate: self, queue: nil)
    }

    private func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "startAdvertising":
            guard let args = call.arguments as? [String: Any],
                  let deviceId    = args["deviceId"]    as? String,
                  let displayName = args["displayName"] as? String else {
                result(FlutterError(code: "INVALID_ARGS",
                                    message: "deviceId and displayName required",
                                    details: nil))
                return
            }
            startAdvertising(deviceId: deviceId, displayName: displayName)
            result(nil)

        case "stopAdvertising":
            stopAdvertising()
            result(nil)

        default:
            result(FlutterMethodNotImplemented)
        }
    }

    func startAdvertising(deviceId: String, displayName: String) {
        guard let mgr = peripheralMgr, mgr.state == .poweredOn else {
            pendingAdData = (deviceId, displayName)
            return
        }

        // Build GATT service with tap characteristic
        let tapChar = CBMutableCharacteristic(
            type:        tapCharUUID,
            properties:  [.read, .notify, .writeWithoutResponse],
            value:       nil,
            permissions: [.readable, .writeable]
        )
        let sessChar = CBMutableCharacteristic(
            type:        sessCharUUID,
            properties:  [.read, .write],
            value:       nil,
            permissions: [.readable, .writeable]
        )
        let service = CBMutableService(type: serviceUUID, primary: true)
        service.characteristics = [tapChar, sessChar]

        mgr.removeAllServices()
        mgr.add(service)

        let adName = "BFAST_\(deviceId.prefix(6))"
        mgr.startAdvertising([
            CBAdvertisementDataLocalNameKey:   adName,
            CBAdvertisementDataServiceUUIDsKey: [serviceUUID],
        ])
    }

    func stopAdvertising() {
        peripheralMgr?.stopAdvertising()
        peripheralMgr?.removeAllServices()
    }

    // ── CBPeripheralManagerDelegate ─────────────────────────────────────

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        if peripheral.state == .poweredOn, let pending = pendingAdData {
            startAdvertising(deviceId: pending.deviceId, displayName: pending.displayName)
            pendingAdData = nil
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           didAdd service: CBService, error: Error?) {
        if let err = error {
            print("[BleAdvertiseHandler] Failed to add service: \(err)")
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           didReceiveRead request: CBATTRequest) {
        request.value = Data()
        peripheral.respond(to: request, withResult: .success)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           didReceiveWrite requests: [CBATTRequest]) {
        for req in requests {
            peripheral.respond(to: req, withResult: .success)
        }
    }
}
