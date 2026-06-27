import 'dart:math';

/// Tap intensity classification
enum TapType { softTap, normalTap, hardTap }

/// Structured result of a single tap event, passed to the callback and
/// used by the DualDeviceCorrelator for cross-device verification.
class TapSignatureLocal {
  /// Confidence score 0.90–1.00
  final double confidence;
  /// Wall-clock time of the tap (ms since epoch)
  final int timestampMs;
  /// Peak impulse magnitude (m/s²)
  final double peakAccelMs2;
  /// Dominant axis index: 0=X, 1=Y, 2=Z
  final int peakAxis;
  /// How long the impulse stayed elevated (ms)
  final int durationMs;
  /// Gyroscope magnitude at tap time (rad/s)
  final double gyroMagnitude;
  final TapType tapType;

  const TapSignatureLocal({
    required this.confidence,
    required this.timestampMs,
    required this.peakAccelMs2,
    required this.peakAxis,
    required this.durationMs,
    required this.gyroMagnitude,
    required this.tapType,
  });

  /// Encode to BLE payload string: "B|peakX100|durationMs|tsLast4|deviceId"
  String encodeToBlePayload(String deviceId) {
    final peakX100 = (peakAccelMs2 * 100).toInt().clamp(0, 9999);
    final dur = durationMs.clamp(0, 999);
    final ts4 = timestampMs % 10000;
    final id = deviceId.length > 10 ? deviceId.substring(0, 10) : deviceId;
    return 'B|$peakX100|$dur|$ts4|$id';
  }
}

/// Production-grade tap detector. Exact port of the Kotlin TapDetector.
///
/// Algorithm summary:
///  1. Gravity EMA (time-based α, sample-rate independent)
///  2. Impulse = |rawMag − gravity|  (unsmoothed, raw peak preserved)
///  3. Adaptive noise floor (EMA of impulse during IDLE, no cooldown, no shake)
///  4. Dynamic threshold = noiseMean + 1.2·σ, capped at 0.45 m/s²
///  5. State machine: IDLE → ELEVATED → validate → tap / reject
///  6. Gates: duration ≤500ms, debounce 200ms, shake <10 crossings/s,
///            sustained gyro >4 rad/s for >400ms
///  7. Post-tap noise cooldown 800ms
class TapDetectionService {
  // ── Constants ───────────────────────────────────────────────────────────
  static const double zScoreMultiplier      = 2.0;   // was 2.5 — slightly more sensitive
  static const double minAbsoluteImpulse    = 1.5;   // was 2.5 — catch softer taps
  static const double thresholdCeiling      = 6.0;   // was 5.0 — allow higher noise floor
  static const double tapImpulseMax         = 80.0;
  static const int    minElevatedMs         = 0;
  static const int    maxElevatedMs         = 200;   // was 150 — more forgiving on slow taps
  static const int    debounceMs            = 250;   // was 300 — slightly faster rearm
  static const int    noiseCooldownMs       = 800;
  static const double gravityTimeConstant   = 2.0;
  static const int    shakeWindowMs         = 1000;
  static const int    shakeMaxCrossings     = 25;
  static const double gyroSustainedThreshold = 4.0;
  static const int    gyroSustainedMs       = 400;

  // ── Arming control ──────────────────────────────────────────────────────
  bool armed = false;

  // ── Gravity estimation ─────────────────────────────────────────────────
  double _gravityEstimate = 9.81;
  bool   _gravityInitialized = false;
  int    _lastSampleTimeUs = 0; // microseconds

  // ── Adaptive noise floor ───────────────────────────────────────────────
  double _noiseMean = 0.0;
  double _noiseVar  = 0.01;
  bool   _noiseEmaInitialized = false;
  double _currentDynamicThreshold = minAbsoluteImpulse;

  // ── Post-tap noise cooldown ────────────────────────────────────────────
  int _lastTapDetectedAtMs = 0;

  // ── Peak-detection state machine ──────────────────────────────────────
  bool   _isElevated         = false;
  double _peakImpulse        = 0.0;
  double _peakDynamicThreshold = 0.0;
  double _peakStdDev         = 0.0;
  int    _elevatedStartMs    = 0;
  List<double> _peakAxisAccel = [0.0, 0.0, 0.0];

  // ── Shake detection ────────────────────────────────────────────────────
  final List<int> _crossingTimestamps = [];
  bool _wasAboveThreshold = false;

  // ── Gyroscope tracking ────────────────────────────────────────────────
  double _currentGyroMag    = 0.0;
  int    _gyroElevatedSinceMs = 0;

  // ── Debounce ──────────────────────────────────────────────────────────
  int _lastTapTimeMs = 0;

  // ── Live / debug values ────────────────────────────────────────────────
  double liveImpulse  = 0.0;
  double liveGyroMag  = 0.0;
  double lastPeakAccel = 0.0;
  double lastGyro     = 0.0;
  int    lastDuration = 0;
  TapSignatureLocal? lastTapSignature;

  final void Function(TapSignatureLocal) onTapDetected;

  TapDetectionService({required this.onTapDetected});

  // ════════════════════════════════════════════════════════════════════════
  //  Accelerometer processing
  // ════════════════════════════════════════════════════════════════════════

  void processAccel(double x, double y, double z) {
    final rawMag = sqrt(x * x + y * y + z * z);
    final nowMs  = DateTime.now().millisecondsSinceEpoch;

    // Free-fall protection: accelerometer near-zero during free-fall
    // → would give false impulse = |0 − 9.81| = 9.81
    if (rawMag < 2.0) return;

    // ── 1. Gravity estimate (time-based EMA) ──────────────────────────────
    if (!_gravityInitialized) {
      _gravityEstimate    = rawMag;
      _gravityInitialized = true;
      _lastSampleTimeUs   = DateTime.now().microsecondsSinceEpoch;
      return; // need ≥2 samples for dt
    }

    final currentUs = DateTime.now().microsecondsSinceEpoch;
    final dtSec = ((currentUs - _lastSampleTimeUs) / 1e6).clamp(0.001, 0.5);
    _lastSampleTimeUs = currentUs;

    // α = dt / (dt + τ)  →  independent of sample rate
    final alpha = (dtSec / (dtSec + gravityTimeConstant)).clamp(0.0005, 0.05);
    _gravityEstimate = _gravityEstimate * (1.0 - alpha) + rawMag * alpha;

    // ── 2. Impulse (raw, unsmoothed) ──────────────────────────────────────
    final impulse = (rawMag - _gravityEstimate).abs();
    liveImpulse = impulse;

    // ── 3. Shake-detection crossing counter ───────────────────────────────
    final aboveThreshold = impulse > _currentDynamicThreshold;
    if (aboveThreshold && !_wasAboveThreshold) {
      _crossingTimestamps.add(nowMs);
    }
    _wasAboveThreshold = aboveThreshold;
    _crossingTimestamps.removeWhere((t) => nowMs - t > shakeWindowMs);
    final isShaking = _crossingTimestamps.length >= shakeMaxCrossings;

    // ── 4. Adaptive Noise Floor (EMA) ─────────────────────────────────────
    final alphaNoise = (dtSec / (dtSec + 1.0)).clamp(0.001, 0.1);
    final inCooldown = (nowMs - _lastTapDetectedAtMs) < noiseCooldownMs;

    if (!_noiseEmaInitialized) {
      _noiseMean           = impulse;
      _noiseVar            = 0.01;
      _noiseEmaInitialized = true;
    } else if (!_isElevated && !inCooldown && !isShaking) {
      _noiseMean = (1.0 - alphaNoise) * _noiseMean + alphaNoise * impulse;
      final diff = impulse - _noiseMean;
      _noiseVar  = (1.0 - alphaNoise) * _noiseVar + alphaNoise * (diff * diff);
    }

    final noiseStdDev = sqrt(_noiseVar).clamp(0.05, double.infinity);
    _currentDynamicThreshold = (_noiseMean + zScoreMultiplier * noiseStdDev)
        .clamp(minAbsoluteImpulse, thresholdCeiling);

    // ── Arming gate ────────────────────────────────────────────────────────
    // Gravity and noise estimators warm up even when disarmed for instant
    // readiness when the receiver arms itself.
    if (!armed) return;

    // ── 5. State machine ──────────────────────────────────────────────────
    if (!_isElevated) {
      // IDLE → check for rising edge
      if (impulse >= _currentDynamicThreshold && impulse <= tapImpulseMax) {
        _isElevated            = true;
        _peakImpulse           = impulse;
        _peakDynamicThreshold  = _currentDynamicThreshold;
        _peakStdDev            = noiseStdDev;
        _elevatedStartMs       = nowMs;
        _peakAxisAccel         = [x.toDouble(), y.toDouble(), z.toDouble()];
      }
    } else {
      // ELEVATED → track peak and wait for decay
      if (impulse > _peakImpulse) {
        _peakImpulse   = impulse;
        _peakAxisAccel = [x.toDouble(), y.toDouble(), z.toDouble()];
      }

      final elapsedMs = nowMs - _elevatedStartMs;

      // Timeout: still elevated after maxElevatedMs → not a tap (phone handling)
      if (elapsedMs > maxElevatedMs) {
        _resetElevatedState();
        return;
      }

      // Impulse decayed below threshold → validate the candidate
      if (impulse < _peakDynamicThreshold) {
        final durationMs = elapsedMs;

        // Gate 1: Duration (0 ms minimum allows single-sample taps)
        if (durationMs < minElevatedMs) { _resetElevatedState(); return; }

        // Gate 2: Debounce
        if (nowMs - _lastTapTimeMs < debounceMs) { _resetElevatedState(); return; }

        // Gate 3: Shake rejection
        if (_crossingTimestamps.length >= shakeMaxCrossings) { _resetElevatedState(); return; }

        // Gate 4: Sustained gyro rejection
        if (_isGyroSustained(nowMs)) { _resetElevatedState(); return; }

        // Gate 5: Peak still in valid range
        if (_peakImpulse < _peakDynamicThreshold || _peakImpulse > tapImpulseMax) {
          _resetElevatedState(); return;
        }

        // ✅ ALL GATES PASSED — VALID TAP ─────────────────────────────────
        lastPeakAccel        = _peakImpulse;
        lastGyro             = _currentGyroMag;
        lastDuration         = durationMs;
        _lastTapTimeMs       = nowMs;
        _lastTapDetectedAtMs = nowMs; // start noise cooldown

        final confidence     = _calculateConfidence(_peakImpulse, _peakDynamicThreshold, _peakStdDev, durationMs);
        final dominantAxis   = _determinePeakAxis(_peakAxisAccel);
        final tapType        = _classifyTap(_peakImpulse);

        final sig = TapSignatureLocal(
          confidence:    confidence,
          timestampMs:   nowMs,
          peakAccelMs2:  _peakImpulse,
          peakAxis:      dominantAxis,
          durationMs:    durationMs,
          gyroMagnitude: _currentGyroMag,
          tapType:       tapType,
        );
        lastTapSignature = sig;
        onTapDetected(sig);

        _resetElevatedState();
        // Clear crossing history so next tap isn't penalized by this tap's crossings
        _crossingTimestamps.clear();
        _wasAboveThreshold = false;
      }
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  //  Gyroscope processing
  // ════════════════════════════════════════════════════════════════════════

  void processGyro(double x, double y, double z) {
    _currentGyroMag = sqrt(x * x + y * y + z * z);
    liveGyroMag     = _currentGyroMag;
    final nowMs     = DateTime.now().millisecondsSinceEpoch;

    if (_currentGyroMag > gyroSustainedThreshold) {
      if (_gyroElevatedSinceMs == 0) _gyroElevatedSinceMs = nowMs;
    } else {
      _gyroElevatedSinceMs = 0;
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  //  Helpers
  // ════════════════════════════════════════════════════════════════════════

  bool _isGyroSustained(int nowMs) =>
      _gyroElevatedSinceMs > 0 && (nowMs - _gyroElevatedSinceMs) > gyroSustainedMs;

  void _resetElevatedState() {
    _isElevated           = false;
    _peakImpulse          = 0.0;
    _peakDynamicThreshold = 0.0;
    _peakStdDev           = 0.0;
    _elevatedStartMs      = 0;
  }

  int _determinePeakAxis(List<double> accel) {
    final adjusted = [
      accel[0].abs(),
      accel[1].abs(),
      (accel[2] - _gravityEstimate).abs(),
    ];
    double maxVal = adjusted.reduce(max);
    return adjusted.indexOf(maxVal);
  }

  TapType _classifyTap(double peak) {
    if (peak < 2.0)  return TapType.softTap;
    if (peak < 15.0) return TapType.normalTap;
    return TapType.hardTap;
  }

  double _calculateConfidence(double peak, double threshold, double stdDev, int durationMs) {
    final excess     = peak - threshold;
    final peakScore  = (excess / (stdDev * 5.0)).clamp(0.0, 1.0);
    final durScore   = durationMs <= 80 ? 1.0 : 0.7;
    final raw        = peakScore * 0.6 + durScore * 0.4;
    return (0.90 + raw * 0.10).clamp(0.90, 1.0);
  }

  // ════════════════════════════════════════════════════════════════════════
  //  Full reset
  // ════════════════════════════════════════════════════════════════════════

  void reset() {
    armed                  = false;
    _gravityEstimate       = 9.81;
    _gravityInitialized    = false;
    _lastSampleTimeUs      = 0;
    _noiseMean             = 0.0;
    _noiseVar              = 0.01;
    _noiseEmaInitialized   = false;
    _currentDynamicThreshold = minAbsoluteImpulse;
    _lastTapDetectedAtMs   = 0;
    _isElevated            = false;
    _peakImpulse           = 0.0;
    _peakDynamicThreshold  = 0.0;
    _peakStdDev            = 0.0;
    _elevatedStartMs       = 0;
    _crossingTimestamps.clear();
    _wasAboveThreshold     = false;
    _currentGyroMag        = 0.0;
    _gyroElevatedSinceMs   = 0;
    _lastTapTimeMs         = 0;
    liveImpulse            = 0.0;
    liveGyroMag            = 0.0;
    lastPeakAccel          = 0.0;
    lastGyro               = 0.0;
    lastDuration           = 0;
    lastTapSignature       = null;
  }
}
