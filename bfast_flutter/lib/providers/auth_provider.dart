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

  AuthNotifier(this._api) : super(const AuthIdle());

  Future<String> _getOrCreateDeviceId() async {
    final existing = await _storage.read(key: 'device_id');
    if (existing != null) return existing;
    final newId = _uuid.v4();
    await _storage.write(key: 'device_id', value: newId);
    return newId;
  }

  Future<void> checkAuth() async {
    final isLoggedIn = await _api.isLoggedIn();
    if (!isLoggedIn) {
      state = const AuthIdle();
      return;
    }
    // Restore cached user
    final name    = await _storage.read(key: 'display_name') ?? 'User';
    final phone   = await _storage.read(key: 'phone_number') ?? '';
    final userId  = await _storage.read(key: 'user_id') ?? '';
    state = AuthAuthenticated(UserModel(
      id:            userId,
      phoneNumber:   phone,
      displayName:   name,
      walletBalance: 0.0,
    ));
  }

  Future<void> login(String phoneNumber, String passcode) async {
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
        final data   = response['data'] as Map<String, dynamic>;
        final tokens = data['tokens'] as Map<String, dynamic>;
        final user   = data['user']   as Map<String, dynamic>;

        await _api.saveTokens(
          tokens['accessToken']  as String,
          tokens['refreshToken'] as String,
        );
        await _storage.write(key: 'user_id',       value: user['id'] as String);
        await _storage.write(key: 'display_name',  value: user['displayName'] as String);
        await _storage.write(key: 'phone_number',  value: user['phoneNumber'] as String);

        state = AuthAuthenticated(UserModel(
          id:            user['id']           as String,
          phoneNumber:   user['phoneNumber']  as String,
          displayName:   user['displayName']  as String,
          walletBalance: 0.0,
        ));
      } else {
        state = AuthError(_parseError(response));
      }
    } on Exception catch (e) {
      state = AuthError(_friendlyError(e));
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
        final data   = response['data'] as Map<String, dynamic>;
        final tokens = data['tokens'] as Map<String, dynamic>;
        final user   = data['user']   as Map<String, dynamic>;

        await _api.saveTokens(
          tokens['accessToken']  as String,
          tokens['refreshToken'] as String,
        );
        await _storage.write(key: 'user_id',      value: user['id']          as String);
        await _storage.write(key: 'display_name', value: user['displayName'] as String);
        await _storage.write(key: 'phone_number', value: user['phoneNumber'] as String);

        state = AuthAuthenticated(UserModel(
          id:            user['id']           as String,
          phoneNumber:   user['phoneNumber']  as String,
          displayName:   user['displayName']  as String,
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
    if (msg.contains('404')) {
      return "We couldn't find an account with this phone number.";
    }
    if (msg.contains('409')) {
      return 'An account with this phone number already exists.';
    }
    return 'Something went wrong: ${e.toString().replaceAll('Exception: ', '')}';
  }
}
