import 'dart:io';
import 'package:device_info_plus/device_info_plus.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:uuid/uuid.dart';
import '../services/api_service.dart';
import '../models/user_model.dart';

// ── State ─────────────────────────────────────────────────────────────────

sealed class AuthState {
  const AuthState();
}

class AuthIdle extends AuthState    { const AuthIdle(); }
class AuthLoading extends AuthState { const AuthLoading(); }
class AuthAuthenticated extends AuthState {
  final UserModel user;
  const AuthAuthenticated(this.user);
}
class AuthError extends AuthState {
  final String message;
  const AuthError(this.message);
}

// ── Providers ─────────────────────────────────────────────────────────────

final apiServiceProvider = Provider<ApiService>((ref) => ApiService());

final authProvider = StateNotifierProvider<AuthNotifier, AuthState>(
  (ref) => AuthNotifier(ref.read(apiServiceProvider)),
);

// ── Notifier ──────────────────────────────────────────────────────────────

class AuthNotifier extends StateNotifier<AuthState> {
  final ApiService   _api;
  final FlutterSecureStorage _storage = const FlutterSecureStorage();
  final Uuid _uuid = const Uuid();

  int _failedAttempts = 0;
  DateTime? _lockoutUntil;

  AuthNotifier(this._api) : super(const AuthIdle());

  // Regex for valid UUID format (backend validates deviceId against this).
  static final _uuidPattern = RegExp(
    r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
    caseSensitive: false,
  );

  Future<String> _getOrCreateDeviceId() async {
    try {
      final existing = await _storage.read(key: 'device_id');
      if (existing != null && existing.isNotEmpty) return existing;

      final info = DeviceInfoPlugin();
      String? stableId;
      if (Platform.isAndroid) {
        stableId = (await info.androidInfo).id;
      } else if (Platform.isIOS) {
        stableId = (await info.iosInfo).identifierForVendor;
      }

      final String newId;
      if (stableId != null && stableId.isNotEmpty) {
        // iOS identifierForVendor is already a UUID — use it directly.
        // Android .id is a short hex string (e.g. "e56b4cef05e19c5a"), NOT a UUID.
        // Convert it to a deterministic UUID v5 so backend UUID validation passes.
        newId = _uuidPattern.hasMatch(stableId)
            ? stableId.toLowerCase()
            : _uuid.v5(Namespace.url.value, 'bfast:device:$stableId');
      } else {
        newId = _uuid.v4();
      }

      await _storage.write(key: 'device_id', value: newId);
      return newId;
    } catch (e) {
      return _uuid.v4();
    }
  }

  Future<void> checkAuth() async {
    try {
      final isLoggedIn = await _api.isLoggedIn();
      if (!isLoggedIn) {
        state = const AuthIdle();
        return;
      }
      final name   = await _storage.read(key: 'display_name') ?? '';
      final phone  = await _storage.read(key: 'phone_number') ?? '';
      final userId = await _storage.read(key: 'user_id') ?? '';
      state = AuthAuthenticated(UserModel(
        id:            userId,
        phoneNumber:   phone,
        displayName:   name,
        walletBalance: 0.0,
      ));
    } catch (e) {
      state = const AuthIdle();
    }
  }

  Future<void> login(String phoneNumber, String passcode) async {
    // Rate limiting: lock out after 5 consecutive failures for 30 seconds.
    if (_lockoutUntil != null && DateTime.now().isBefore(_lockoutUntil!)) {
      final remaining = _lockoutUntil!.difference(DateTime.now()).inSeconds;
      state = AuthError('Too many failed attempts. Please wait ${remaining}s.');
      return;
    }

    // Input validation
    if (phoneNumber.trim().isEmpty) {
      state = const AuthError('Please enter your phone number to log in.');
      return;
    }
    if (passcode.length != 4 || !RegExp(r'^\d{4}$').hasMatch(passcode)) {
      state = const AuthError('Your passcode must be exactly 4 digits.');
      return;
    }

    state = const AuthLoading();
    try {
      final formatted  = phoneNumber.startsWith('+91') ? phoneNumber : '+91$phoneNumber';
      final deviceId   = await _getOrCreateDeviceId();
      final response   = await _api.login(formatted, passcode, deviceId);

      if (response['success'] == true) {
        final data   = response['data']         as Map<String, dynamic>? ?? {};
        final tokens = data['tokens']           as Map<String, dynamic>? ?? {};
        final user   = data['user']             as Map<String, dynamic>? ?? {};
        final access  = tokens['accessToken']   as String?;
        final refresh = tokens['refreshToken']  as String?;
        if (access == null || user['id'] == null) {
          state = const AuthError('Unexpected server response. Please try again.');
          return;
        }
        await _api.saveTokens(access, refresh ?? '');
        await _storage.write(key: 'user_id',      value: user['id']          as String? ?? '');
        await _storage.write(key: 'display_name', value: user['displayName'] as String? ?? '');
        await _storage.write(key: 'phone_number', value: user['phoneNumber'] as String? ?? '');

        _failedAttempts = 0;
        _lockoutUntil   = null;
        state = AuthAuthenticated(UserModel(
          id:            user['id']           as String? ?? '',
          phoneNumber:   user['phoneNumber']  as String? ?? '',
          displayName:   user['displayName']  as String? ?? '',
          walletBalance: 0.0,
        ));
      } else {
        _onLoginFailure();
        state = AuthError(_parseError(response));
      }
    } on Exception catch (e) {
      _onLoginFailure();
      state = AuthError(_friendlyError(e));
    }
  }

  void _onLoginFailure() {
    _failedAttempts++;
    if (_failedAttempts >= 5) {
      _lockoutUntil = DateTime.now().add(const Duration(seconds: 30));
    }
  }

  Future<void> register(
    String phoneNumber, String passcode, String confirmPasscode, String displayName,
  ) async {
    if (phoneNumber.trim().isEmpty) {
      state = const AuthError('Please enter your phone number.');
      return;
    }
    if (displayName.trim().length < 2) {
      state = const AuthError('Display name must be at least 2 characters.');
      return;
    }
    if (passcode.length != 4 || !RegExp(r'^\d{4}$').hasMatch(passcode)) {
      state = const AuthError('Passcode must be exactly 4 digits.');
      return;
    }
    if (passcode != confirmPasscode) {
      state = const AuthError('Passcodes do not match.');
      return;
    }

    state = const AuthLoading();
    try {
      final formatted = phoneNumber.startsWith('+91') ? phoneNumber : '+91$phoneNumber';
      final deviceId  = await _getOrCreateDeviceId();
      final response  = await _api.register(
        formatted, passcode, confirmPasscode, displayName.trim(), deviceId,
      );

      if (response['success'] == true) {
        final data   = response['data']        as Map<String, dynamic>? ?? {};
        final tokens = data['tokens']          as Map<String, dynamic>? ?? {};
        final user   = data['user']            as Map<String, dynamic>? ?? {};
        final access  = tokens['accessToken']  as String?;
        final refresh = tokens['refreshToken'] as String?;
        if (access == null || user['id'] == null) {
          state = const AuthError('Unexpected server response. Please try again.');
          return;
        }
        await _api.saveTokens(access, refresh ?? '');
        await _storage.write(key: 'user_id',      value: user['id']          as String? ?? '');
        await _storage.write(key: 'display_name', value: user['displayName'] as String? ?? '');
        await _storage.write(key: 'phone_number', value: user['phoneNumber'] as String? ?? '');

        state = AuthAuthenticated(UserModel(
          id:            user['id']           as String? ?? '',
          phoneNumber:   user['phoneNumber']  as String? ?? '',
          displayName:   user['displayName']  as String? ?? '',
          walletBalance: 0.0,
        ));
      } else {
        state = AuthError(_parseError(response));
      }
    } on Exception catch (e) {
      state = AuthError(_friendlyError(e));
    }
  }

  Future<void> logout() async {
    await _api.clearTokens();
    state = const AuthIdle();
  }

  void resetError() {
    if (state is AuthError) state = const AuthIdle();
  }

  String _parseError(Map<String, dynamic> response) {
    final err = response['error'] as Map<String, dynamic>?;
    return err?['message'] as String? ??
        'Something went wrong. Please try again.';
  }

  String _friendlyError(Exception e) {
    final msg = e.toString().toLowerCase();
    if (msg.contains('connection') || msg.contains('socket') || msg.contains('reach')) {
      return 'Could not reach the BFast server. Make sure your phone and the server '
          'are on the same WiFi network.';
    }
    if (msg.contains('401') || msg.contains('unauthorized')) {
      return 'Incorrect phone number or passcode. Please try again.';
    }
    if (msg.contains('400')) {
      return 'Invalid request. Please check your phone number format (+91XXXXXXXXXX) and try again.';
    }
    if (msg.contains('404')) {
      return "We couldn't find an account with this phone number.";
    }
    if (msg.contains('409')) {
      return 'An account with this phone number already exists.';
    }
    return 'Something went wrong. Please try again.';
  }
}
