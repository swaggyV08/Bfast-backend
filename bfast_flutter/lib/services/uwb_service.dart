import 'dart:async';
import 'package:flutter/services.dart';
import '../core/constants/app_constants.dart';

/// Thin wrapper around the native UWB platform channel.
///
/// On devices without UWB hardware (most Android < Pixel 6, all iPhone < 11)
/// [isAvailable] is false and all methods are no-ops that return gracefully.
class UwbService {
  static const MethodChannel _method =
      MethodChannel(AppConstants.uwbMethodChannel);
  static const EventChannel _events =
      EventChannel(AppConstants.uwbEventChannel);

  bool   _isAvailable     = false;
  double? _currentDistance; // meters, null if no active ranging

  bool    get isAvailable      => _isAvailable;
  double? get currentDistance  => _currentDistance;

  StreamSubscription<dynamic>? _distanceSub;
  final StreamController<double> _distanceController =
      StreamController<double>.broadcast();

  Stream<double> get distanceStream => _distanceController.stream;

  Future<void> initialize() async {
    try {
      _isAvailable = await _method.invokeMethod<bool>('isUwbAvailable') ?? false;
    } on PlatformException {
      _isAvailable = false;
    } catch (_) {
      _isAvailable = false;
    }
  }

  Future<String?> getLocalToken() async {
    if (!_isAvailable) return null;
    try {
      return await _method.invokeMethod<String>('getLocalToken');
    } catch (_) {
      return null;
    }
  }

  Future<void> startRanging(String peerToken) async {
    if (!_isAvailable) return;
    try {
      await _method.invokeMethod('startRanging', {'peerToken': peerToken});
      _distanceSub = _events.receiveBroadcastStream().listen(
        (dynamic d) {
          if (d is num) {
            _currentDistance = d.toDouble();
            _distanceController.add(_currentDistance!);
          }
        },
        onError: (_) {},
      );
    } catch (_) {}
  }

  Future<void> stopRanging() async {
    _distanceSub?.cancel();
    _distanceSub     = null;
    _currentDistance = null;
    if (!_isAvailable) return;
    try {
      await _method.invokeMethod('stopRanging');
    } catch (_) {}
  }

  bool isInDetectionRange() =>
      _currentDistance != null && _currentDistance! <= AppConstants.uwbDetectionRangeM;

  bool isInTapRange() =>
      _currentDistance != null && _currentDistance! <= AppConstants.uwbTapRangeM;

  void dispose() {
    stopRanging();
    _distanceController.close();
  }
}
