class AppConstants {
  AppConstants._();

  // ── Backend ──────────────────────────────────────────────────────────
  static const String baseUrl = 'http://192.168.88.13:3000/api/v1';

  // ── BLE UUIDs ────────────────────────────────────────────────────────
  static const String bleServiceUuid      = '0000FFF0-0000-1000-8000-00805F9B34FB';
  /// HandshakeChar — SENDER writes all messages to RECEIVER (WRITE + WRITE_NO_RESPONSE)
  static const String bleHandshakeCharUuid = '0000FFF2-0000-1000-8000-00805F9B34FB';
  /// ResponseChar — RECEIVER stores responses/events; SENDER polls via READ.
  /// Replaces NOTIFY entirely — no CCCD, no Samsung BLE quirks.
  /// Format: [seqNum:1, msgType:1, ...payload]
  static const String bleResponseCharUuid  = '0000FFF3-0000-1000-8000-00805F9B34FB';
  static const String bleDeviceNamePrefix  = 'BFAST_';

  // ── GATT message type bytes ───────────────────────────────────────────
  static const int gattMsgCapabilities = 0x05;
  static const int gattMsgPublicKey    = 0x01;
  static const int gattMsgSessionInfo  = 0x02;
  static const int gattMsgSessionAck   = 0x03;
  static const int gattMsgMotionEvent  = 0x04;
  /// Receiver is already in a session — sender should try another device.
  static const int gattMsgBusy         = 0x06;
  /// UWB NI discovery token exchange (iOS ↔ iOS optional step 7.5, after ACK).
  /// Payload: UTF-8 bytes of hex-encoded NIDiscoveryToken archive.
  /// Android devices that do not support UWB will never send or handle this type.
  static const int gattMsgUwbToken     = 0x07;

  /// Incremented when the on-wire GATT framing changes in a breaking way.
  static const int protocolVersion     = 1;

  // ── Native method/event channels ─────────────────────────────────────
  static const String bleAdvertChannel  = 'com.bfast.app/ble_advertise';
  static const String gattServerChannel = 'com.bfast.app/gatt_server';
  static const String gattServerEvents  = 'com.bfast.app/gatt_server/events';
  static const String uwbMethodChannel  = 'com.bfast.app/uwb';
  static const String uwbEventChannel   = 'com.bfast.app/uwb/events';

  // ── Proximity thresholds ─────────────────────────────────────────────
  static const int    rssiDetectionZone        = -70;
  static const int    rssiTapZone              = -60;
  static const double proximityScoreDetection  = 25.0;
  static const double proximityScoreTapZone    = 45.0;

  // ── UWB thresholds ────────────────────────────────────────────────────
  static const double uwbDetectionRangeM = 0.10;
  static const double uwbTapRangeM       = 0.05;

  // ── Tap detection parameters ──────────────────────────────────────────
  static const double tapMinImpulse        = 0.08;
  static const double tapMaxImpulse        = 80.0;
  static const double tapThresholdCeiling  = 0.45;
  static const int    tapMaxDurationMs     = 500;
  static const int    tapDebouncMs         = 200;
  static const double gravityTimeConstant  = 2.0;
  static const int    shakeWindowMs        = 1000;
  static const int    shakeMaxCrossings    = 10;
  static const double gyroSustainedThreshold = 4.0;
  static const int    gyroSustainedMs      = 400;
  static const int    noiseCooldownMs      = 800;
  static const double zScoreMultiplier     = 1.2;

  // ── Session / Protocol timing ─────────────────────────────────────────
  static const int tapPollIntervalMs      = 200;
  static const int sessionTimeoutMs       = 30000;  // 30s session lifetime
  static const int tapConfirmTimeoutMs    = 5000;
  static const int handshakeTimeoutMs     = 20000;  // max time for ECDH handshake (3×5s polls + crypto)
  static const int tapArmWindowMs         = 30000;  // IMU armed for 30s after session ready
  static const int mutualTapWindowMs      = 300;    // both phones must tap within 300ms

  // ── Wallet refresh ───────────────────────────────────────────────────
  static const int walletRefreshIntervalSec = 0; // no polling — on-demand only
}
