import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';
import 'package:permission_handler/permission_handler.dart';
import '../core/constants/app_constants.dart';
import 'ble_proximity_engine.dart';
import 'tap_detection_service.dart';
import 'secure_session.dart';

// ── Discovered device info ────────────────────────────────────────────────────

class BleDeviceInfo {
  final String   bleAddress;  // actual BLE MAC for GATT connect
  final String   deviceId;    // internal UUID from mfg data (or MAC as fallback)
  final String   displayName;
  final int      rssi;
  final DateTime lastSeen;
  final String   role;

  BleDeviceInfo({
    required this.bleAddress,
    required this.deviceId,
    required this.displayName,
    required this.rssi,
    required this.lastSeen,
    this.role = 'receiver',
  });
}

// ── BLE service ───────────────────────────────────────────────────────────────

/// BLE service for BFast tap-to-pay.
///
/// RECEIVER: advertises + runs GATT server (via native channel)
/// SENDER:   scans + connects as GATT client + drives handshake
///
/// Handshake uses a READ-poll pattern instead of NOTIFY:
///   Sender writes to FFF2 (HandshakeChar)
///   Receiver processes write, stores response in FFF3 (ResponseChar)
///   Sender polls FFF3 every 100ms until a new seqNum appears
///   → No CCCD, no NOTIFY, no Samsung BLE stack issues
class BleService {
  // ── Native channels ──────────────────────────────────────────────────────
  static const MethodChannel _advertChannel =
      MethodChannel(AppConstants.bleAdvertChannel);
  static const MethodChannel _gattChannel =
      MethodChannel(AppConstants.gattServerChannel);
  static const EventChannel _gattEvents =
      EventChannel(AppConstants.gattServerEvents);

  // ── GATT char GUIDs ──────────────────────────────────────────────────────
  static final Guid _handshakeGuid = Guid(AppConstants.bleHandshakeCharUuid);
  static final Guid _responseGuid  = Guid(AppConstants.bleResponseCharUuid);

  // ── BFast manufacturer ID ─────────────────────────────────────────────────
  static const int _bfastMfgId = 0x0BFA;

  final BleProximityEngine _proximityEngine = BleProximityEngine();

  // ── Nearby devices (sender's scan results) ────────────────────────────────
  final Map<String, BleDeviceInfo> _nearbyDevices = {};
  final StreamController<List<BleDeviceInfo>> _receiversCtrl =
      StreamController<List<BleDeviceInfo>>.broadcast();
  Stream<List<BleDeviceInfo>> get nearbyReceiversStream => _receiversCtrl.stream;
  BleDeviceInfo? _lastEmittedClosest;

  // Devices that sent back a busy rejection; excluded from scan results
  // until `clearBusySkipList` is called (e.g., on reset).
  final Set<String> _busyDevices = {};

  StreamSubscription<List<ScanResult>>? _scanSub;
  bool _isScanning = false;

  // ── GATT client state (sender connects to receiver) ──────────────────────
  BluetoothDevice?               _connectedDevice;
  BluetoothCharacteristic?       _handshakeChar; // FFF2 — sender writes here
  BluetoothCharacteristic?       _responseChar;  // FFF3 — sender reads responses here
  StreamSubscription?            _connStateSub;

  // Fires whenever the connected GATT device drops unexpectedly (sender side).
  final StreamController<void> _disconnectCtrl =
      StreamController<void>.broadcast();
  Stream<void> get onGattDisconnected => _disconnectCtrl.stream;

  // ── GATT server events (receiver listens to native) ──────────────────────
  StreamSubscription? _gattEventSub;
  final StreamController<Map<String, dynamic>> _gattServerCtrl =
      StreamController<Map<String, dynamic>>.broadcast();
  Stream<Map<String, dynamic>> get gattServerEvents => _gattServerCtrl.stream;

  // ════════════════════════════════════════════════════════════════════════════
  //  Runtime permission request (Android 12+ requires explicit grant)
  // ════════════════════════════════════════════════════════════════════════════

  /// Requests all BLE + location permissions needed for scanning, connecting,
  /// and advertising. Must be called before any BLE operation on Android.
  /// On Android < 12 the bluetooth permissions auto-grant; location covers scan.
  static Future<bool> requestBlePermissions() async {
    if (!Platform.isAndroid) return true;
    final statuses = await [
      Permission.bluetoothScan,
      Permission.bluetoothConnect,
      Permission.bluetoothAdvertise,
      Permission.locationWhenInUse,
    ].request();
    final denied = statuses.entries
        .where((e) => !e.value.isGranted)
        .map((e) => e.key.toString())
        .toList();
    if (denied.isNotEmpty) {
      debugPrint('[BFAST][BLE] Permissions still denied: $denied');
    }
    // Scan + connect are the critical ones; advertise/location are best-effort.
    return (statuses[Permission.bluetoothScan]?.isGranted  ?? false) &&
           (statuses[Permission.bluetoothConnect]?.isGranted ?? false);
  }

  // ════════════════════════════════════════════════════════════════════════════
  //  Advertising + GATT server (RECEIVER)
  // ════════════════════════════════════════════════════════════════════════════

  Future<void> startReceiver(String deviceId, String displayName) async {
    await requestBlePermissions();
    try { await _advertChannel.invokeMethod('startAdvertising', {
      'deviceId': deviceId, 'displayName': displayName, 'isSender': false,
    }); } catch (_) {}

    try { await _gattChannel.invokeMethod('startGattServer'); } catch (_) {}

    // Guard against double subscription: EventChannel throws PlatformException
    // ("already active") on Android if the previous onCancel fires asynchronously
    // after the new listen() call arrives at the native layer.
    if (_gattEventSub == null) {
      _gattEventSub = _gattEvents.receiveBroadcastStream().listen((event) {
        final map = (event as Map).cast<String, dynamic>();
        if (map['type'] == 'busy_rejected') {
          _busyDevices.add(map['deviceAddress'] as String? ?? '');
        }
        _gattServerCtrl.add(map);
      });
    }
  }

  Future<void> stopReceiver() async {
    try { await _advertChannel.invokeMethod('stopAdvertising'); } catch (_) {}
    try { await _gattChannel.invokeMethod('stopGattServer'); }   catch (_) {}
    _gattEventSub?.cancel();
    _gattEventSub = null;
  }

  /// Tell the native GATT server whether a session is active.
  /// When active, any new incoming connection is rejected with a busy frame.
  Future<void> setReceiverBusy(bool busy) async {
    try {
      await _gattChannel.invokeMethod('setSessionActive', {'active': busy});
    } catch (_) {}
  }

  // ── Receiver stores response/event in FFF3 for sender to READ ────────────

  /// Store [bytes] in FFF3 (ResponseChar) so the sender can READ them.
  /// [bytes] format: [seqNum:1, msgType:1, ...payload]
  Future<void> receiverSetHandshakeResponse(List<int> bytes) async {
    try {
      await _gattChannel.invokeMethod('setResponse', {'bytes': bytes});
    } catch (_) {}
  }

  /// Encode a motion event and store in FFF3 for sender to poll.
  Future<void> receiverSetMotionResponse(String blePayload, int seqNum) async {
    final encoded = GattMsg.encode(
        AppConstants.gattMsgMotionEvent,
        utf8.encode(blePayload));
    await receiverSetHandshakeResponse([seqNum, ...encoded]);
  }

  // ════════════════════════════════════════════════════════════════════════════
  //  Scanning (SENDER)
  // ════════════════════════════════════════════════════════════════════════════

  Future<void> startScanning() async {
    if (_isScanning) return;
    await requestBlePermissions();
    _isScanning = true;
    // withServices filter: required for iOS background scanning (CoreBluetooth
    // only scans in background when a specific service UUID is given).
    // Android also benefits: faster discovery by filtering at radio layer.
    await FlutterBluePlus.startScan(
      withServices:      [Guid(AppConstants.bleServiceUuid)],
      continuousUpdates: true,
      removeIfGone:      const Duration(seconds: 4),
    );
    _scanSub = FlutterBluePlus.scanResults.listen(_onScanResults);
  }

  void _onScanResults(List<ScanResult> results) {
    final nowMs = DateTime.now().millisecondsSinceEpoch;
    for (final r in results) {
      final name     = r.device.platformName;
      final mfgBytes = r.advertisementData.manufacturerData[_bfastMfgId];
      if (mfgBytes == null && !name.startsWith(AppConstants.bleDeviceNamePrefix)) continue;

      String role = 'receiver';
      if (mfgBytes != null && mfgBytes.isNotEmpty) {
        role = mfgBytes[0] == 0x53 ? 'sender' : 'receiver';
      }

      final bleAddress = r.device.remoteId.str;
      String deviceId = bleAddress;
      if (mfgBytes != null && mfgBytes.length >= 17) {
        final hex = mfgBytes.skip(1).take(16)
            .map((b) => b.toRadixString(16).padLeft(2, '0')).join();
        deviceId = '${hex.substring(0, 8)}-${hex.substring(8, 12)}'
            '-${hex.substring(12, 16)}-${hex.substring(16, 20)}'
            '-${hex.substring(20, 32)}';
      }

      final displayName = name.startsWith(AppConstants.bleDeviceNamePrefix)
          ? name.replaceFirst(AppConstants.bleDeviceNamePrefix, '')
          : 'BFast User';

      _proximityEngine.feedReading(bleAddress, r.rssi, nowMs);
      _nearbyDevices[bleAddress] = BleDeviceInfo(
        bleAddress: bleAddress, deviceId: deviceId, displayName: displayName,
        rssi: r.rssi, lastSeen: DateTime.now(), role: role,
      );
    }

    _nearbyDevices.removeWhere(
      (_, d) => DateTime.now().difference(d.lastSeen).inSeconds > 4,
    );

    final receivers = _nearbyDevices.values
        .where((d) => d.role == 'receiver' && !_busyDevices.contains(d.bleAddress))
        .toList()
      ..sort((a, b) => b.rssi.compareTo(a.rssi));

    // Debounce: only emit if the closest device changed or RSSI shifted > 5 dBm
    final closest = receivers.isNotEmpty ? receivers.first : null;
    final last    = _lastEmittedClosest;
    final changed = closest?.bleAddress != last?.bleAddress ||
        (closest != null && last != null && (closest.rssi - last.rssi).abs() > 5);
    if (!changed) return;
    _lastEmittedClosest = closest;
    _receiversCtrl.add(List.unmodifiable(receivers));
  }

  Future<void> stopScanning() async {
    if (!_isScanning) return;
    _isScanning = false;
    _scanSub?.cancel();
    _scanSub = null;
    _nearbyDevices.clear();
    _busyDevices.clear();
    try { await FlutterBluePlus.stopScan(); } catch (_) {}
  }

  // ════════════════════════════════════════════════════════════════════════════
  //  GATT client connection (SENDER)
  // ════════════════════════════════════════════════════════════════════════════

  /// Connect to receiver and discover FFF2 (write) and FFF3 (read) characteristics.
  Future<bool> connectToReceiver(String bleAddress) async {
    try {
      final device = BluetoothDevice.fromId(bleAddress);
      // mtu:512 auto-negotiated so large payloads (ECDH public key, session info) fit.
      await device.connect(
          license: License.nonprofit,
          timeout: const Duration(seconds: 15),
          mtu: 512);
      _connectedDevice = device;

      final services = await device.discoverServices();
      final svcGuid  = Guid(AppConstants.bleServiceUuid);

      for (final svc in services) {
        if (svc.uuid != svcGuid) continue;
        for (final char in svc.characteristics) {
          if (char.uuid == _handshakeGuid) {
            _handshakeChar = char;
          } else if (char.uuid == _responseGuid) {
            _responseChar = char;
          }
        }
      }

      final ok = _handshakeChar != null && _responseChar != null;
      if (!ok) {
        debugPrint('[BFAST][BLE] BFast chars not found — '
            'handshake=${_handshakeChar != null} response=${_responseChar != null}');
        await disconnect();
        return false;
      }

      // Verify the negotiated MTU is large enough for ECDH payloads (~200 bytes).
      final actualMtu = await device.mtu.first;
      if (actualMtu < 100) {
        debugPrint('[BFAST][BLE] MTU too small: $actualMtu — disconnecting');
        await disconnect();
        return false;
      }
      debugPrint('[BFAST][BLE] MTU negotiated: $actualMtu bytes');

      // iOS only: subscribe to FFF3 NOTIFY so the iOS receiver's GattServerHandler
      // can detect when this central disconnects (via didUnsubscribeFrom callback).
      // The subscription carries no data — we still poll via READ.
      // On Android receivers FFF3 is READ-only; setNotifyValue will throw and
      // the catch swallows it silently.
      if (Platform.isIOS && _responseChar!.properties.notify) {
        try {
          await _responseChar!.setNotifyValue(true);
          debugPrint('[BFAST][BLE] subscribed to FFF3 NOTIFY (iOS disconnect detection)');
        } catch (_) {}
      }

      // Watch for unexpected drops so tap_provider can recover.
      _connStateSub?.cancel();
      _connStateSub = device.connectionState.listen((s) {
        if (s == BluetoothConnectionState.disconnected) {
          _disconnectCtrl.add(null);
        }
      });

      return true;
    } catch (e) {
      debugPrint('[BFAST][BLE] connectToReceiver failed: $e');
      await disconnect();
      return false;
    }
  }

  /// Write a framed handshake message to the receiver's HandshakeChar (FFF2).
  /// Uses write-with-response so ATT ACK confirms receipt; failures are thrown.
  Future<void> senderWriteHandshake(int msgType, List<int> payload) async {
    final char = _handshakeChar;
    if (char == null) {
      debugPrint('[BFAST][BLE] senderWriteHandshake: handshakeChar is null');
      return;
    }
    final bytes = Uint8List.fromList([msgType, ...payload]);
    debugPrint('[BFAST][BLE] write handshake type=0x${msgType.toRadixString(16)} '
        'len=${bytes.length}');
    await char.write(bytes, withoutResponse: false);
  }

  /// Write a motion event to the receiver's HandshakeChar (FFF2).
  Future<void> senderWriteMotion(TapSignatureLocal sig, String deviceId) async {
    final payload = utf8.encode(sig.encodeToBlePayload(deviceId));
    final msg     = Uint8List.fromList([AppConstants.gattMsgMotionEvent, ...payload]);
    final char    = _handshakeChar;
    if (char == null) return;
    try { await char.write(msg, withoutResponse: true); } catch (_) {}
  }

  /// Poll FFF3 (ResponseChar) until a response with the expected msgType and a
  /// new seqNum appears, or until [timeoutMs] elapses.
  ///
  /// Returns [seqNum, msgType, ...payload] or null on timeout/error.
  Future<List<int>?> senderWaitForResponse({
    required int expectedType,
    int lastSeq   = 0,
    int timeoutMs = 3000,
  }) async {
    final deadline = DateTime.now().add(Duration(milliseconds: timeoutMs));
    while (DateTime.now().isBefore(deadline)) {
      await Future.delayed(const Duration(milliseconds: 50));
      try {
        final val = await _responseChar?.read() ?? [];
        // val[0]=seqNum, val[1]=msgType, val[2:]=payload
        if (val.length >= 2 &&
            val[0] != 0 &&
            val[0] != lastSeq &&
            val[1] == expectedType) {
          debugPrint('[BFAST][BLE] senderWaitForResponse: got type=0x${expectedType.toRadixString(16)} '
              'seq=${val[0]} len=${val.length}');
          return val;
        }
      } catch (_) {
        return null; // disconnected
      }
    }
    debugPrint('[BFAST][BLE] senderWaitForResponse: timeout waiting for '
        'type=0x${expectedType.toRadixString(16)}');
    return null;
  }

  /// Read FFF3 once and return the raw bytes.
  /// Throws [StateError] if the characteristic is gone (disconnected).
  Future<List<int>> senderReadResponse() async {
    try {
      return await _responseChar?.read() ?? [];
    } catch (e) {
      throw StateError('BLE read failed — likely disconnected: $e');
    }
  }

  /// Write a null keep-alive byte to FFF2 so the iOS GATT watchdog resets.
  Future<void> senderWriteKeepAlive() async {
    final char = _handshakeChar;
    if (char == null) return;
    try { await char.write([0x00], withoutResponse: true); } catch (_) {}
  }

  Future<void> disconnect() async {
    _connStateSub?.cancel();
    _connStateSub  = null;
    _handshakeChar = null;
    _responseChar  = null;
    try { await _connectedDevice?.disconnect(); } catch (_) {}
    _connectedDevice = null;
  }

  // ════════════════════════════════════════════════════════════════════════════
  //  Advertising (sender-side, for discovery)
  // ════════════════════════════════════════════════════════════════════════════

  Future<void> startSenderAdvertising(String deviceId, String displayName) async {
    try {
      await _advertChannel.invokeMethod('startAdvertising', {
        'deviceId': deviceId, 'displayName': displayName, 'isSender': true,
      });
    } catch (_) {}
  }

  Future<void> stopAdvertising() async {
    try { await _advertChannel.invokeMethod('stopAdvertising'); } catch (_) {}
  }

  // ════════════════════════════════════════════════════════════════════════════
  //  Proximity helpers
  // ════════════════════════════════════════════════════════════════════════════

  double getProximityScore(String bleAddress) =>
      _proximityEngine.getScoreForDevice(bleAddress);

  bool isInTapZone(String bleAddress) =>
      getProximityScore(bleAddress) >= AppConstants.proximityScoreTapZone;

  // ════════════════════════════════════════════════════════════════════════════
  //  Cleanup
  // ════════════════════════════════════════════════════════════════════════════

  void dispose() {
    stopScanning();
    disconnect();
    stopReceiver();
    _receiversCtrl.close();
    _gattServerCtrl.close();
    _disconnectCtrl.close();
  }
}
