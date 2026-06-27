import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';
import 'package:fluttertoast/fluttertoast.dart';
import 'core/theme/app_theme.dart';
import 'navigation/app_router.dart';
import 'providers/auth_provider.dart';
import 'providers/tap_provider.dart';

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
    // Restore auth state from secure storage on every cold start.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(authProvider.notifier).checkAuth();
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  // ── App lifecycle → pause/resume BLE ─────────────────────────────────────

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    final notifier = ref.read(tapProvider.notifier);
    switch (state) {
      case AppLifecycleState.paused:
      case AppLifecycleState.detached:
      case AppLifecycleState.hidden:
        // Stop all BLE activity when app leaves foreground
        notifier.pauseForBackground();
      case AppLifecycleState.resumed:
        notifier.resumeFromBackground();
      case AppLifecycleState.inactive:
        break;
    }
  }

  // ── Connectivity toasts ───────────────────────────────────────────────────

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
        // Re-arm as receiver (or sender) now that BT is back on.
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
    final router = ref.watch(appRouterProvider);
    return MaterialApp.router(
      title:                   'BFast',
      theme:                   AppTheme.dark,
      routerConfig:            router,
      debugShowCheckedModeBanner: false,
    );
  }
}
