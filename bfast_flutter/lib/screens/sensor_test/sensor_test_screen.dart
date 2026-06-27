import 'dart:async';
import 'dart:math';
import 'package:flutter/material.dart';
import 'package:sensors_plus/sensors_plus.dart';
import '../../core/theme/app_theme.dart';
import '../../services/tap_detection_service.dart';

class SensorTestScreen extends StatefulWidget {
  const SensorTestScreen({super.key});

  @override
  State<SensorTestScreen> createState() => _SensorTestScreenState();
}

class _SensorTestScreenState extends State<SensorTestScreen> {
  // Raw sensor values
  double _ax = 0, _ay = 0, _az = 0;
  double _gx = 0, _gy = 0, _gz = 0;
  double _magnitude = 0;
  double _impulse   = 0;

  bool _running = false;
  final List<String> _log = [];

  StreamSubscription<AccelerometerEvent>? _accelSub;
  StreamSubscription<GyroscopeEvent>?     _gyroSub;

  late final TapDetectionService _tapDetector;

  @override
  void initState() {
    super.initState();
    _tapDetector = TapDetectionService(
      onTapDetected: (sig) {
        if (!mounted) return;
        final time = TimeOfDay.now().format(context);
        setState(() {
          _log.insert(0,
            '[$time] ${sig.tapType.name}  '
            '${sig.peakAccelMs2.toStringAsFixed(2)} m/s²  '
            '${sig.durationMs}ms  '
            'conf ${(sig.confidence * 100).toStringAsFixed(0)}%',
          );
          if (_log.length > 20) _log.removeLast();
        });
      },
    );
    _tapDetector.armed = true;
  }

  void _startSensors() {
    if (_running) return;
    setState(() => _running = true);

    _accelSub = accelerometerEventStream(
      samplingPeriod: const Duration(microseconds: 2500),
    ).listen((e) {
      _tapDetector.processAccel(e.x, e.y, e.z);
      if (mounted) setState(() {
        _ax = e.x; _ay = e.y; _az = e.z;
        _magnitude = sqrt(e.x * e.x + e.y * e.y + e.z * e.z);
        _impulse   = _tapDetector.liveImpulse;
      });
    });

    _gyroSub = gyroscopeEventStream(
      samplingPeriod: const Duration(microseconds: 2500),
    ).listen((e) {
      _tapDetector.processGyro(e.x, e.y, e.z);
      if (mounted) setState(() { _gx = e.x; _gy = e.y; _gz = e.z; });
    });
  }

  void _stopSensors() {
    _accelSub?.cancel();
    _gyroSub?.cancel();
    _accelSub = null;
    _gyroSub  = null;
    setState(() => _running = false);
  }

  @override
  void dispose() {
    _stopSensors();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppTheme.background,
      appBar: AppBar(
        backgroundColor: AppTheme.background,
        title: const Text('Sensor Test'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => Navigator.of(context).pop(),
        ),
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            // Live values card
            _Card(
              title: 'Live Readings',
              child: Column(
                children: [
                  _SensorRow('Accel X', _ax, 'm/s²'),
                  _SensorRow('Accel Y', _ay, 'm/s²'),
                  _SensorRow('Accel Z', _az, 'm/s²'),
                  const Divider(color: Colors.white10, height: 20),
                  _SensorRow('Gyro X',  _gx, 'rad/s'),
                  _SensorRow('Gyro Y',  _gy, 'rad/s'),
                  _SensorRow('Gyro Z',  _gz, 'rad/s'),
                  const Divider(color: Colors.white10, height: 20),
                  _SensorRow('|a| magnitude', _magnitude, 'm/s²', highlight: true),
                  _SensorRow('Impulse',        _impulse,   'm/s²', highlight: true),
                ],
              ),
            ),

            const SizedBox(height: 16),

            // Start / Stop button
            SizedBox(
              width: double.infinity,
              height: 52,
              child: ElevatedButton.icon(
                style: ElevatedButton.styleFrom(
                  backgroundColor: _running ? AppTheme.error : AppTheme.primary,
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                ),
                icon: Icon(_running ? Icons.stop : Icons.play_arrow),
                label: Text(
                  _running ? 'Stop Sensors' : 'Start Sensors',
                  style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
                ),
                onPressed: _running ? _stopSensors : _startSensors,
              ),
            ),

            const SizedBox(height: 16),

            // Tap event log
            _Card(
              title: 'Tap Events (${_log.length})',
              child: _log.isEmpty
                ? const Padding(
                    padding: EdgeInsets.symmetric(vertical: 12),
                    child: Text(
                      'No taps detected yet.\nStart sensors and tap the phone.',
                      textAlign: TextAlign.center,
                      style: TextStyle(color: AppTheme.textSecondary, fontSize: 13),
                    ),
                  )
                : Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: _log.map((entry) => Padding(
                      padding: const EdgeInsets.only(bottom: 8),
                      child: Text(
                        entry,
                        style: const TextStyle(
                          color: AppTheme.textPrimary,
                          fontSize: 12,
                          fontFamily: 'monospace',
                        ),
                      ),
                    )).toList(),
                  ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Card extends StatelessWidget {
  final String title;
  final Widget child;
  const _Card({required this.title, required this.child});

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(16),
    decoration: BoxDecoration(
      color:        AppTheme.surface,
      borderRadius: BorderRadius.circular(16),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(title, style: const TextStyle(
          color: AppTheme.textPrimary, fontSize: 14, fontWeight: FontWeight.w600,
        )),
        const SizedBox(height: 12),
        child,
      ],
    ),
  );
}

class _SensorRow extends StatelessWidget {
  final String label;
  final double value;
  final String unit;
  final bool   highlight;
  const _SensorRow(this.label, this.value, this.unit, {this.highlight = false});

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 3),
    child: Row(
      children: [
        Expanded(
          child: Text(label, style: TextStyle(
            color: highlight ? AppTheme.textPrimary : AppTheme.textSecondary,
            fontSize: 13,
          )),
        ),
        Text(
          '${value.toStringAsFixed(3)} $unit',
          style: TextStyle(
            color:      highlight ? AppTheme.primary : AppTheme.textPrimary,
            fontSize:   13,
            fontWeight: highlight ? FontWeight.w700 : FontWeight.w400,
            fontFamily: 'monospace',
          ),
        ),
      ],
    ),
  );
}
