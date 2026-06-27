import 'package:flutter_test/flutter_test.dart';
import 'package:bfast_flutter/services/ble_proximity_engine.dart';

void main() {
  group('BleProximityEngine', () {
    late BleProximityEngine engine;

    setUp(() => engine = BleProximityEngine());

    int nowMs() => DateTime.now().millisecondsSinceEpoch;

    // Feed N identical RSSI readings to one device
    void feedN(String id, int rssi, int count) {
      final start = nowMs();
      for (int i = 0; i < count; i++) {
        engine.feedReading(id, rssi, start + i * 100);
      }
    }

    // ── RSSI Score (sigmoid) ──────────────────────────────────────────
    group('RSSI Score — sigmoid centred at -55 dBm', () {
      test('RSSI -30 dBm (touching) → rssiScore ≥ 90', () {
        feedN('dev1', -30, 5);
        expect(engine.rssiScore, greaterThanOrEqualTo(90));
      });

      test('RSSI -55 dBm (sigmoid centre) → rssiScore near 50', () {
        feedN('dev1', -55, 5);
        expect(engine.rssiScore, closeTo(50.0, 5.0));
      });

      test('RSSI -75 dBm (1 m) → rssiScore ≤ 15', () {
        feedN('dev1', -75, 5);
        expect(engine.rssiScore, lessThanOrEqualTo(15));
      });

      test('RSSI -100 dBm (far away) → rssiScore near 0', () {
        feedN('dev1', -100, 5);
        expect(engine.rssiScore, lessThan(5));
      });
    });

    // ── Stability Score ───────────────────────────────────────────────
    group('Stability Score — inverse variance', () {
      test('consistent RSSI → stabilityScore ≥ 80', () {
        feedN('dev1', -45, 10);
        expect(engine.stabilityScore, greaterThanOrEqualTo(80));
      });

      test('highly variable RSSI → stabilityScore ≤ 40', () {
        final start = nowMs();
        for (int i = 0; i < 20; i++) {
          engine.feedReading('dev1', i.isEven ? -30 : -80, start + i * 100);
        }
        expect(engine.stabilityScore, lessThanOrEqualTo(40));
      });

      test('< 3 samples → neutral stability score (50)', () {
        engine.feedReading('dev1', -45, nowMs());
        expect(engine.stabilityScore, equals(50.0));
      });
    });

    // ── Trend Score ───────────────────────────────────────────────────
    group('Trend Score — linear regression slope', () {
      test('rising RSSI (device approaching) → trendScore > 50', () {
        final start = nowMs();
        for (int i = 0; i < 10; i++) {
          engine.feedReading('dev1', -80 + i * 3, start + i * 200);
        }
        expect(engine.trendScore, greaterThan(50));
      });

      test('falling RSSI (device receding) → trendScore near 0', () {
        final start = nowMs();
        for (int i = 0; i < 10; i++) {
          engine.feedReading('dev1', -30 - i * 4, start + i * 200);
        }
        expect(engine.trendScore, lessThanOrEqualTo(30));
      });

      test('< 3 samples → neutral trend score (50)', () {
        engine.feedReading('dev1', -45, nowMs());
        engine.feedReading('dev1', -45, nowMs() + 100);
        expect(engine.trendScore, equals(50.0));
      });
    });

    // ── Dwell Score ───────────────────────────────────────────────────
    group('Dwell Score', () {
      test('device above -75 dBm for ≥ 1000ms → dwellScore = 100', () {
        final start = nowMs();
        for (int i = 0; i < 12; i++) {
          engine.feedReading('dev1', -50, start + i * 100);
        }
        expect(engine.dwellScore, closeTo(100.0, 10.0));
      });

      test('single sample → dwellScore near 0', () {
        engine.feedReading('dev1', -50, nowMs());
        expect(engine.dwellScore, lessThan(20));
      });

      test('device below dwell floor (-75 dBm) → dwellScore = 0', () {
        feedN('dev1', -90, 5);
        expect(engine.dwellScore, equals(0.0));
      });
    });

    // ── Total score ───────────────────────────────────────────────────
    group('Total proximity score', () {
      test('device at ~5 cm (RSSI -40, stable) → total score > 65', () {
        final start = nowMs();
        for (int i = 0; i < 15; i++) {
          engine.feedReading('dev1', -40, start + i * 100);
        }
        expect(engine.proximityScore, greaterThan(65));
      });

      test('device at ~2 m (RSSI -80, variable) → total score < 30', () {
        final start = nowMs();
        for (int i = 0; i < 10; i++) {
          engine.feedReading('dev1', -80 + (i % 2 == 0 ? 5 : -5), start + i * 200);
        }
        expect(engine.proximityScore, lessThan(30));
      });

      test('score is always in range 0–100', () {
        feedN('dev1', -30, 20);
        expect(engine.proximityScore, inInclusiveRange(0.0, 100.0));
        engine.clearAll();
        feedN('dev1', -100, 20);
        expect(engine.proximityScore, inInclusiveRange(0.0, 100.0));
      });
    });

    // ── Multi-device ──────────────────────────────────────────────────
    group('Multi-device independence', () {
      test('scores are independent per device', () {
        final start = nowMs();
        for (int i = 0; i < 10; i++) {
          engine.feedReading('dev_close', -35, start + i * 100);
          engine.feedReading('dev_far',   -80, start + i * 100);
        }
        final closeScore = engine.getScoreForDevice('dev_close');
        final farScore   = engine.getScoreForDevice('dev_far');
        expect(closeScore, greaterThan(farScore));
      });

      test('clearDevice removes only that device', () {
        feedN('dev1', -40, 5);
        feedN('dev2', -40, 5);
        engine.clearDevice('dev1');
        expect(engine.getScoreForDevice('dev1'), equals(0.0));
        expect(engine.getScoreForDevice('dev2'), greaterThan(0.0));
      });

      test('clearAll resets all scores', () {
        feedN('dev1', -40, 5);
        feedN('dev2', -50, 5);
        engine.clearAll();
        expect(engine.proximityScore, equals(0.0));
        expect(engine.getScoreForDevice('dev1'), equals(0.0));
        expect(engine.getScoreForDevice('dev2'), equals(0.0));
      });
    });

    // ── Ring buffer ───────────────────────────────────────────────────
    group('RssiRingBuffer', () {
      test('respects maxSize — no more than 30 samples', () {
        final buf = RssiRingBuffer(30);
        for (int i = 0; i < 50; i++) {
          buf.add(-50, nowMs() + i * 100);
        }
        expect(buf.size, equals(30));
      });

      test('evictOlderThan removes stale samples', () {
        final buf = RssiRingBuffer(30);
        final old = nowMs() - 5000;
        buf.add(-50, old);
        buf.add(-50, nowMs());
        buf.evictOlderThan(nowMs() - 3000);
        expect(buf.size, equals(1));
      });

      test('mean returns -100 for empty buffer', () {
        final buf = RssiRingBuffer(30);
        expect(buf.mean(), equals(-100.0));
      });

      test('variance returns 0 for < 2 samples', () {
        final buf = RssiRingBuffer(30);
        buf.add(-50, nowMs());
        expect(buf.variance(), equals(0.0));
      });

      test('trendSlope returns 0 for < 3 samples', () {
        final buf = RssiRingBuffer(30);
        buf.add(-50, nowMs());
        buf.add(-50, nowMs() + 100);
        expect(buf.trendSlope(), equals(0.0));
      });

      test('dwellTimeAbove returns 0 for empty buffer', () {
        final buf = RssiRingBuffer(30);
        expect(buf.dwellTimeAbove(-75, nowMs()), equals(0));
      });

      test('latest returns -100 for empty buffer', () {
        final buf = RssiRingBuffer(30);
        expect(buf.latest(), equals(-100));
      });

      test('mean is correct for known values', () {
        final buf = RssiRingBuffer(30);
        final now = nowMs();
        buf.add(-50, now);
        buf.add(-60, now + 100);
        buf.add(-70, now + 200);
        expect(buf.mean(), closeTo(-60.0, 0.01));
      });
    });

    // ── RSSI Surge ────────────────────────────────────────────────────
    group('RSSI Surge', () {
      test('surge > 0 when device rapidly approaches', () {
        final start = nowMs();
        // Background: low RSSI
        for (int i = 0; i < 8; i++) {
          engine.feedReading('dev1', -70, start + i * 100);
        }
        // Recent: high RSSI
        for (int i = 8; i < 11; i++) {
          engine.feedReading('dev1', -40, start + i * 100);
        }
        expect(engine.rssiSurge, greaterThan(0));
      });

      test('surge near 0 for stationary device', () {
        final start = nowMs();
        for (int i = 0; i < 15; i++) {
          engine.feedReading('dev1', -50, start + i * 100);
        }
        // Minor fluctuation only
        expect(engine.rssiSurge.abs(), lessThan(5));
      });
    });

    // ── getScoreForDevice ─────────────────────────────────────────────
    group('getScoreForDevice', () {
      test('returns 0 for unknown device', () {
        expect(engine.getScoreForDevice('unknown'), equals(0.0));
      });

      test('returns positive score after feeding readings', () {
        feedN('dev1', -45, 8);
        expect(engine.getScoreForDevice('dev1'), greaterThan(0));
      });
    });
  });
}
