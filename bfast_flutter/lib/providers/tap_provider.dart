import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:fluttertoast/fluttertoast.dart';
import '../core/constants/app_constants.dart';
import '../services/ble_service.dart';
import '../services/sensor_service.dart';
import '../services/uwb_service.dart';
import '../services/tap_detection_service.dart';
import '../services/dual_device_correlator.dart';
import '../services/secure_session.dart';
import '../services/api_service.dart';
import '../models/payment_result.dart';
import 'auth_provider.dart';

// ── Protocol phases ────────────────────────────────────────────────────────

enum ProtocolPhase {
  idle,
  advertising,
  scanning,
  discovered,
  connecting,
  connected,
  sessionSetup,       // capability exchange + ECDH + session IDs (internal steps hidden from UI)
  armed,              // IMU active, waiting for tap
  tapDetected,        // local tap fired, motion event sent
  motionExchanged,    // remote motion event received too
  mutualConfirmation, // correlation passed
  paymentReady,
  paymentCompleted,
  error,
}

// Alias so existing screens compile without changes.
typedef TapPhase = ProtocolPhase;

// ── State ──────────────────────────────────────────────────────────────────

class TapState {
  final ProtocolPhase       phase;
  final List<BleDeviceInfo> nearbyReceivers;
  final BleDeviceInfo?      selectedReceiver;
  final String?             errorMessage;
  final PaymentResult?      paymentResult;
  final double              liveImpulse;
  final double?             uwbDistance;
  final String?             peerDisplayName;
  final String              hsStep; // handshake step name — visible in debug badge

  const TapState({
    this.phase            = ProtocolPhase.idle,
    this.nearbyReceivers  = const [],
    this.selectedReceiver,
    this.errorMessage,
    this.paymentResult,
    this.liveImpulse      = 0.0,
    this.uwbDistance,
    this.peerDisplayName,
    this.hsStep           = '',
  });

  TapState copyWith({
    ProtocolPhase?       phase,
    List<BleDeviceInfo>? nearbyReceivers,
    BleDeviceInfo?       selectedReceiver,
    String?              errorMessage,
    PaymentResult?       paymentResult,
    double?              liveImpulse,
    double?              uwbDistance,
    String?              peerDisplayName,
    String?              hsStep,
    bool clearError    = false,
    bool clearReceiver = false,
    bool clearPeer     = false,
  }) =>
      TapState(
        phase:            phase            ?? this.phase,
        nearbyReceivers:  nearbyReceivers  ?? this.nearbyReceivers,
        selectedReceiver: clearReceiver ? null : (selectedReceiver ?? this.selectedReceiver),
        errorMessage:     clearError    ? null : (errorMessage     ?? this.errorMessage),
        paymentResult:    paymentResult    ?? this.paymentResult,
        liveImpulse:      liveImpulse      ?? this.liveImpulse,
        uwbDistance:      uwbDistance      ?? this.uwbDistance,
        peerDisplayName:  clearPeer ? null : (peerDisplayName ?? this.peerDisplayName),
        hsStep:           hsStep           ?? this.hsStep,
      );
}

// ── Provider ───────────────────────────────────────────────────────────────

final tapProvider = StateNotifierProvider<TapNotifier, TapState>(
  (ref) => TapNotifier(ref.read(apiServiceProvider)),
);

// ── Notifier ───────────────────────────────────────────────────────────────

class TapNotifier extends StateNotifier<TapState> {
  final ApiService             _api;
  final FlutterSecureStorage   _storage = const FlutterSecureStorage();
  late final TapDetectionService    _tapDetector;
  late final SensorService          _sensors;
  late final BleService             _ble;
  late final UwbService             _uwb;
  late final DualDeviceCorrelator   _correlator;

  SecureSession? _session;
  bool   _isSender      = false;
  String _myDeviceId    = '';
  String _myUserId      = '';
  String _myDisplayName = '';
  String _mySessionId   = '';

  // Tracks progress inside sessionSetup without adding UI-visible states.
  _HandshakeStep _hsStep = _HandshakeStep.idle;

  // Receiver: sequence number for FFF3 responses (1-255, never 0)
  int _respSeqNum = 0;

  // Sender: last seqNum seen from receiver — avoids double-processing
  int _lastReceiverSeq = 0;

  // Sender: set to true to stop the motion-event poll loop
  bool _stopPoll = false;

  StreamSubscription<List<BleDeviceInfo>>? _receiversSub;
  StreamSubscription<double>?              _impulseSub;
  StreamSubscription?                      _gattServerSub;
  StreamSubscription?                      _disconnectSub;
  Timer? _sessionTimer;
  Timer? _handshakeTimer;

  // ── Logging ───────────────────────────────────────────────────────────────

  void _log(String msg) {
    final ts = DateTime.now().toIso8601String().substring(11, 23);
    debugPrint('[$ts][BFAST][${_isSender ? "SENDER" : "RECEIVER"}] '
        '${state.phase.name}/${_hsStep.name} → $msg');
  }

  void _go(ProtocolPhase next,
      {String? peer, String? error, bool clearPeer = false}) {
    _log('→ ${next.name}${peer != null ? "  peer=$peer" : ""}');
    state = state.copyWith(
      phase:           next,
      peerDisplayName: clearPeer ? null : peer,
      errorMessage:    error,
      hsStep:          _hsStep.name,
      clearError:      error == null,
      clearPeer:       clearPeer,
    );
  }

  TapNotifier(this._api) : super(const TapState()) {
    _tapDetector = TapDetectionService(onTapDetected: _onTapDetected);
    _sensors     = SensorService(tapDetector: _tapDetector);
    _ble         = BleService();
    _uwb         = UwbService();
    _correlator  = DualDeviceCorrelator();
  }

  // ════════════════════════════════════════════════════════════════════════
  //  RECEIVER FLOW
  // ════════════════════════════════════════════════════════════════════════

  Future<void> startAsReceiver() async {
    _stopAll();
    _isSender    = false;
    _respSeqNum  = 0;
    _go(ProtocolPhase.advertising);

    await _loadMyInfo();
    try { await _uwb.initialize(); } catch (_) {}

    _sensors.startListening();
    _impulseSub = _sensors.liveImpulseStream
        .listen((v) => state = state.copyWith(liveImpulse: v));

    _generateBackendSession();

    await _ble.startReceiver(_myDeviceId, _myDisplayName);
    _gattServerSub = _ble.gattServerEvents.listen(_onGattServerEvent);
  }

  Future<void> _generateBackendSession() async {
    try {
      final res = await _api.generateSession(_myDeviceId);
      if (res['success'] == true) {
        _mySessionId = (res['data'] as Map?)?['scId'] as String? ?? '';
      }
    } catch (_) {}
  }

  void _onGattServerEvent(Map<String, dynamic> event) {
    final type = event['type'] as String? ?? '';
    _log('GATT event: $type');

    switch (type) {
      case 'connected':
        _hsStep     = _HandshakeStep.idle;
        _respSeqNum = 0;
        _go(ProtocolPhase.connected);
        _startHandshakeTimer();

      case 'disconnected':
        if (!_isTerminal(state.phase)) {
          _log('Sender disconnected mid-protocol — resetting to advertising');
          _toast('Connection lost', color: Colors.orange);
          _handshakeTimer?.cancel();
          _disarmImu();
          _session?.dispose();
          _session    = null;
          _hsStep     = _HandshakeStep.idle;
          _respSeqNum = 0;
          _ble.setReceiverBusy(false);
          _go(ProtocolPhase.advertising, clearPeer: true);
        }

      case 'data':
        _onReceiverWrite(
          event['charType'] as String? ?? '',
          (event['data'] as List).cast<int>(),
        );
    }
  }

  Future<void> _onReceiverWrite(String charType, List<int> bytes) async {
    if (bytes.isEmpty) return;
    final (msgType, payload) = GattMsg.decode(bytes);

    switch (msgType) {
      // ── Step 1: Sender sends [protocolVersion, capsByte] ──────────────
      case AppConstants.gattMsgCapabilities:
        if (state.phase != ProtocolPhase.connected) return;
        _session = SecureSession(
            connectionId: DateTime.now().millisecondsSinceEpoch.toString());

        if (payload.length >= 2) {
          _session!.remoteCapabilities = DeviceCapabilities.fromByte(payload[1]);
          _log('Peer protocol v${payload[0]} caps=${payload[1]}');
        } else if (payload.isNotEmpty) {
          _session!.remoteCapabilities = DeviceCapabilities.fromByte(payload[0]);
        }

        _hsStep = _HandshakeStep.capabilities;
        _go(ProtocolPhase.sessionSetup);

        final caps = DeviceCapabilities(hasUwb: false, hasHighRateImu: true);
        final respBytes = GattMsg.encode(AppConstants.gattMsgCapabilities,
            [AppConstants.protocolVersion, caps.toByte()]);
        _log('Step2: setting capabilities response in FFF3');
        await _setReceiverResponse(respBytes);
        _log('Step2: capabilities response set (seq=$_respSeqNum)');

      // ── Step 3: Sender sends its public key ───────────────────────────
      case AppConstants.gattMsgPublicKey:
        if (state.phase != ProtocolPhase.sessionSetup) return;
        if (_hsStep != _HandshakeStep.capabilities) return;
        _hsStep = _HandshakeStep.ecdh;

        _log('Step3: received sender public key (${payload.length}B)');
        final sess = _session ??= SecureSession(
            connectionId: DateTime.now().millisecondsSinceEpoch.toString());
        final ourPub = await sess.generateKeyPair();
        _log('Step4: setting public key response in FFF3');
        await _setReceiverResponse(GattMsg.encode(AppConstants.gattMsgPublicKey, ourPub));
        _log('Step4: public key response set (seq=$_respSeqNum)');
        await sess.computeSharedSecret(payload);
        _log('Shared secret derived (receiver)');

      // ── Step 5: Sender sends session info ─────────────────────────────
      case AppConstants.gattMsgSessionInfo:
        final sess = _session;
        if (sess == null || !sess.hasSharedKey) return;
        if (_hsStep != _HandshakeStep.ecdh) return;
        _hsStep = _HandshakeStep.sessionInfo;

        _log('Step5: received sender session info');
        final senderInfo = PeerInfo.fromBytes(payload);
        if (senderInfo == null) {
          _log('Step5: FAILED to parse sender PeerInfo');
          return;
        }
        sess.remoteInfo = senderInfo;
        _go(ProtocolPhase.sessionSetup, peer: senderInfo.displayName);

        final myInfo = PeerInfo(
          userId:      _myUserId,
          displayName: _myDisplayName,
          role:        'RECEIVER',
          sessionId:   _mySessionId,
        );
        sess.localInfo = myInfo;
        _log('Step6: setting session info response in FFF3');
        await _setReceiverResponse(
            GattMsg.encode(AppConstants.gattMsgSessionInfo, myInfo.toBytes()));
        _log('Step6: session info response set (seq=$_respSeqNum)');

      // ── Step 7: Sender ACK — lock receiver + arm IMU ──────────────────
      case AppConstants.gattMsgSessionAck:
        if (state.phase != ProtocolPhase.sessionSetup) return;
        if (_hsStep != _HandshakeStep.sessionInfo) return;
        _hsStep = _HandshakeStep.complete;

        _log('Step7: received ACK — handshake complete');
        _handshakeTimer?.cancel();
        await _ble.setReceiverBusy(true);
        _armImu();
        _startSessionTimer();
        _go(ProtocolPhase.armed);
        _toast('Ready — tap phones together to pay',
            color: const Color(0xFF4CAF50));

      // ── Sender's tap event ────────────────────────────────────────────
      case AppConstants.gattMsgMotionEvent:
        _onRemoteMotionPayload(payload);
    }
  }

  // ── Helper: increment seqNum and store response bytes in FFF3 ────────────

  Future<void> _setReceiverResponse(List<int> gattMsgBytes) async {
    _respSeqNum = (_respSeqNum % 255) + 1; // 1-255, never 0
    await _ble.receiverSetHandshakeResponse([_respSeqNum, ...gattMsgBytes]);
  }

  // ════════════════════════════════════════════════════════════════════════
  //  SENDER FLOW
  // ════════════════════════════════════════════════════════════════════════

  Future<void> startAsSender() async {
    _stopAll();
    _isSender        = true;
    _lastReceiverSeq = 0;
    _stopPoll        = false;
    _go(ProtocolPhase.scanning, clearPeer: true);

    await _loadMyInfo();
    try { await _uwb.initialize(); } catch (_) {}

    _sensors.startListening();
    _impulseSub = _sensors.liveImpulseStream
        .listen((v) => state = state.copyWith(liveImpulse: v));

    // Recover if receiver drops the connection unexpectedly.
    _disconnectSub = _ble.onGattDisconnected.listen(_onSenderGattDropped);

    try {
      await _ble.startScanning();
      _receiversSub = _ble.nearbyReceiversStream.listen(_onReceiversUpdated);
    } catch (_) {}
  }

  void _onSenderGattDropped(_) {
    if (_isTerminal(state.phase)) return;
    _log('GATT connection dropped — returning to scan');
    _toast('Receiver disconnected — scanning again', color: Colors.orange);
    _stopPoll = true;
    _handshakeTimer?.cancel();
    _sessionTimer?.cancel();
    _disarmImu();
    _session?.dispose();
    _session         = null;
    _hsStep          = _HandshakeStep.idle;
    _lastReceiverSeq = 0;
    _go(ProtocolPhase.scanning, clearPeer: true);
    // Scanning was stopped at connect-time; restart it now.
    _receiversSub?.cancel();
    _receiversSub = null;
    _ble.startScanning();
    _receiversSub = _ble.nearbyReceiversStream.listen(_onReceiversUpdated);
  }

  void _onReceiversUpdated(List<BleDeviceInfo> receivers) {
    if (_isTerminal(state.phase)) return;

    final inZone = receivers
        .where((r) => r.rssi >= AppConstants.rssiDetectionZone)
        .toList();

    // Once we're past the discovery phase, just keep the displayed list fresh
    // so the receiver card stays visible — but DO NOT attempt a new connection.
    if (!{ProtocolPhase.scanning, ProtocolPhase.discovered}.contains(state.phase)) {
      if (inZone.isNotEmpty) state = state.copyWith(nearbyReceivers: inZone);
      return;
    }

    if (inZone.isEmpty) {
      if (state.phase != ProtocolPhase.scanning) {
        state = state.copyWith(
            phase: ProtocolPhase.scanning, nearbyReceivers: const []);
      }
      return;
    }

    state = state.copyWith(
      phase:            ProtocolPhase.discovered,
      nearbyReceivers:  inZone,
      selectedReceiver: inZone.first,
    );
    _connectToReceiver(inZone.first);
  }

  /// Drives the ENTIRE handshake sequentially — write → poll FFF3 → write → poll → …
  /// This replaces the old notification-based approach and is reliable across all Android versions.
  Future<void> _connectToReceiver(BleDeviceInfo receiver) async {
    if (!{ProtocolPhase.discovered, ProtocolPhase.scanning}
        .contains(state.phase)) return;

    _go(ProtocolPhase.connecting, peer: receiver.displayName);

    final ok = await _ble.connectToReceiver(receiver.bleAddress);
    if (!ok || !mounted) {
      _toast('Could not connect to ${receiver.displayName}', color: Colors.red);
      _go(ProtocolPhase.scanning, clearPeer: true);
      return;
    }

    // Stop scanning so _onReceiversUpdated stops firing during handshake + session.
    // Scanning resumes only if connection drops or handshake times out.
    await _ble.stopScanning();
    _receiversSub?.cancel();
    _receiversSub = null;

    _go(ProtocolPhase.connected, peer: receiver.displayName);
    _hsStep          = _HandshakeStep.idle;
    _lastReceiverSeq = 0;
    _startHandshakeTimer();

    // ── Step 1: send capabilities ────────────────────────────────────────
    final caps = DeviceCapabilities(hasUwb: false, hasHighRateImu: true);
    try {
      await _ble.senderWriteHandshake(
          AppConstants.gattMsgCapabilities,
          [AppConstants.protocolVersion, caps.toByte()]);
      _log('Step1: capabilities written');
    } catch (e) {
      _log('Step1 FAILED: $e');
      return;
    }

    // ── Step 2: read receiver's capabilities from FFF3 ───────────────────
    final step2 = await _ble.senderWaitForResponse(
        expectedType: AppConstants.gattMsgCapabilities,
        lastSeq:      _lastReceiverSeq,
        timeoutMs:    5000);
    if (step2 == null || !mounted) {
      _log('Step2: timeout waiting for capabilities response'); return;
    }
    _lastReceiverSeq = step2[0];
    final capPayload  = step2.sublist(2); // [protocolVersion, capsByte]
    _session = SecureSession(
        connectionId: DateTime.now().millisecondsSinceEpoch.toString());
    if (capPayload.length >= 2) {
      _session!.remoteCapabilities = DeviceCapabilities.fromByte(capPayload[1]);
      _log('Step2: receiver caps received (v${capPayload[0]})');
    }
    _hsStep = _HandshakeStep.capabilities;
    _go(ProtocolPhase.sessionSetup);

    // ── Step 3: generate keypair + send public key ───────────────────────
    final ourPub = await _session!.generateKeyPair();
    _hsStep = _HandshakeStep.ecdh;
    try {
      await _ble.senderWriteHandshake(AppConstants.gattMsgPublicKey, ourPub);
      _log('Step3: public key written (${ourPub.length}B)');
    } catch (e) {
      _log('Step3 FAILED: $e'); return;
    }

    // ── Step 4: read receiver's public key ───────────────────────────────
    final step4 = await _ble.senderWaitForResponse(
        expectedType: AppConstants.gattMsgPublicKey,
        lastSeq:      _lastReceiverSeq,
        timeoutMs:    5000);
    if (step4 == null || !mounted) {
      _log('Step4: timeout waiting for public key response'); return;
    }
    _lastReceiverSeq = step4[0];
    final peerPub = step4.sublist(2);
    _log('Step4: receiver public key received (${peerPub.length}B)');
    await _session!.computeSharedSecret(peerPub);
    _log('Shared secret derived (sender)');

    // ── Step 5: send session info ────────────────────────────────────────
    try {
      await _senderSendSessionInfo(_session!);
    } catch (e) {
      _log('Step5 FAILED: $e'); return;
    }

    // ── Step 6: read receiver's session info ─────────────────────────────
    final step6 = await _ble.senderWaitForResponse(
        expectedType: AppConstants.gattMsgSessionInfo,
        lastSeq:      _lastReceiverSeq,
        timeoutMs:    5000);
    if (step6 == null || !mounted) {
      _log('Step6: timeout waiting for session info response'); return;
    }
    _lastReceiverSeq = step6[0];
    final receiverInfo = PeerInfo.fromBytes(step6.sublist(2));
    if (receiverInfo == null) {
      _log('Step6: FAILED to parse receiver PeerInfo'); return;
    }
    _session!.remoteInfo = receiverInfo;
    _log('Session ready: receiver=${receiverInfo.displayName}');

    // ── Step 7: send ACK ─────────────────────────────────────────────────
    try {
      await _ble.senderWriteHandshake(AppConstants.gattMsgSessionAck, [0x01]);
      _log('Step7: ACK written');
    } catch (e) {
      _log('Step7 FAILED: $e'); return;
    }

    _hsStep = _HandshakeStep.complete;
    _handshakeTimer?.cancel();
    _armSender(receiverInfo.displayName);

    // Start polling FFF3 for incoming motion events from receiver
    _startSenderMotionPoll();
  }

  Future<void> _senderSendSessionInfo(SecureSession sess) async {
    if (_mySessionId.isEmpty) {
      try {
        final res = await _api.generateSession(_myDeviceId);
        if (res['success'] == true) {
          _mySessionId = (res['data'] as Map?)?['scId'] as String? ?? '';
        }
      } catch (_) {}
    }
    final info = PeerInfo(
      userId:      _myUserId,
      displayName: _myDisplayName,
      role:        'SENDER',
      sessionId:   _mySessionId,
    );
    sess.localInfo = info;
    _hsStep = _HandshakeStep.sessionInfo;
    await _ble.senderWriteHandshake(
        AppConstants.gattMsgSessionInfo, info.toBytes());
    _log('Step5: session info written');
  }

  void _armSender(String peerName) {
    final rssi = state.selectedReceiver?.rssi ?? -100;
    _log('Proximity: RSSI=$rssi dBm  peer=$peerName');
    if (rssi < AppConstants.rssiDetectionZone) {
      _toast('Move phones closer for best results', color: Colors.orange);
    }
    _armImu();
    _startSessionTimer();
    _go(ProtocolPhase.armed, peer: peerName);
    _toast('Ready — tap phones together', color: const Color(0xFF1E88E5));
  }

  /// Polls FFF3 every 150ms while armed, looking for motion events from receiver.
  /// Uses seqNum to avoid double-processing.
  void _startSenderMotionPoll() {
    _stopPoll = false;
    _doSenderMotionPoll();
  }

  Future<void> _doSenderMotionPoll() async {
    while (!_stopPoll && _isSender && mounted) {
      await Future.delayed(const Duration(milliseconds: 150));
      if (_stopPoll || !mounted) break;
      // Only read during motion-exchange phase (saves BLE reads in other states)
      if (!{ProtocolPhase.armed, ProtocolPhase.tapDetected,
            ProtocolPhase.motionExchanged}.contains(state.phase)) continue;
      try {
        final val = await _ble.senderReadResponse();
        if (val.length >= 2 && val[0] != 0 && val[0] != _lastReceiverSeq) {
          _lastReceiverSeq = val[0];
          final msgType = val[1];
          if (msgType == AppConstants.gattMsgMotionEvent) {
            _onRemoteMotionPayload(val.sublist(2));
          }
        }
      } catch (_) {
        _log('Motion poll error — stopping');
        break;
      }
    }
    _log('Sender motion poll stopped');
  }

  // ════════════════════════════════════════════════════════════════════════
  //  TAP DETECTION → MOTION EXCHANGE → MUTUAL CONFIRMATION
  // ════════════════════════════════════════════════════════════════════════

  void _onTapDetected(TapSignatureLocal sig) {
    if (!_tapDetector.armed) return;
    if (!{ProtocolPhase.armed, ProtocolPhase.tapDetected,
        ProtocolPhase.motionExchanged}.contains(state.phase)) return;

    _log('Local tap: peak=${sig.peakAccelMs2.toStringAsFixed(1)} '
        'dur=${sig.durationMs}ms conf=${sig.confidence.toStringAsFixed(3)}');

    final result = _correlator.recordLocalTap(sig);
    if (state.phase == ProtocolPhase.armed) _go(ProtocolPhase.tapDetected);

    if (_isSender) {
      _ble.senderWriteMotion(sig, _myDeviceId);
    } else {
      // Receiver: store motion event in FFF3 for sender to poll
      _storeReceiverMotionEvent(sig);
      _reportTapToBackend(sig);
    }

    if (result != null && result.matched) _onMutualConfirmation();
  }

  Future<void> _storeReceiverMotionEvent(TapSignatureLocal sig) async {
    final payload = sig.encodeToBlePayload(_myDeviceId);
    _respSeqNum = (_respSeqNum % 255) + 1;
    await _ble.receiverSetMotionResponse(payload, _respSeqNum);
    _log('Motion event stored in FFF3 (seq=$_respSeqNum)');
  }

  void _onRemoteMotionPayload(List<int> payload) {
    final payloadStr = String.fromCharCodes(payload);
    final remote     = _correlator.decodeTapFromBle(payloadStr);
    if (remote == null) return;

    _log('Remote tap: peak=${(remote.peakAccelX100 / 100).toStringAsFixed(1)} '
        'dur=${remote.durationMs}ms');

    final result = _correlator.recordRemoteTap(remote);
    if (state.phase == ProtocolPhase.armed ||
        state.phase == ProtocolPhase.tapDetected) {
      _go(ProtocolPhase.motionExchanged);
    }
    if (result != null && result.matched) _onMutualConfirmation();
  }

  void _onMutualConfirmation() {
    if (_isTerminal(state.phase)) return;

    _log('Mutual tap confirmed!');
    _stopPoll = true;
    _sessionTimer?.cancel();
    _disarmImu();

    if (_isSender) {
      final peerName = _session?.remoteInfo?.displayName ??
                       state.selectedReceiver?.displayName ?? 'receiver';
      _toast('Tap confirmed — paying $peerName',
          color: const Color(0xFF4CAF50));
      // Go directly to paymentReady — the screen listener triggers navigation
      // immediately without an intermediate spinner.
      _go(ProtocolPhase.paymentReady);
    } else {
      _go(ProtocolPhase.mutualConfirmation);
      _ble.setReceiverBusy(false);
      _toast('Payment tap confirmed!', color: const Color(0xFF4CAF50));
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  //  PAYMENT SUBMISSION (SENDER)
  // ════════════════════════════════════════════════════════════════════════

  Future<void> submitPayment(double amount) async {
    final receiver  = state.selectedReceiver;
    final sessionId = _session?.remoteInfo?.sessionId ??
                      _session?.localInfo?.sessionId  ?? '';

    if (receiver == null || sessionId.isEmpty) {
      _go(ProtocolPhase.error, error: 'Session expired. Please tap again.');
      return;
    }

    state = state.copyWith(phase: ProtocolPhase.paymentCompleted);
    try {
      final res = await _api.initiateTransaction(
        sessionCodeId:    sessionId,
        receiverDeviceId: receiver.deviceId,
        amountInr:        amount,
      );
      if (res['success'] == true) {
        final data = (res['data'] as Map<String, dynamic>?) ?? {};
        state = state.copyWith(
          phase: ProtocolPhase.paymentCompleted,
          paymentResult: PaymentResult(
            transactionId: data['txRef'] as String? ?? '',
            amount:        amount,
            receiverName:  _session?.remoteInfo?.displayName ??
                           receiver.displayName,
            success:       true,
            timestamp:     DateTime.now(),
          ),
        );
      } else {
        _go(ProtocolPhase.error, error: 'Payment failed. Please try again.');
      }
    } on Exception catch (e) {
      _go(ProtocolPhase.error,
          error: e.toString().replaceAll('Exception: ', ''));
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  //  HELPERS
  // ════════════════════════════════════════════════════════════════════════

  Future<void> _loadMyInfo() async {
    _myDeviceId    = await _storage.read(key: 'device_id')    ?? '';
    _myUserId      = await _storage.read(key: 'user_id')      ?? '';
    _myDisplayName = await _storage.read(key: 'display_name') ?? 'User';
  }

  void _armImu()    { _tapDetector.armed = true;  _log('IMU armed'); }
  void _disarmImu() { _tapDetector.armed = false; _log('IMU disarmed'); }

  bool _isTerminal(ProtocolPhase p) => {
    ProtocolPhase.paymentReady, ProtocolPhase.paymentCompleted,
    ProtocolPhase.mutualConfirmation, ProtocolPhase.error,
  }.contains(p);

  void _startHandshakeTimer() {
    _handshakeTimer?.cancel();
    _handshakeTimer = Timer(
      const Duration(milliseconds: AppConstants.handshakeTimeoutMs),
      () {
        if (state.phase == ProtocolPhase.connected ||
            state.phase == ProtocolPhase.sessionSetup) {
          _log('Handshake timed out at step ${_hsStep.name}');
          _toast('Could not establish secure session — try again',
              color: Colors.orange);
          _session?.dispose();
          _session         = null;
          _hsStep          = _HandshakeStep.idle;
          _lastReceiverSeq = 0;
          _stopPoll        = true;
          _ble.disconnect();

          if (_isSender) {
            _go(ProtocolPhase.scanning, clearPeer: true);
            _ble.stopScanning();
            _receiversSub?.cancel();
            _receiversSub = null;
            Future.delayed(const Duration(seconds: 3), () {
              if (mounted && state.phase == ProtocolPhase.scanning) {
                _stopPoll     = false;
                _lastReceiverSeq = 0;
                _ble.startScanning();
                _receiversSub ??=
                    _ble.nearbyReceiversStream.listen(_onReceiversUpdated);
              }
            });
          } else {
            _go(ProtocolPhase.advertising, clearPeer: true);
          }
        }
      },
    );
  }

  void _startSessionTimer() {
    _sessionTimer?.cancel();
    _sessionTimer = Timer(
      const Duration(milliseconds: AppConstants.sessionTimeoutMs),
      () {
        if ({ProtocolPhase.armed, ProtocolPhase.tapDetected,
            ProtocolPhase.motionExchanged}.contains(state.phase)) {
          _log('Session timed out');
          _toast('Session expired — tap again to pay', color: Colors.grey);
          reset();
        }
      },
    );
  }

  Future<void> _reportTapToBackend(TapSignatureLocal sig) async {
    if (_myDeviceId.isEmpty) return;
    try {
      await _api.reportTapEvent(
          receiverDeviceId: _myDeviceId, accelPeakMs2: sig.peakAccelMs2);
    } catch (_) {}
  }

  void _toast(String msg, {Color color = Colors.black}) {
    Fluttertoast.showToast(
      msg:             msg,
      toastLength:     Toast.LENGTH_SHORT,
      gravity:         ToastGravity.TOP,
      backgroundColor: color,
      textColor:       Colors.white,
      fontSize:        14.0,
    );
  }

  // ════════════════════════════════════════════════════════════════════════
  //  APP LIFECYCLE
  // ════════════════════════════════════════════════════════════════════════

  void pauseForBackground() {
    _log('App backgrounded');
    _stopPoll = true;
    _ble.stopScanning();
    _ble.stopReceiver();
    _disarmImu();
    _sensors.stopListening();
  }

  void resumeFromBackground() {
    _log('App resumed');
    if (_isSender) startAsSender(); else startAsReceiver();
  }

  // ════════════════════════════════════════════════════════════════════════
  //  CLEANUP
  // ════════════════════════════════════════════════════════════════════════

  void _stopAll() {
    _stopPoll = true;
    _sessionTimer?.cancel();
    _handshakeTimer?.cancel();
    _receiversSub?.cancel();
    _impulseSub?.cancel();
    _gattServerSub?.cancel();
    _disconnectSub?.cancel();
    _sensors.stopListening();
    _ble.stopScanning();
    _ble.disconnect();
    _ble.stopReceiver();
    _ble.setReceiverBusy(false);
    _tapDetector.reset();
    _correlator.reset();
    _session?.dispose();

    _sessionTimer    = null;
    _handshakeTimer  = null;
    _receiversSub    = null;
    _impulseSub      = null;
    _gattServerSub   = null;
    _disconnectSub   = null;
    _session         = null;
    _mySessionId     = '';
    _isSender        = false;
    _hsStep          = _HandshakeStep.idle;
    _respSeqNum      = 0;
    _lastReceiverSeq = 0;
  }

  void reset() {
    _stopAll();
    state = const TapState();
  }

  @override
  void dispose() {
    _stopAll();
    _ble.dispose();
    super.dispose();
  }
}

// ── Internal handshake sub-state (never exposed to UI) ────────────────────

enum _HandshakeStep { idle, capabilities, ecdh, sessionInfo, complete }
