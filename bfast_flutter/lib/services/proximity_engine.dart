import 'dart:async';
import 'uwb_service.dart';
import 'tap_detection_service.dart';

// ── Events ─────────────────────────────────────────────────────────────────────

sealed class ProximityEvent {}

/// UWB distance measurement — update the UI range indicator.
class UwbRangeUpdate extends ProximityEvent {
  final double distanceMeters;
  UwbRangeUpdate(this.distanceMeters);
}

/// UWB engine confirmed proximity AND local IMU tap simultaneously.
/// tap_provider must call _onMutualConfirmation() when this fires.
class TapMutuallyConfirmedByUwb extends ProximityEvent {
  TapMutuallyConfirmedByUwb();
}

/// Engine could not function (UWB hardware unavailable, session invalid, timeout).
/// tap_provider must stop the UwbEngine and fall back to BleImuEngine.
class ProximityEngineFailure extends ProximityEvent {
  final String reason;
  ProximityEngineFailure(this.reason);
}

// ── Abstract interface ─────────────────────────────────────────────────────────

abstract class ProximityEngine {
  Stream<ProximityEvent> get events;

  /// Arm the engine. Called after SESSION_READY.
  Future<void> start();

  /// Disarm cleanly without touching BLE session or ECDH keys.
  Future<void> stop();

  /// Called by tap_provider when TapDetectionService fires a local tap.
  /// BleImuEngine ignores this (tap_provider runs correlation inline).
  /// UwbEngine uses this to check distance + tap simultaneously.
  void onLocalTap(TapSignatureLocal sig) {}
}

// ── BleImuEngine ───────────────────────────────────────────────────────────────

/// Wraps the existing IMU-based approach.
/// Correlation logic stays in tap_provider; this engine only manages armed state.
/// Its [events] stream is empty — tap_provider handles taps and correlation inline.
class BleImuEngine implements ProximityEngine {
  final TapDetectionService _tapDetector;

  BleImuEngine(this._tapDetector);

  @override
  Stream<ProximityEvent> get events => const Stream.empty();

  @override
  Future<void> start() async {
    _tapDetector.armed = true;
  }

  @override
  Future<void> stop() async {
    _tapDetector.armed = false;
  }

  @override
  void onLocalTap(TapSignatureLocal sig) {
    // tap_provider handles correlation inline for BleImuEngine.
  }
}

// ── UwbEngine ──────────────────────────────────────────────────────────────────

/// NearbyInteraction ranging engine.
///
/// Assumes [UwbService.startRanging] was already called (token exchange happens
/// at the tap_provider level so it can use the BLE handshake channel).
/// This engine subscribes to the distance stream and arms the IMU.
///
/// When distance ≤ uwbTapRangeM AND local IMU fires → emits [TapMutuallyConfirmedByUwb].
/// If no distance reading arrives within 10 seconds of [start] → emits [ProximityEngineFailure].
class UwbEngine implements ProximityEngine {
  final UwbService _uwb;
  final TapDetectionService _tapDetector;

  final _ctrl = StreamController<ProximityEvent>.broadcast();
  StreamSubscription<double>? _distanceSub;
  Timer? _startupTimer;

  UwbEngine({required UwbService uwb, required TapDetectionService tapDetector})
      : _uwb = uwb,
        _tapDetector = tapDetector;

  @override
  Stream<ProximityEvent> get events => _ctrl.stream;

  @override
  Future<void> start() async {
    _tapDetector.armed = true;

    _distanceSub = _uwb.distanceStream.listen((d) {
      if (!_ctrl.isClosed) _ctrl.add(UwbRangeUpdate(d));
    });

    // If no distance update arrives within 10 s, fall back to BleImuEngine.
    _startupTimer = Timer(const Duration(seconds: 10), () {
      if (!_ctrl.isClosed && _uwb.currentDistance == null) {
        _ctrl.add(ProximityEngineFailure('UWB startup timeout'));
      }
    });
  }

  @override
  void onLocalTap(TapSignatureLocal sig) {
    if (_ctrl.isClosed) return;
    if (_uwb.isInTapRange()) {
      _ctrl.add(TapMutuallyConfirmedByUwb());
    }
  }

  @override
  Future<void> stop() async {
    _startupTimer?.cancel();
    _startupTimer = null;
    await _distanceSub?.cancel();
    _distanceSub = null;
    await _uwb.stopRanging();
    _tapDetector.armed = false;
    if (!_ctrl.isClosed) await _ctrl.close();
  }
}
