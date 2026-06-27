import 'package:logger/logger.dart';

/// Single entry in the protocol transaction log.
///
/// Every state transition, GATT operation, and handshake step produces one
/// of these so the developer can see exactly where a transaction failed.
class ProtocolLogEntry {
  /// Wall-clock time of the event.
  final DateTime timestamp;

  /// Protocol state before the transition (null for the first entry).
  final String? fromState;

  /// Protocol state after the transition.
  final String toState;

  /// Human-readable description of what happened.
  final String message;

  /// Whether this step succeeded.
  final bool success;

  /// Optional extra data (RSSI, device ID, error message, etc.)
  final Map<String, dynamic>? extra;

  const ProtocolLogEntry({
    required this.timestamp,
    this.fromState,
    required this.toState,
    required this.message,
    required this.success,
    this.extra,
  });

  /// Compact debug string for console output.
  ///
  /// Example:
  /// ```
  /// 12:11:02.123  IDLE → ADVERTISING         ✅ Advertising started (BFAST_a3f21c)
  /// 12:11:02.415  ADVERTISING → DISCOVERED   ✅ Receiver found: RSSI=-42, ID=xx:xx
  /// 12:11:02.601  DISCOVERED → CONNECTING     ✅ Connecting to xx:xx:xx
  /// 12:11:03.210  CONNECTING → CONNECTED      ❌ GATT connection timeout
  /// ```
  String toDebugString() {
    final ts = '${timestamp.hour.toString().padLeft(2, '0')}:'
        '${timestamp.minute.toString().padLeft(2, '0')}:'
        '${timestamp.second.toString().padLeft(2, '0')}.'
        '${timestamp.millisecond.toString().padLeft(3, '0')}';
    final icon = success ? '✅' : '❌';
    final from = fromState ?? '—';
    final transition = '$from → $toState'.padRight(35);
    return '$ts  $transition $icon $message';
  }

  /// JSON representation for potential backend reporting.
  Map<String, dynamic> toJson() => {
        'timestamp': timestamp.toIso8601String(),
        'fromState': fromState,
        'toState': toState,
        'message': message,
        'success': success,
        if (extra != null) 'extra': extra,
      };
}

/// Records every protocol state transition for debugging BLE transactions.
///
/// Usage:
/// ```dart
/// final logger = ProtocolLogger();
/// logger.log(from: 'IDLE', to: 'ADVERTISING', message: 'Started advertising');
/// logger.log(from: 'ADVERTISING', to: 'DISCOVERED', message: 'Found device', extra: {'rssi': -42});
/// print(logger.toDebugString());
/// ```
class ProtocolLogger {
  final List<ProtocolLogEntry> _entries = [];
  final Logger _console = Logger(
    printer: PrettyPrinter(methodCount: 0, dateTimeFormat: DateTimeFormat.none, noBoxingByDefault: true),
  );

  /// Unmodifiable view of all log entries.
  List<ProtocolLogEntry> get entries => List.unmodifiable(_entries);

  /// Number of entries recorded.
  int get length => _entries.length;

  /// The most recent entry, or null if empty.
  ProtocolLogEntry? get latest => _entries.isEmpty ? null : _entries.last;

  /// Record a protocol event.
  ///
  /// [from] — State before the transition. Null for the initial event.
  /// [to]   — State after the transition.
  void log({
    String? from,
    required String to,
    required String message,
    bool success = true,
    Map<String, dynamic>? extra,
  }) {
    final entry = ProtocolLogEntry(
      timestamp: DateTime.now(),
      fromState: from,
      toState: to,
      message: message,
      success: success,
      extra: extra,
    );
    _entries.add(entry);

    // Also print to console for development
    if (success) {
      _console.i(entry.toDebugString());
    } else {
      _console.e(entry.toDebugString());
    }
  }

  /// Multi-line debug output of the entire transaction log.
  String toDebugString() {
    if (_entries.isEmpty) return '(no protocol events recorded)';
    return _entries.map((e) => e.toDebugString()).join('\n');
  }

  /// JSON array of all entries.
  List<Map<String, dynamic>> toJson() =>
      _entries.map((e) => e.toJson()).toList();

  /// Clear all entries (e.g., when starting a new transaction).
  void clear() => _entries.clear();

  /// Get all entries for a specific state.
  List<ProtocolLogEntry> entriesForState(String state) =>
      _entries.where((e) => e.toState == state).toList();

  /// Get the first failure entry, if any.
  ProtocolLogEntry? get firstFailure =>
      _entries.cast<ProtocolLogEntry?>().firstWhere(
            (e) => e != null && !e.success,
            orElse: () => null,
          );

  /// Summary: which state was reached before failure (or 'COMPLETED' if all passed).
  String get reachedState {
    final failure = firstFailure;
    if (failure != null) return 'FAILED at ${failure.toState}';
    if (_entries.isEmpty) return 'NOT_STARTED';
    return _entries.last.toState;
  }
}
