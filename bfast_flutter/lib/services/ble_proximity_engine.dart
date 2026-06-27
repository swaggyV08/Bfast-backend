import 'dart:math';

/// Single RSSI sample with timestamp.
class RssiSample {
  final int rssi;
  final int timestampMs;
  const RssiSample(this.rssi, this.timestampMs);
}

/// Fixed-capacity ring buffer for RSSI samples.
class RssiRingBuffer {
  final int maxSize;
  final List<RssiSample> _samples = [];

  RssiRingBuffer(this.maxSize);

  void add(int rssi, int timestampMs) {
    _samples.add(RssiSample(rssi, timestampMs));
    while (_samples.length > maxSize) _samples.removeAt(0);
  }

  void evictOlderThan(int cutoffMs) {
    _samples.removeWhere((s) => s.timestampMs < cutoffMs);
  }

  bool get isEmpty => _samples.isEmpty;
  int  get size    => _samples.length;

  List<RssiSample> getSamples() => List.unmodifiable(_samples);

  double mean() {
    if (_samples.isEmpty) return -100.0;
    return _samples.fold(0.0, (sum, s) => sum + s.rssi) / _samples.length;
  }

  double variance() {
    if (_samples.length < 2) return 0.0;
    final m = mean();
    final sumSq = _samples.fold(0.0, (sum, s) => sum + (s.rssi - m) * (s.rssi - m));
    return sumSq / (_samples.length - 1);
  }

  /// Linear regression slope (dBm per second). Positive = device approaching.
  double trendSlope() {
    if (_samples.length < 3) return 0.0;
    final n     = _samples.length.toDouble();
    final t0    = _samples.first.timestampMs;
    double sumT = 0, sumR = 0, sumTR = 0, sumTT = 0;

    for (final s in _samples) {
      final t = (s.timestampMs - t0) / 1000.0;
      sumT  += t;
      sumR  += s.rssi;
      sumTR += t * s.rssi;
      sumTT += t * t;
    }

    final denom = n * sumTT - sumT * sumT;
    if (denom.abs() < 1e-9) return 0.0;
    return (n * sumTR - sumT * sumR) / denom;
  }

  /// Continuous dwell time above [threshold] dBm (counts backwards from latest).
  int dwellTimeAbove(int threshold, int nowMs) {
    if (_samples.isEmpty) return 0;
    int earliestAbove = nowMs;
    for (int i = _samples.length - 1; i >= 0; i--) {
      if (_samples[i].rssi >= threshold) {
        earliestAbove = _samples[i].timestampMs;
      } else {
        break;
      }
    }
    return earliestAbove < nowMs ? nowMs - earliestAbove : 0;
  }

  int latest() => _samples.isEmpty ? -100 : _samples.last.rssi;
}

/// Production-grade multi-signal BLE proximity engine.
///
/// Computes a proximity confidence score (0–100) from four signals:
///   1. RSSI Strength  (40%) — sigmoid centred at −55 dBm
///   2. RSSI Stability (25%) — inverse rolling variance
///   3. RSSI Trend     (15%) — linear regression slope
///   4. Dwell Time     (20%) — time above noise floor
class BleProximityEngine {
  // ── Weights ─────────────────────────────────────────────────────────────
  static const double wRssi      = 0.40;
  static const double wStability = 0.25;
  static const double wTrend     = 0.15;
  static const double wDwell     = 0.20;

  // ── Sigmoid ──────────────────────────────────────────────────────────────
  static const double sigmoidCenter = -55.0;
  static const double sigmoidK      = 0.20;

  // ── Stability ────────────────────────────────────────────────────────────
  static const double maxUsefulVariance = 100.0;

  // ── Dwell ────────────────────────────────────────────────────────────────
  static const int dwellRssiFloor    = -75;
  static const int dwellFullScoreMs  = 1000;

  // ── Ring buffer ──────────────────────────────────────────────────────────
  static const int ringBufferSize     = 30;
  static const int ringBufferMaxAgeMs = 3000;

  // ── State ────────────────────────────────────────────────────────────────
  final Map<String, RssiRingBuffer> _deviceBuffers = {};

  double proximityScore  = 0.0;
  double rssiScore       = 0.0;
  double stabilityScore  = 0.0;
  double trendScore      = 0.0;
  double dwellScore      = 0.0;
  double rssiSurge       = 0.0;
  String? scoredDeviceId;

  // ════════════════════════════════════════════════════════════════════════
  //  Core API
  // ════════════════════════════════════════════════════════════════════════

  void feedReading(String deviceId, int rssi, int timestampMs) {
    final buffer = _deviceBuffers.putIfAbsent(deviceId, () => RssiRingBuffer(ringBufferSize));
    buffer.add(rssi, timestampMs);
    buffer.evictOlderThan(timestampMs - ringBufferMaxAgeMs);

    final rs = _computeRssiScore(buffer);
    final ss = _computeStabilityScore(buffer);
    final ts = _computeTrendScore(buffer);
    final ds = _computeDwellScore(buffer, timestampMs);
    final surge = _computeRssiSurge(buffer, timestampMs);

    final total = (rs * wRssi + ss * wStability + ts * wTrend + ds * wDwell).clamp(0.0, 100.0);

    rssiScore      = rs;
    stabilityScore = ss;
    trendScore     = ts;
    dwellScore     = ds;
    rssiSurge      = surge;
    proximityScore = total;
    scoredDeviceId = deviceId;
  }

  double getScoreForDevice(String deviceId) {
    final buffer = _deviceBuffers[deviceId];
    if (buffer == null || buffer.isEmpty) return 0.0;
    final nowMs = DateTime.now().millisecondsSinceEpoch;
    return (_computeRssiScore(buffer) * wRssi +
            _computeStabilityScore(buffer) * wStability +
            _computeTrendScore(buffer) * wTrend +
            _computeDwellScore(buffer, nowMs) * wDwell)
        .clamp(0.0, 100.0);
  }

  void clearDevice(String deviceId) {
    _deviceBuffers.remove(deviceId);
    if (scoredDeviceId == deviceId) {
      proximityScore = 0.0;
      rssiScore      = 0.0;
      stabilityScore = 0.0;
      trendScore     = 0.0;
      dwellScore     = 0.0;
      scoredDeviceId = null;
    }
  }

  void clearAll() {
    _deviceBuffers.clear();
    proximityScore  = 0.0;
    rssiScore       = 0.0;
    stabilityScore  = 0.0;
    trendScore      = 0.0;
    dwellScore      = 0.0;
    rssiSurge       = 0.0;
    scoredDeviceId  = null;
  }

  // ════════════════════════════════════════════════════════════════════════
  //  Score computation
  // ════════════════════════════════════════════════════════════════════════

  double _computeRssiScore(RssiRingBuffer buf) {
    final diff    = buf.mean() - sigmoidCenter;
    final sigmoid = 1.0 / (1.0 + exp(-sigmoidK * diff));
    return (sigmoid * 100.0).clamp(0.0, 100.0);
  }

  double _computeStabilityScore(RssiRingBuffer buf) {
    if (buf.size < 3) return 50.0; // neutral warm-start
    final variance = buf.variance();
    return ((1.0 - (variance / maxUsefulVariance).clamp(0.0, 1.0)) * 100.0).clamp(0.0, 100.0);
  }

  double _computeTrendScore(RssiRingBuffer buf) {
    if (buf.size < 3) return 50.0;
    final slope = buf.trendSlope();
    if (slope > 0) {
      return (slope * 20.0).clamp(0.0, 100.0);
    }
    return slope > -2.0 ? 30.0 : 0.0;
  }

  double _computeDwellScore(RssiRingBuffer buf, int nowMs) {
    final dwellMs = buf.dwellTimeAbove(dwellRssiFloor, nowMs);
    return (dwellMs / dwellFullScoreMs * 100.0).clamp(0.0, 100.0);
  }

  double _computeRssiSurge(RssiRingBuffer buf, int nowMs) {
    final samples = buf.getSamples();
    if (samples.length < 4) return 0.0;

    final recent     = samples.where((s) => s.timestampMs >= nowMs - 200).toList();
    final background = samples.where((s) => s.timestampMs < nowMs - 200 && s.timestampMs >= nowMs - 1000).toList();

    if (recent.isEmpty || background.isEmpty) return 0.0;

    final recentAvg     = recent.fold(0.0, (sum, s) => sum + s.rssi) / recent.length;
    final backgroundAvg = background.fold(0.0, (sum, s) => sum + s.rssi) / background.length;
    return (recentAvg - backgroundAvg);
  }
}
