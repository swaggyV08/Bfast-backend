import 'dart:math';
import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../core/constants/app_constants.dart';

class ApiService {
  late final Dio _dio;
  final FlutterSecureStorage _storage = const FlutterSecureStorage();

  ApiService() {
    _dio = Dio(BaseOptions(
      baseUrl:        AppConstants.baseUrl,
      connectTimeout: const Duration(seconds: 15),
      receiveTimeout: const Duration(seconds: 30),
      sendTimeout:    const Duration(seconds: 30),
      headers:        {'Content-Type': 'application/json'},
    ));

    _dio.interceptors.add(_AuthInterceptor(_storage));
    _dio.interceptors.add(LogInterceptor(
      requestBody:  true,
      responseBody: true,
      error:        true,
    ));
  }

  // ── Auth ─────────────────────────────────────────────────────────────────

  Future<Map<String, dynamic>> login(
    String phoneNumber, String passcode, String deviceId,
  ) async {
    final res = await _dio.post('/auth/mobile/login', data: {
      'phoneNumber': phoneNumber,
      'passcode':    passcode,
      'deviceId':    deviceId,
    });
    return _unwrap(res);
  }

  Future<Map<String, dynamic>> register(
    String phoneNumber, String passcode, String confirmPasscode,
    String displayName, String deviceId,
  ) async {
    final res = await _dio.post('/auth/mobile/register', data: {
      'phoneNumber':    phoneNumber,
      'passcode':       passcode,
      'confirmPasscode': confirmPasscode,
      'displayName':    displayName,
      'deviceId':       deviceId,
    });
    return _unwrap(res);
  }

  // ── Session ───────────────────────────────────────────────────────────────

  Future<Map<String, dynamic>> generateSession(String deviceId) async {
    final res = await _dio.post('/session/generate', data: {
      'deviceId': deviceId,
    });
    return _unwrap(res);
  }

  Future<Map<String, dynamic>> getActiveSession() async {
    final res = await _dio.get('/session/active');
    return _unwrap(res);
  }

  Future<void> reportTapEvent({
    required String receiverDeviceId,
    required double accelPeakMs2,
  }) async {
    await _dio.post('/session/tap-event', data: {
      'receiverDeviceId': receiverDeviceId,
      'accelPeakMs2':     accelPeakMs2,
    });
  }

  Future<Map<String, dynamic>> pollTap({required String receiverDeviceId}) async {
    final res = await _dio.get('/session/tap-poll', queryParameters: {
      'receiverDeviceId': receiverDeviceId,
    });
    return _unwrap(res);
  }

  // ── Wallet ────────────────────────────────────────────────────────────────

  Future<Map<String, dynamic>> getWallet() async {
    final res = await _dio.get('/wallet');
    return _unwrap(res);
  }

  Future<Map<String, dynamic>> getTransactionHistory({
    int page  = 1,
    int limit = 20,
  }) async {
    final res = await _dio.get('/transaction/history',
        queryParameters: {'page': page, 'limit': limit});
    return _unwrap(res);
  }

  // ── Transaction ───────────────────────────────────────────────────────────

  Future<Map<String, dynamic>> initiateTransaction({
    required String sessionCodeId,
    required String receiverDeviceId,
    required double amountInr,
  }) async {
    final res = await _dio.post('/transaction', data: {
      'sessionCodeId':    sessionCodeId,
      'receiverDeviceId': receiverDeviceId,
      'amountPaise':      (amountInr * 100).round(),
      'nonce':            _generateNonce(),
      'currency':         'INR',
      'clientInitiatedAt': DateTime.now().toIso8601String(),
    });
    return _unwrap(res);
  }

  static String _generateNonce() {
    final rand  = Random.secure();
    final bytes = List<int>.generate(12, (_) => rand.nextInt(256));
    return bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  }

  // ── Token management ─────────────────────────────────────────────────────

  // Cached after first read; set eagerly on login/logout so all GoRouter
  // redirect calls (which are async but hot-path) return a pre-resolved Future.
  bool? _loggedInCache;

  Future<void> saveTokens(String access, String refresh) async {
    _loggedInCache = true;
    await Future.wait([
      _storage.write(key: 'access_token',  value: access),
      _storage.write(key: 'refresh_token', value: refresh),
    ]);
  }

  Future<String?> getAccessToken() => _storage.read(key: 'access_token');

  Future<void> clearTokens() async {
    _loggedInCache = false;
    await _storage.deleteAll();
  }

  Future<bool> isLoggedIn() async {
    if (_loggedInCache != null) return _loggedInCache!;
    final token = await _storage.read(key: 'access_token');
    _loggedInCache = token != null && token.isNotEmpty;
    return _loggedInCache!;
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  Map<String, dynamic> _unwrap(Response<dynamic> res) {
    final data = res.data as Map<String, dynamic>?;
    if (data == null) throw Exception('Empty response from server');
    return data;
  }
}

class _AuthInterceptor extends Interceptor {
  final FlutterSecureStorage _storage;
  _AuthInterceptor(this._storage);

  @override
  Future<void> onRequest(
    RequestOptions options, RequestInterceptorHandler handler,
  ) async {
    final token = await _storage.read(key: 'access_token');
    if (token != null) {
      options.headers['Authorization'] = 'Bearer $token';
    }
    handler.next(options);
  }

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) {
    if (err.type == DioExceptionType.connectionTimeout ||
        err.type == DioExceptionType.connectionError) {
      return handler.reject(DioException(
        requestOptions: err.requestOptions,
        message: 'Could not reach the BFast server. Make sure your phone '
            'and the server are on the same WiFi network and the server is running.',
        type: DioExceptionType.connectionError,
      ));
    }
    handler.next(err);
  }
}
