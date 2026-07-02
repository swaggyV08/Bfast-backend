import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../providers/auth_provider.dart';
import '../screens/auth/login_screen.dart';
import '../screens/auth/register_screen.dart';
import '../screens/home/home_screen.dart';
import '../screens/payment/tap_detection_screen.dart';
import '../screens/payment/payment_entry_screen.dart';
import '../screens/payment/payment_incoming_screen.dart';
import '../screens/payment/result_screen.dart';
import '../screens/sensor_test/sensor_test_screen.dart';
import '../screens/home/transaction_history_screen.dart';

final appRouterProvider = Provider<GoRouter>((ref) {
  return GoRouter(
    initialLocation: '/login',
    routes: [
      GoRoute(
        path:    '/login',
        builder: (_, __) => const LoginScreen(),
      ),
      GoRoute(
        path:    '/register',
        builder: (_, __) => const RegisterScreen(),
      ),
      GoRoute(
        path:    '/home',
        builder: (_, __) => const HomeScreen(),
      ),
      GoRoute(
        path:    '/payment/sender',
        builder: (_, __) => const TapDetectionScreen(isSender: true),
      ),
      GoRoute(
        path:    '/payment/receiver',
        builder: (_, __) => const TapDetectionScreen(isSender: false),
      ),
      GoRoute(
        path:    '/payment/entry',
        builder: (_, __) => const PaymentEntryScreen(),
      ),
      GoRoute(
        path:    '/payment/incoming',
        builder: (_, __) => const PaymentIncomingScreen(),
      ),
      GoRoute(
        path:    '/payment/result',
        builder: (_, __) => const ResultScreen(),
      ),
      GoRoute(
        path:    '/sensor-test',
        builder: (_, __) => const SensorTestScreen(),
      ),
      GoRoute(
        path:    '/transaction-history',
        builder: (_, __) => const TransactionHistoryScreen(),
      ),
    ],
  );
});
