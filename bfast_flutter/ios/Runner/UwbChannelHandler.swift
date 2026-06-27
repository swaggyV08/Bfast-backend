import Foundation
import Flutter
import NearbyInteraction

/// Handles UWB ranging on iOS via the NINearbyInteraction framework.
/// Available on iPhone 11 and later running iOS 14+.
/// On unsupported devices [isUwbAvailable] returns false and all operations
/// are no-ops, allowing the app to fall back to BLE-only proximity.
class UwbChannelHandler: NSObject {

    private let methodChannel: FlutterMethodChannel
    private let eventChannel:  FlutterEventChannel
    private var eventSink: FlutterEventSink?

    @available(iOS 14.0, *)
    private var niSession: NISession? = nil

    init(messenger: FlutterBinaryMessenger) {
        methodChannel = FlutterMethodChannel(
            name: "com.bfast.app/uwb",
            binaryMessenger: messenger
        )
        eventChannel = FlutterEventChannel(
            name: "com.bfast.app/uwb/events",
            binaryMessenger: messenger
        )
        super.init()
        methodChannel.setMethodCallHandler(handle)
        eventChannel.setStreamHandler(self)
    }

    private func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {

        case "isUwbAvailable":
            if #available(iOS 14.0, *) {
                result(NISession.isSupported)
            } else {
                result(false)
            }

        case "getLocalToken":
            guard #available(iOS 14.0, *), NISession.isSupported else {
                result(nil); return
            }
            getLocalToken(result: result)

        case "startRanging":
            guard #available(iOS 14.0, *), NISession.isSupported else {
                result(nil); return
            }
            guard let args = call.arguments as? [String: Any],
                  let tokenData = (args["peerToken"] as? FlutterStandardTypedData)?.data else {
                result(FlutterError(code: "INVALID_ARGS", message: "peerToken required", details: nil))
                return
            }
            startRanging(peerTokenData: tokenData, result: result)

        case "stopRanging":
            stopRanging()
            result(nil)

        default:
            result(FlutterMethodNotImplemented)
        }
    }

    @available(iOS 14.0, *)
    private func getLocalToken(result: @escaping FlutterResult) {
        if niSession == nil {
            niSession = NISession()
            niSession?.delegate = self
        }
        guard let token = niSession?.discoveryToken else {
            // Session not ready yet
            result(nil); return
        }
        do {
            let data = try NSKeyedArchiver.archivedData(
                withRootObject: token,
                requiringSecureCoding: true
            )
            result(FlutterStandardTypedData(bytes: data))
        } catch {
            result(FlutterError(code: "UWB_ERROR", message: error.localizedDescription, details: nil))
        }
    }

    @available(iOS 14.0, *)
    private func startRanging(peerTokenData: Data, result: @escaping FlutterResult) {
        do {
            guard let peerToken = try NSKeyedUnarchiver.unarchivedObject(
                ofClass: NIDiscoveryToken.self, from: peerTokenData
            ) else {
                result(FlutterError(code: "UWB_ERROR", message: "Invalid peer token", details: nil))
                return
            }
            if niSession == nil {
                niSession = NISession()
                niSession?.delegate = self
            }
            let config = NINearbyPeerConfiguration(peerToken: peerToken)
            niSession?.run(config)
            result(nil)
        } catch {
            result(FlutterError(code: "UWB_ERROR", message: error.localizedDescription, details: nil))
        }
    }

    func stopRanging() {
        if #available(iOS 14.0, *) {
            niSession?.invalidate()
            niSession = nil
        }
    }
}

// ── NISessionDelegate ─────────────────────────────────────────────────────

@available(iOS 14.0, *)
extension UwbChannelHandler: NISessionDelegate {

    func session(_ session: NISession, didUpdate nearbyObjects: [NINearbyObject]) {
        guard let obj = nearbyObjects.first, let distance = obj.distance else { return }
        DispatchQueue.main.async {
            self.eventSink?(Double(distance))
        }
    }

    func session(_ session: NISession, didInvalidateWith error: Error) {
        // Peer left range — end the distance stream cleanly
        DispatchQueue.main.async {
            self.eventSink?(FlutterEndOfEventStream)
        }
    }

    func sessionWasSuspended(_ session: NISession) {}
    func sessionSuspensionEnded(_ session: NISession) {}
}

// ── FlutterStreamHandler ──────────────────────────────────────────────────

extension UwbChannelHandler: FlutterStreamHandler {
    func onListen(withArguments arguments: Any?,
                  eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        self.eventSink = events
        return nil
    }
    func onCancel(withArguments arguments: Any?) -> FlutterError? {
        self.eventSink = nil
        return nil
    }
}
