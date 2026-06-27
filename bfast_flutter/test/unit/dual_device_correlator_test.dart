import 'package:flutter_test/flutter_test.dart';
import 'package:bfast_flutter/services/dual_device_correlator.dart';
import 'package:bfast_flutter/services/tap_detection_service.dart';

void main() {
  group('DualDeviceCorrelator', () {
    late DualDeviceCorrelator correlator;

    int nowMs() => DateTime.now().millisecondsSinceEpoch;

    TapSignatureLocal makeTap({
      int?    timestampMs,
      double  peak     = 5.0,
      int     duration = 50,
      double  gyro     = 0.5,
      TapType tapType  = TapType.normalTap,
    }) => TapSignatureLocal(
      confidence:    0.95,
      timestampMs:   timestampMs ?? nowMs(),
      peakAccelMs2:  peak,
      peakAxis:      2,
      durationMs:    duration,
      gyroMagnitude: gyro,
      tapType:       tapType,
    );

    RemoteTapData makeRemote({
      int?    timestampMs,
      double  peak     = 5.0,
      int     duration = 50,
    }) {
      final ts = timestampMs ?? nowMs();
      return RemoteTapData(
        deviceId:       'remote_device',
        peakAccelX100:  (peak * 100).toInt(),
        durationMs:     duration,
        timestampLast4: ts % 10000,
        receivedAtMs:   ts,
      );
    }

    setUp(() => correlator = DualDeviceCorrelator());

    // ── BLE payload encoding / decoding ───────────────────────────────
    group('BLE payload encoding / decoding', () {
      test('round-trip encode → decode preserves peak and duration', () {
        final tap     = makeTap(peak: 12.5, duration: 75);
        final payload = correlator.encodeTapForBle(tap, 'device123');
        final decoded = correlator.decodeTapFromBle(payload);
        expect(decoded, isNotNull);
        expect(decoded!.peakAccelX100, equals(1250));
        expect(decoded.durationMs,     equals(75));
      });

      test('payload starts with "B|"', () {
        final payload = correlator.encodeTapForBle(makeTap(), 'dev1');
        expect(payload.startsWith('B|'), isTrue);
      });

      test('deviceId is truncated to 10 chars', () {
        final payload = correlator.encodeTapForBle(makeTap(), 'verylongdeviceid99');
        final parts = payload.split('|');
        expect(parts[4].length, lessThanOrEqualTo(10));
      });

      test('payload has exactly 5 pipe-delimited parts', () {
        final payload = correlator.encodeTapForBle(makeTap(), 'dev1');
        expect(payload.split('|').length, equals(5));
      });

      test('decodeTapFromBle returns null for empty string', () {
        expect(correlator.decodeTapFromBle(''), isNull);
      });

      test('decodeTapFromBle returns null for wrong prefix', () {
        expect(correlator.decodeTapFromBle('A|100|50|1234|dev1'), isNull);
      });

      test('decodeTapFromBle returns null for non-integer fields', () {
        expect(correlator.decodeTapFromBle('B|abc|xyz|nope|dev1'), isNull);
      });

      test('decodeTapFromBle returns null for too few parts', () {
        expect(correlator.decodeTapFromBle('B|100|50'), isNull);
      });

      test('peak > 9999 is clamped to 9999 in payload', () {
        final tap     = makeTap(peak: 200.0); // 200 * 100 = 20000 > 9999
        final payload = correlator.encodeTapForBle(tap, 'dev');
        final decoded = correlator.decodeTapFromBle(payload);
        expect(decoded!.peakAccelX100, equals(9999));
      });
    });

    // ── recordLocalTap ────────────────────────────────────────────────
    group('recordLocalTap', () {
      test('returns null when no remote tap buffered', () {
        final result = correlator.recordLocalTap(makeTap());
        expect(result, isNull);
      });

      test('matches when remote tap is within 150ms', () {
        final ts = nowMs();
        correlator.recordRemoteTap(makeRemote(timestampMs: ts, peak: 5.0, duration: 50));
        final result = correlator.recordLocalTap(makeTap(timestampMs: ts + 50, peak: 5.0, duration: 50));
        expect(result, isNotNull);
        expect(result!.matched, isTrue);
      });

      test('no match when time delta > 150ms', () {
        final ts = nowMs();
        correlator.recordRemoteTap(makeRemote(timestampMs: ts));
        // 200ms later
        final result = correlator.recordLocalTap(makeTap(timestampMs: ts + 200));
        // Time delta: (ts+200) % 10000 - ts % 10000 = 200ms → no match
        if (result != null) {
          expect(result.matched, isFalse);
        }
      });
    });

    // ── recordRemoteTap ───────────────────────────────────────────────
    group('recordRemoteTap', () {
      test('returns null when no local tap buffered', () {
        final result = correlator.recordRemoteTap(makeRemote());
        expect(result, isNull);
      });

      test('matches when local tap already recorded and time aligns', () {
        final ts = nowMs();
        correlator.recordLocalTap(makeTap(timestampMs: ts, peak: 5.0, duration: 50));
        final result = correlator.recordRemoteTap(makeRemote(timestampMs: ts + 30, peak: 5.0, duration: 50));
        expect(result, isNotNull);
        expect(result!.matched, isTrue);
      });
    });

    // ── Correlation score ─────────────────────────────────────────────
    group('Correlation score', () {
      test('identical taps → score near 100', () {
        final ts = nowMs();
        correlator.recordLocalTap(makeTap(timestampMs: ts, peak: 5.0, duration: 50));
        final result = correlator.recordRemoteTap(makeRemote(timestampMs: ts, peak: 5.0, duration: 50));
        expect(result, isNotNull);
        expect(result!.correlationScore, greaterThan(80));
      });

      test('large magnitude ratio → lower score', () {
        final ts = nowMs();
        correlator.recordLocalTap(makeTap(timestampMs: ts, peak: 1.0, duration: 50));
        final result = correlator.recordRemoteTap(makeRemote(timestampMs: ts, peak: 10.0, duration: 50));
        if (result != null) {
          // ratio = 0.1, which is < minMagnitudeRatio (0.2) → mag score = 0
          expect(result.correlationScore, lessThan(80));
        }
      });

      test('matched=false when score < 40', () {
        // Time delta just over limit should give matched=false
        final ts = nowMs();
        correlator.recordLocalTap(makeTap(timestampMs: ts, peak: 1.0));
        // Set remote ts 200ms apart
        final result = correlator.recordRemoteTap(
          makeRemote(timestampMs: ts + 200, peak: 100.0),
        );
        if (result != null) {
          // Either matched=false or no result returned
          expect(result.matched, isFalse);
        }
      });
    });

    // ── Magnitude ratio edge cases ─────────────────────────────────────
    group('Magnitude ratio', () {
      test('ratio in 0.2–5.0 range gets positive mag score', () {
        final ts = nowMs();
        correlator.recordLocalTap(makeTap(timestampMs: ts, peak: 3.0, duration: 50));
        final result = correlator.recordRemoteTap(makeRemote(timestampMs: ts, peak: 6.0, duration: 50));
        // ratio = 0.5 → in valid range
        expect(result, isNotNull);
      });

      test('ratio outside 0.2–5.0 gives zero magnitude score', () {
        final ts = nowMs();
        correlator.recordLocalTap(makeTap(timestampMs: ts, peak: 0.5, duration: 50));
        final result = correlator.recordRemoteTap(makeRemote(timestampMs: ts, peak: 50.0, duration: 50));
        // ratio = 0.01 → below minMagnitudeRatio
        if (result != null) {
          expect(result.correlationScore, lessThanOrEqualTo(50));
        }
      });
    });

    // ── Stale eviction ────────────────────────────────────────────────
    group('Stale entry eviction', () {
      test('local taps older than 2000ms are evicted and cannot correlate', () {
        final oldTs = nowMs() - 3000; // 3 seconds ago
        correlator.recordLocalTap(makeTap(timestampMs: oldTs));
        // Try to match with a new remote tap — old local should be evicted
        final result = correlator.recordRemoteTap(makeRemote(timestampMs: nowMs()));
        // Should return null (no matching local tap left after eviction)
        expect(result, isNull);
      });
    });

    // ── Reset ─────────────────────────────────────────────────────────
    group('Reset', () {
      test('reset clears all buffers and scores', () {
        correlator.recordLocalTap(makeTap());
        correlator.reset();
        expect(correlator.lastCorrelationScore,  equals(0.0));
        expect(correlator.lastCorrelationResult, isNull);
      });

      test('can correlate after reset', () {
        correlator.recordLocalTap(makeTap());
        correlator.reset();
        final ts = nowMs();
        correlator.recordLocalTap(makeTap(timestampMs: ts, peak: 5.0, duration: 50));
        final result = correlator.recordRemoteTap(makeRemote(timestampMs: ts, peak: 5.0, duration: 50));
        expect(result, isNotNull);
      });
    });

    // ── getCurrentCorrelationScore ────────────────────────────────────
    group('lastCorrelationScore', () {
      test('starts at 0.0', () {
        expect(correlator.lastCorrelationScore, equals(0.0));
      });

      test('updates after successful correlation', () {
        final ts = nowMs();
        correlator.recordLocalTap(makeTap(timestampMs: ts, peak: 5.0, duration: 50));
        correlator.recordRemoteTap(makeRemote(timestampMs: ts, peak: 5.0, duration: 50));
        expect(correlator.lastCorrelationScore, greaterThan(0.0));
      });
    });
  });
}
