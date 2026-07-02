import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';
import 'package:fluttertoast/fluttertoast.dart';
import 'core/theme/app_theme.dart';
import 'providers/auth_provider.dart';
import 'providers/tap_provider.dart';
import 'screens/auth/login_screen.dart';
import 'screens/auth/register_screen.dart';
import 'screens/home/home_screen.dart';
import 'screens/home/transaction_history_screen.dart';
import 'screens/payment/tap_detection_screen.dart';
import 'screens/payment/payment_entry_screen.dart';
import 'screens/payment/payment_incoming_screen.dart';
import 'screens/payment/result_screen.dart';
import 'screens/sensor_test/sensor_test_screen.dart';

class BFastApp extends ConsumerStatefulWidget {
  const BFastApp({super.key});

  @override
  ConsumerState<BFastApp> createState() => _BFastAppState();
}

class _BFastAppState extends ConsumerState<BFastApp>
    with WidgetsBindingObserver {

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _listenConnectivity();
    _listenBluetooth();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(authProvider.notifier).checkAuth();
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    final notifier = ref.read(tapProvider.notifier);
    switch (state) {
      case AppLifecycleState.paused:
      case AppLifecycleState.detached:
      case AppLifecycleState.hidden:
        notifier.pauseForBackground();
      case AppLifecycleState.resumed:
        notifier.resumeFromBackground();
      case AppLifecycleState.inactive:
        break;
    }
  }

  void _listenConnectivity() {
    Connectivity().onConnectivityChanged.listen((results) {
      if (results.contains(ConnectivityResult.none)) {
        _toast('No internet connection', Colors.red);
      }
    });
  }

  void _listenBluetooth() {
    FlutterBluePlus.adapterState.listen((btState) {
      if (btState == BluetoothAdapterState.off) {
        _toast('Bluetooth is off — please enable it', Colors.red);
        ref.read(tapProvider.notifier).reset();
      } else if (btState == BluetoothAdapterState.on) {
        _toast('Bluetooth enabled', Colors.green);
        ref.read(tapProvider.notifier).resumeFromBackground();
      }
    });
  }

  void _toast(String msg, Color color) {
    Fluttertoast.showToast(
      msg:             msg,
      toastLength:     Toast.LENGTH_LONG,
      gravity:         ToastGravity.BOTTOM,
      backgroundColor: color,
      textColor:       Colors.white,
    );
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title:                      'BFast',
      theme:                      AppTheme.dark,
      debugShowCheckedModeBanner: false,
      home: const LoginScreen(),
      routes: {
        '/login':               (_) => const LoginScreen(),
        '/register':            (_) => const RegisterScreen(),
        '/home':                (_) => const HomeScreen(),
        '/payment/sender':      (_) => const TapDetectionScreen(isSender: true),
        '/payment/receiver':    (_) => const TapDetectionScreen(isSender: false),
        '/payment/entry':       (_) => const PaymentEntryScreen(),
        '/payment/incoming':    (_) => const PaymentIncomingScreen(),
        '/payment/result':      (_) => const ResultScreen(),
        '/sensor-test':         (_) => const SensorTestScreen(),
        '/transaction-history': (_) => const TransactionHistoryScreen(),
      },
    );
  }
}
