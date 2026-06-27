import 'dart:math';
import 'tap_detection_service.dart';

/// Result of correlating two tap signatures.
class CorrelationResult {
  final bool   matched;
  final int    timeDeltaMs;
  final double magnitudeRatio;
  final double correlationScore;

  const CorrelationResult({
    required this.matched,
    required this.timeDeltaMs,
    required this.magnitudeRatio,
    required this.correlationScore,
  });
}

/// Compact tap data received via BLE "B" command from the remote device.
class RemoteTapData {
  final String deviceId;
  /// Peak acceleration × 100 (integer encoding)
  final int peakAccelX100;
  final int durationMs;
  /// Last 4 digits of timestamp (0–9999)
  final int timestampLast4;
  final int receivedAtMs;

  RemoteTapData({
    required this.deviceId,
    required this.peakAccelX100,
    required this.durationMs,
    required this.timestampLast4,
    int? receivedAtMs,
  }) : receivedAtMs = receivedAtMs ?? DateTime.now().millisecondsSinceEpoch;
}

/// Cross-device tap correlation engine.
///
/// When Device A detects a tap it broadcasts a BLE "B" payload.
/// Device B correlates the remote tap with its own recent taps using:
///   1. Time alignment   (50%) — both taps must be within 150ms
///   2. Magnitude ratio  (30%) — peaks within 0.2×–5.0× of each other
///   3. Duration similarity (20%) — durations within 100ms
class DualDeviceCorrelator {
  // ── Constants ───────────────────────────────────────────────────────────
  // Time window uses the LOCAL device's clock for both timestamps, so it is
  // immune to cross-device clock skew.  We compare local.timestampMs (local
  // clock at tap time) vs remote.receivedAtMs (local clock when the remote
  // event arrived).  For a simultaneous physical tap the delta is just the
  // BLE latency + FFF3 poll interval ≤ ~200ms, so 300ms gives comfortable
  // headroom without allowing false positives from sequential taps.
  static const int    maxTimeDeltaMs        = 300;
  static const double minMagnitudeRatio     = 0.2;
  static const double maxMagnitudeRatio     = 5.0;
  static const int    maxDurationDeltaMs    = 100;
  static const double wTime                 = 0.50;
  static const double wMagnitude            = 0.30;
  static const double wDuration             = 0.20;
  static const double minCorrelationScore   = 40.0;
  static const int    localTapBufferDurMs   = 2000;

  // ── Buffers ─────────────────────────────────────────────────────────────
  final List<TapSignatureLocal> _localTapBuffer  = [];
  final List<RemoteTapData>     _remoteTapBuffer = [];

  CorrelationResult?  lastCorrelationResult;
  double              lastCorrelationScore = 0.0;
  TapSignatureLocal?  lastLocalTap;
  RemoteTapData?      lastRemoteTap;

  // ════════════════════════════════════════════════════════════════════════
  //  Core API
  // ════════════════════════════════════════════════════════════════════════

  CorrelationResult? recordLocalTap(TapSignatureLocal sig) {
    _evictStale();
    _localTapBuffer.add(sig);
    lastLocalTap = sig;

    for (final remote in _remoteTapBuffer) {
      final result = _correlate(sig, remote);
      if (result.matched) {
        lastCorrelationResult = result;
        lastCorrelationScore  = result.correlationScore;
        return result;
      }
    }
    return null;
  }

  CorrelationResult? recordRemoteTap(RemoteTapData remote) {
    _evictStale();
    _remoteTapBuffer.add(remote);
    lastRemoteTap = remote;

    for (final local in _localTapBuffer) {
      final result = _correlate(local, remote);
      if (result.matched) {
        lastCorrelationResult = result;
        lastCorrelationScore  = result.correlationScore;
        return result;
      }
    }
    return null;
  }

  /// Expose internal correlate for external decision engine use.
  CorrelationResult? correlate(TapSignatureLocal local, RemoteTapData remote) {
    final r = _correlate(local, remote);
    return r.matched ? r : null;
  }

  void reset() {
    _localTapBuffer.clear();
    _remoteTapBuffer.clear();
    lastCorrelationResult = null;
    lastCorrelationScore  = 0.0;
    lastLocalTap          = null;
    lastRemoteTap         = null;
  }

  // ════════════════════════════════════════════════════════════════════════
  //  BLE payload encoding / decoding
  // ════════════════════════════════════════════════════════════════════════

  /// Encodes: "B|peakX100|durationMs|tsLast4|deviceId"
  String encodeTapForBle(TapSignatureLocal sig, String deviceId) {
    final peakX100 = (sig.peakAccelMs2 * 100).toInt().clamp(0, 9999);
    final dur      = sig.durationMs.clamp(0, 999);
    final ts4      = sig.timestampMs % 10000;
    final id       = deviceId.length > 10 ? deviceId.substring(0, 10) : deviceId;
    return 'B|$peakX100|$dur|$ts4|$id';
  }

  RemoteTapData? decodeTapFromBle(String payload) {
    try {
      final parts = payload.split('|');
      if (parts.length < 5 || parts[0] != 'B') return null;
      final peakX100 = int.tryParse(parts[1]);
      final dur      = int.tryParse(parts[2]);
      final ts4      = int.tryParse(parts[3]);
      if (peakX100 == null || dur == null || ts4 == null) return null;
      return RemoteTapData(
        deviceId:        parts[4],
        peakAccelX100:   peakX100,
        durationMs:      dur,
        timestampLast4:  ts4,
      );
    } catch (_) {
      return null;
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  //  Correlation
  // ════════════════════════════════════════════════════════════════════════

  CorrelationResult _correlate(TapSignatureLocal local, RemoteTapData remote) {
    // Time correlation: compare local tap time vs when the remote event ARRIVED
    // locally — both use the same device's clock, so cross-device skew doesn't
    // affect the result.  For a simultaneous tap the delta equals BLE latency
    // + FFF3 poll delay (≤ ~200ms).
    final timeDelta = (local.timestampMs - remote.receivedAtMs).abs();

    final timeScore = timeDelta <= maxTimeDeltaMs
        ? (1.0 - timeDelta / maxTimeDeltaMs) * 100.0
        : 0.0;

    // Magnitude
    final remotePeak = remote.peakAccelX100 / 100.0;
    final magRatio   = remotePeak > 0.01 ? local.peakAccelMs2 / remotePeak : 99.0;
    final magScore   = (magRatio >= minMagnitudeRatio && magRatio <= maxMagnitudeRatio)
        ? (1.0 - log(magRatio).abs() / log(maxMagnitudeRatio)).clamp(0.0, 1.0) * 100.0
        : 0.0;

    // Duration
    final durDelta  = (local.durationMs - remote.durationMs).abs();
    final durScore  = durDelta <= maxDurationDeltaMs
        ? (1.0 - durDelta / maxDurationDeltaMs) * 100.0
        : 0.0;

    final total   = timeScore * wTime + magScore * wMagnitude + durScore * wDuration;
    final matched = total >= minCorrelationScore && timeDelta <= maxTimeDeltaMs;

    return CorrelationResult(
      matched:          matched,
      timeDeltaMs:      timeDelta,
      magnitudeRatio:   magRatio,
      correlationScore: total,
    );
  }

  void _evictStale() {
    final cutoff = DateTime.now().millisecondsSinceEpoch - localTapBufferDurMs;
    _localTapBuffer.removeWhere((s) => s.timestampMs < cutoff);
    _remoteTapBuffer.removeWhere((r) => r.receivedAtMs < cutoff);
  }
}
