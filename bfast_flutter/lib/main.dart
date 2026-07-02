import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'app.dart';

// TODO(H-1): After running `flutter pub add firebase_core firebase_crashlytics`:
//   1. Replace _recordError with FirebaseCrashlytics.instance.recordError
//   2. Add FlutterError.onError = FirebaseCrashlytics.instance.recordFlutterFatalError
//   3. Call Firebase.initializeApp() before runZonedGuarded

void _recordError(Object error, StackTrace stack, {bool fatal = false}) {
  // Placeholder — replace with FirebaseCrashlytics.instance.recordError
  debugPrint('[BFast][FATAL=$fatal] $error\n$stack');
}

void main() {
  runZonedGuarded(() async {
    WidgetsFlutterBinding.ensureInitialized();

    FlutterError.onError = (details) {
      _recordError(details.exception, details.stack ?? StackTrace.empty, fatal: true);
    };

    await SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
    SystemChrome.setSystemUIOverlayStyle(const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: Brightness.light,
    ));

    runApp(const ProviderScope(child: BFastApp()));
  }, (error, stack) => _recordError(error, stack, fatal: true));
}
