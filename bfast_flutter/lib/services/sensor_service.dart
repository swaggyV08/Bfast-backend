import 'dart:async';
import 'package:sensors_plus/sensors_plus.dart';
import 'tap_detection_service.dart';

/// Wraps sensors_plus to feed accelerometer + gyroscope into [TapDetectionService].
/// Uses the highest available sampling rate (~400 Hz) for reliable tap detection.
class SensorService {
  final TapDetectionService _tapDetector;

  StreamSubscription<AccelerometerEvent>? _accelSub;
  StreamSubscription<GyroscopeEvent>?     _gyroSub;

  final StreamController<double> _impulseController =
      StreamController<double>.broadcast();

  Stream<double> get liveImpulseStream => _impulseController.stream;

  SensorService({required TapDetectionService tapDetector})
      : _tapDetector = tapDetector;

  void startListening() {
    if (_accelSub != null) return; // already running

    _accelSub = accelerometerEventStream(
      samplingPeriod: const Duration(microseconds: 2500), // ~400 Hz
    ).listen((event) {
      _tapDetector.processAccel(event.x, event.y, event.z);
      _impulseController.add(_tapDetector.liveImpulse);
    });

    _gyroSub = gyroscopeEventStream(
      samplingPeriod: const Duration(microseconds: 2500),
    ).listen((event) {
      _tapDetector.processGyro(event.x, event.y, event.z);
    });
  }

  void stopListening() {
    _accelSub?.cancel();
    _gyroSub?.cancel();
    _accelSub = null;
    _gyroSub  = null;
  }

  void dispose() {
    stopListening();
    _impulseController.close();
  }
}
