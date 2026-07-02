import 'dart:convert';
import 'dart:typed_data';
import 'package:cryptography/cryptography.dart';

// ── GATT message framing ─────────────────────────────────────────────────────

/// First byte of every GATT handshake/motion message.
class GattMsg {
  static const int capabilities = 0x05;
  static const int publicKey    = 0x01;
  static const int sessionInfo  = 0x02;
  static const int sessionAck   = 0x03;
  static const int motionEvent  = 0x04;

  static Uint8List encode(int type, List<int> payload) =>
      Uint8List.fromList([type, ...payload]);

  static (int type, Uint8List payload) decode(List<int> bytes) {
    if (bytes.isEmpty) return (0, Uint8List(0));
    return (bytes[0], Uint8List.fromList(bytes.sublist(1)));
  }
}

// ── Peer info exchanged during session handshake ──────────────────────────────

class PeerInfo {
  final String userId;
  final String displayName;
  final String role;       // 'SENDER' | 'RECEIVER'
  final String sessionId;  // backend session code ID (scId)
  final String deviceId;   // backend-registered device UUID (transmitted via encrypted handshake)

  const PeerInfo({
    required this.userId,
    required this.displayName,
    required this.role,
    required this.sessionId,
    this.deviceId = '',
  });

  Map<String, dynamic> toJson() => {
    'u': userId,
    'd': displayName,
    'r': role,
    's': sessionId,
    'i': deviceId,
  };

  factory PeerInfo.fromJson(Map<String, dynamic> j) => PeerInfo(
    userId:      j['u'] as String? ?? '',
    displayName: j['d'] as String? ?? 'User',
    role:        j['r'] as String? ?? 'RECEIVER',
    sessionId:   j['s'] as String? ?? '',
    deviceId:    j['i'] as String? ?? '',
  );

  Uint8List toBytes() =>
      Uint8List.fromList(utf8.encode(jsonEncode(toJson())));

  static PeerInfo? fromBytes(List<int> bytes) {
    try {
      final json = jsonDecode(utf8.decode(bytes)) as Map<String, dynamic>;
      return PeerInfo.fromJson(json);
    } catch (_) {
      return null;
    }
  }
}

// ── Device capability flags ───────────────────────────────────────────────────

class DeviceCapabilities {
  final bool hasUwb;
  final bool hasHighRateImu;

  const DeviceCapabilities({this.hasUwb = false, this.hasHighRateImu = true});

  int toByte() => (hasUwb ? 0x01 : 0) | (hasHighRateImu ? 0x02 : 0);

  static DeviceCapabilities fromByte(int b) => DeviceCapabilities(
    hasUwb:        (b & 0x01) != 0,
    hasHighRateImu: (b & 0x02) != 0,
  );
}

// ── Secure session: ECDH key exchange + shared AES-256-GCM session ───────────

class SecureSession {
  final String connectionId; // random UUID per BLE connection
  PeerInfo? localInfo;
  PeerInfo? remoteInfo;
  DeviceCapabilities? remoteCapabilities;

  SimpleKeyPair?   _keyPair;
  SimplePublicKey? _remotePublicKey;
  SecretKey?       _sharedKey;

  bool get isReady => remoteInfo != null && _sharedKey != null;
  bool get hasSharedKey => _sharedKey != null;

  SecureSession({required this.connectionId});

  /// Generate ephemeral X25519 key pair and return our public key bytes.
  Future<Uint8List> generateKeyPair() async {
    final algo = X25519();
    _keyPair = await algo.newKeyPair();
    final pub = await _keyPair!.extractPublicKey();
    return Uint8List.fromList(pub.bytes);
  }

  /// Receive the remote's X25519 public key and derive the shared secret.
  Future<void> computeSharedSecret(List<int> remotePubBytes) async {
    if (_keyPair == null) throw StateError('Key pair not generated yet');
    final algo = X25519();
    _remotePublicKey = SimplePublicKey(remotePubBytes, type: KeyPairType.x25519);
    _sharedKey = await algo.sharedSecretKey(
      keyPair:         _keyPair!,
      remotePublicKey: _remotePublicKey!,
    );
  }

  /// Encrypt plaintext with AES-256-GCM using the shared session key.
  Future<Uint8List?> encrypt(Uint8List plaintext) async {
    final key = _sharedKey;
    if (key == null) return null;
    final algo  = AesGcm.with256bits();
    final nonce = algo.newNonce();
    final box   = await algo.encrypt(plaintext, secretKey: key, nonce: nonce);
    // Layout: [nonce:12][ciphertext:N][mac:16]
    return Uint8List.fromList([...nonce, ...box.cipherText, ...box.mac.bytes]);
  }

  /// Decrypt ciphertext produced by [encrypt].
  Future<Uint8List?> decrypt(List<int> ciphertext) async {
    final key = _sharedKey;
    if (key == null || ciphertext.length < 28) return null;
    final nonce  = ciphertext.sublist(0, 12);
    final mac    = Mac(ciphertext.sublist(ciphertext.length - 16));
    final cipher = ciphertext.sublist(12, ciphertext.length - 16);
    final algo   = AesGcm.with256bits();
    try {
      final plain = await algo.decrypt(
        SecretBox(cipher, nonce: nonce, mac: mac),
        secretKey: key,
      );
      return Uint8List.fromList(plain);
    } catch (_) {
      return null;
    }
  }

  void dispose() {
    _keyPair         = null;
    _remotePublicKey = null;
    _sharedKey       = null;
  }
}
