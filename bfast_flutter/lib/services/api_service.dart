import 'dart:async';
import 'dart:convert';
import 'dart:math';
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
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
    _dio.interceptors.add(_RefreshInterceptor(_storage, _dio));
    _dio.interceptors.add(_RetryInterceptor(_dio));
    if (kDebugMode) {
      _dio.interceptors.add(_MaskedLogInterceptor());
    }
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
    final nonce = _generateNonce();
    final res = await _dio.post('/transaction',
      data: {
        'sessionCodeId':     sessionCodeId,
        'receiverDeviceId':  receiverDeviceId,
        'amountPaise':       (amountInr * 100).round(),
        'nonce':             nonce,
        'currency':          'INR',
        'clientInitiatedAt': DateTime.now().toIso8601String(),
      },
      options: Options(headers: {'Idempotency-Key': nonce}),
    );
    return _unwrap(res);
  }

  static String _generateNonce() {
    final rand  = Random.secure();
    final bytes = List<int>.generate(12, (_) => rand.nextInt(256));
    return bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  }

  // ── Token management ─────────────────────────────────────────────────────

  bool? _loggedInCache;

  Future<void> saveTokens(String access, String refresh) async {
    _loggedInCache = true;
    await Future.wait([
      _storage.write(key: 'access_token',  value: access),
      _storage.write(key: 'refresh_token', value: refresh),
    ]);
  }

  Future<String?> getAccessToken() => _storage.read(key: 'access_token');

  /// Returns true if the stored access token expires within [thresholdSeconds].
  /// Proactively call this before a payment to force refresh if needed.
  Future<bool> isTokenExpiringSoon({int thresholdSeconds = 60}) async {
    try {
      final token = await _storage.read(key: 'access_token');
      if (token == null) return true;
      final parts = token.split('.');
      if (parts.length != 3) return false;
      final payload = utf8.decode(base64Url.decode(base64Url.normalize(parts[1])));
      final data    = jsonDecode(payload) as Map<String, dynamic>;
      final exp     = data['exp'] as int?;
      if (exp == null) return false;
      final expiresAt = DateTime.fromMillisecondsSinceEpoch(exp * 1000);
      return DateTime.now().add(Duration(seconds: thresholdSeconds)).isAfter(expiresAt);
    } catch (_) {
      return false;
    }
  }

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

// ── Auth interceptor: attaches Bearer token to every request ────────────────

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

// ── Refresh interceptor: on 401 → refresh tokens → retry original ────────────

class _RefreshInterceptor extends Interceptor {
  final FlutterSecureStorage _storage;
  final Dio _dio;

  // When a refresh is in flight, new 401s wait on this completer instead of
  // triggering a second refresh. The completer resolves with the new token
  // (or null on failure) so waiters can retry their original request.
  Completer<String?>? _refreshCompleter;

  _RefreshInterceptor(this._storage, this._dio);

  @override
  Future<void> onError(DioException err, ErrorInterceptorHandler handler) async {
    final statusCode = err.response?.statusCode;
    if (statusCode != 401) {
      handler.next(err);
      return;
    }

    // Another 401 arrived while a refresh is already in progress — queue it.
    if (_refreshCompleter != null) {
      final newToken = await _refreshCompleter!.future;
      if (newToken == null) {
        handler.next(err);
        return;
      }
      final opts = err.requestOptions;
      opts.headers['Authorization'] = 'Bearer $newToken';
      try {
        final retryRes = await _dio.request<dynamic>(
          opts.path,
          data:            opts.data,
          queryParameters: opts.queryParameters,
          options: Options(method: opts.method, headers: opts.headers),
        );
        handler.resolve(retryRes);
      } on DioException catch (e) {
        handler.next(e);
      }
      return;
    }

    _refreshCompleter = Completer<String?>();
    try {
      final refreshToken = await _storage.read(key: 'refresh_token');
      if (refreshToken == null) {
        _refreshCompleter!.complete(null);
        await _clearAndReject(handler, err);
        return;
      }

      final refreshDio = Dio(BaseOptions(
        baseUrl:        AppConstants.baseUrl,
        connectTimeout: const Duration(seconds: 15),
        headers:        {'Content-Type': 'application/json'},
      ));
      final refreshRes = await refreshDio.post('/auth/mobile/refresh', data: {
        'refreshToken': refreshToken,
      });

      final resData    = refreshRes.data as Map<String, dynamic>?;
      final tokens     = (resData?['data'] as Map<String, dynamic>?)?['tokens']
          as Map<String, dynamic>?;
      final newAccess  = tokens?['accessToken']  as String?;
      final newRefresh = tokens?['refreshToken'] as String?;

      if (newAccess == null) {
        _refreshCompleter!.complete(null);
        await _clearAndReject(handler, err);
        return;
      }

      await Future.wait([
        _storage.write(key: 'access_token', value: newAccess),
        if (newRefresh != null)
          _storage.write(key: 'refresh_token', value: newRefresh),
      ]);

      _refreshCompleter!.complete(newAccess);

      final opts = err.requestOptions;
      opts.headers['Authorization'] = 'Bearer $newAccess';
      final retryRes = await _dio.request<dynamic>(
        opts.path,
        data:            opts.data,
        queryParameters: opts.queryParameters,
        options: Options(method: opts.method, headers: opts.headers),
      );
      handler.resolve(retryRes);
    } catch (e) {
      _refreshCompleter?.complete(null);
      await _storage.deleteAll();
      handler.next(err);
    } finally {
      _refreshCompleter = null;
    }
  }

  Future<void> _clearAndReject(
    ErrorInterceptorHandler handler, DioException err,
  ) async {
    await _storage.deleteAll();
    handler.next(err);
  }
}

// ── Retry interceptor: exponential backoff on transient network failures ──────

class _RetryInterceptor extends Interceptor {
  final Dio _dio;
  static const _maxRetries   = 3;
  static const _initialDelay = 200; // ms

  _RetryInterceptor(this._dio);

  @override
  Future<void> onError(DioException err, ErrorInterceptorHandler handler) async {
    final retryCount = err.requestOptions.extra['_retry'] as int? ?? 0;
    final isRetryable = err.type == DioExceptionType.connectionTimeout ||
        err.type == DioExceptionType.connectionError  ||
        err.type == DioExceptionType.sendTimeout      ||
        err.type == DioExceptionType.receiveTimeout;

    if (!isRetryable || retryCount >= _maxRetries) {
      handler.next(err);
      return;
    }

    final delayMs = _initialDelay * (1 << retryCount); // 200 → 400 → 800
    await Future.delayed(Duration(milliseconds: delayMs));

    try {
      final opts = err.requestOptions;
      opts.extra['_retry'] = retryCount + 1;
      final res = await _dio.request<dynamic>(
        opts.path,
        data:            opts.data,
        queryParameters: opts.queryParameters,
        options:         Options(method: opts.method, headers: opts.headers, extra: opts.extra),
      );
      handler.resolve(res);
    } on DioException catch (e) {
      handler.next(e);
    }
  }
}

// ── Debug-only log interceptor: masks sensitive fields ───────────────────────

class _MaskedLogInterceptor extends Interceptor {
  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    final maskedHeaders = Map<String, dynamic>.from(options.headers);
    if (maskedHeaders.containsKey('Authorization')) {
      maskedHeaders['Authorization'] = 'Bearer ***';
    }
    debugPrint('[Dio] --> ${options.method} ${options.path}  headers:$maskedHeaders');
    if (options.data != null) {
      debugPrint('[Dio]     body: ${_mask(options.data)}');
    }
    handler.next(options);
  }

  @override
  void onResponse(Response response, ResponseInterceptorHandler handler) {
    debugPrint('[Dio] <-- ${response.statusCode} ${response.requestOptions.path}');
    debugPrint('[Dio]     body: ${_mask(response.data)}');
    handler.next(response);
  }

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) {
    debugPrint('[Dio] ERR ${err.requestOptions.path}: ${err.message}');
    handler.next(err);
  }

  static const _sensitiveKeys = {
    'accessToken', 'refreshToken', 'passcode', 'token', 'password',
  };

  dynamic _mask(dynamic data) {
    if (data is Map) {
      return data.map((k, v) => _sensitiveKeys.contains(k)
          ? MapEntry(k, '***')
          : MapEntry(k, _mask(v)));
    }
    return data;
  }
}
