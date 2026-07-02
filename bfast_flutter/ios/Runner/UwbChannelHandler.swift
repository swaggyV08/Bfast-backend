import Foundation
import Flutter
import NearbyInteraction

/// UWB ranging on iOS via NINearbyInteraction.
/// Available on iPhone 11 and later (iOS 14+).
///
/// TOKEN CONTRACT — matches Android's UwbChannelHandler.kt:
///   getLocalToken  → returns hex-encoded String   (was FlutterStandardTypedData — fixed)
///   startRanging   → receives hex-encoded String  (was FlutterStandardTypedData — fixed)
///
/// EventChannel emits Double (distance in metres) on each ranging update,
/// matching Android which emits dist.toDouble().
class UwbChannelHandler: NSObject {

    private let methodChannel: FlutterMethodChannel
    private let eventChannel:  FlutterEventChannel
    private var eventSink: FlutterEventSink?

    private var _niSessionAny: Any? = nil
    private let _sessionLock = NSLock()

    @available(iOS 14.0, *)
    private var niSession: NISession? {
        get {
            _sessionLock.lock()
            defer { _sessionLock.unlock() }
            return _niSessionAny as? NISession
        }
        set {
            _sessionLock.lock()
            defer { _sessionLock.unlock() }
            _niSessionAny = newValue
        }
    }

    // ── Init ─────────────────────────────────────────────────────────────────

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

    // ── MethodChannel handler ─────────────────────────────────────────────────

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
            guard let args         = call.arguments as? [String: Any],
                  let peerTokenHex = args["peerToken"] as? String
            else {
                result(FlutterError(code: "INVALID_ARGS",
                                    message: "peerToken (hex String) required",
                                    details: nil))
                return
            }
            startRanging(peerTokenHex: peerTokenHex, result: result)

        case "stopRanging":
            stopRanging()
            result(nil)

        default:
            result(FlutterMethodNotImplemented)
        }
    }

    // ── getLocalToken ─────────────────────────────────────────────────────────

    @available(iOS 14.0, *)
    private func getLocalToken(result: @escaping FlutterResult) {
        if niSession == nil {
            niSession = NISession()
            niSession?.delegate = self
        }
        guard let token = niSession?.discoveryToken else {
            // Session not ready yet — caller should retry
            result(nil); return
        }
        do {
            let data = try NSKeyedArchiver.archivedData(
                withRootObject: token,
                requiringSecureCoding: true
            )
            // Return as hex String — same contract as Android which returns a hex String
            result(data.hexString)
        } catch {
            result(FlutterError(code: "UWB_ERROR",
                                message: error.localizedDescription,
                                details: nil))
        }
    }

    // ── startRanging ──────────────────────────────────────────────────────────

    @available(iOS 14.0, *)
    private func startRanging(peerTokenHex: String, result: @escaping FlutterResult) {
        guard let peerData = Data(hexString: peerTokenHex) else {
            result(FlutterError(code: "INVALID_ARGS",
                                message: "peerToken is not valid hex",
                                details: nil))
            return
        }
        do {
            guard let peerToken = try NSKeyedUnarchiver.unarchivedObject(
                ofClass: NIDiscoveryToken.self,
                from: peerData
            ) else {
                result(FlutterError(code: "UWB_ERROR",
                                    message: "Failed to decode NIDiscoveryToken",
                                    details: nil))
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
            result(FlutterError(code: "UWB_ERROR",
                                message: error.localizedDescription,
                                details: nil))
        }
    }

    // ── stopRanging ───────────────────────────────────────────────────────────

    func stopRanging() {
        if #available(iOS 14.0, *) {
            niSession?.invalidate()
            niSession = nil
        }
    }
}

// ── NISessionDelegate ─────────────────────────────────────────────────────────

@available(iOS 14.0, *)
extension UwbChannelHandler: NISessionDelegate {

    func session(_ session: NISession, didUpdate nearbyObjects: [NINearbyObject]) {
        guard let obj = nearbyObjects.first, let distance = obj.distance else { return }
        DispatchQueue.main.async { [weak self] in
            self?.eventSink?(Double(distance))
        }
    }

    func session(_ session: NISession, didInvalidateWith error: Error) {
        // Capture sink before the async dispatch; onCancel may nil it between now
        // and when the block executes, which would call FlutterEventSink after
        // cancellation — undefined behaviour in the Flutter engine.
        DispatchQueue.main.async { [weak self] in
            guard let self, let sink = self.eventSink else { return }
            sink(FlutterEndOfEventStream)
            self.eventSink = nil
        }
    }

    func sessionWasSuspended(_ session: NISession) {}
    func sessionSuspensionEnded(_ session: NISession) {}
}

// ── FlutterStreamHandler ──────────────────────────────────────────────────────

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

// ── Data hex extensions ───────────────────────────────────────────────────────

private extension Data {
    /// Decode a hex string into Data.  Returns nil if the string has odd length
    /// or contains non-hex characters.
    init?(hexString: String) {
        let hex = hexString
        guard hex.count.isMultiple(of: 2) else { return nil }
        var result = Data(capacity: hex.count / 2)
        var idx = hex.startIndex
        while idx < hex.endIndex {
            let next = hex.index(idx, offsetBy: 2)
            guard let byte = UInt8(hex[idx..<next], radix: 16) else { return nil }
            result.append(byte)
            idx = next
        }
        self = result
    }

    /// Encode Data as a lowercase hex string.
    var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }
}
