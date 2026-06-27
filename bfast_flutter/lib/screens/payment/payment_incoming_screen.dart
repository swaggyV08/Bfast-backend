import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/theme/app_theme.dart';
import '../../providers/tap_provider.dart';
import '../../providers/auth_provider.dart';
import '../../services/api_service.dart';

/// Shown on the RECEIVER side immediately after tap detection.
///
/// Polls the wallet balance every 1.5 s. When the sender submits payment and
/// the balance rises, shows a success confirmation with the received amount.
class PaymentIncomingScreen extends ConsumerStatefulWidget {
  const PaymentIncomingScreen({super.key});

  @override
  ConsumerState<PaymentIncomingScreen> createState() => _PaymentIncomingScreenState();
}

class _PaymentIncomingScreenState extends ConsumerState<PaymentIncomingScreen>
    with SingleTickerProviderStateMixin {
  late AnimationController _pulseController;
  late Animation<double>   _pulseAnim;

  Timer?  _pollTimer;
  double? _initialBalance;
  double? _receivedAmount;
  bool    _paymentReceived = false;

  @override
  void initState() {
    super.initState();

    _pulseController = AnimationController(
      vsync:    this,
      duration: const Duration(milliseconds: 900),
    )..repeat(reverse: true);
    _pulseAnim = Tween<double>(begin: 0.92, end: 1.08)
        .animate(CurvedAnimation(parent: _pulseController, curve: Curves.easeInOut));

    // Capture starting balance, then start polling
    _initBalance();
  }

  Future<void> _initBalance() async {
    try {
      final api = ref.read(apiServiceProvider);
      final res = await api.getWallet();
      _initialBalance = ((res['data']?['balance'] ?? 0) as num).toDouble();
    } catch (_) {}

    _pollTimer = Timer.periodic(const Duration(milliseconds: 1500), (_) => _pollBalance());
  }

  Future<void> _pollBalance() async {
    if (_paymentReceived) return;
    try {
      final api = ref.read(apiServiceProvider);
      final res = await api.getWallet();
      final balance = ((res['data']?['balance'] ?? 0) as num).toDouble();
      final initial = _initialBalance ?? balance;

      if (balance > initial + 0.01) {
        setState(() {
          _paymentReceived = true;
          _receivedAmount  = balance - initial;
        });
        _pollTimer?.cancel();
        // Navigate to result after brief celebration delay
        await Future.delayed(const Duration(seconds: 2));
        if (mounted) context.go('/home');
      }
    } catch (_) {}
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    _pulseController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final tapState   = ref.watch(tapProvider);
    final senderName = tapState.selectedReceiver?.displayName ?? 'Someone';

    return Scaffold(
      backgroundColor: Colors.black,
      body: SafeArea(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(32),
            child: _paymentReceived
                ? _buildSuccess(senderName)
                : _buildWaiting(senderName),
          ),
        ),
      ),
    );
  }

  Widget _buildWaiting(String senderName) => Column(
    mainAxisAlignment: MainAxisAlignment.center,
    children: [
      AnimatedBuilder(
        animation: _pulseAnim,
        builder: (_, __) => Transform.scale(
          scale: _pulseAnim.value,
          child: Container(
            width:  100,
            height: 100,
            decoration: BoxDecoration(
              shape:  BoxShape.circle,
              color:  AppTheme.primary.withValues(alpha: 0.15),
              border: Border.all(color: AppTheme.primary, width: 2.5),
            ),
            child: const Icon(Icons.payments_outlined, color: AppTheme.primary, size: 50),
          ),
        ),
      ),
      const SizedBox(height: 32),
      const Text(
        'Payment incoming!',
        style: TextStyle(color: Colors.white, fontSize: 26, fontWeight: FontWeight.w700),
      ),
      const SizedBox(height: 12),
      Text(
        'From: $senderName',
        style: const TextStyle(color: AppTheme.textSecondary, fontSize: 17),
      ),
      const SizedBox(height: 40),
      const SizedBox(
        width: 28, height: 28,
        child: CircularProgressIndicator(color: AppTheme.primary, strokeWidth: 2),
      ),
      const SizedBox(height: 12),
      const Text(
        'Waiting for sender to confirm amount…',
        style: TextStyle(color: AppTheme.textSecondary, fontSize: 13),
        textAlign: TextAlign.center,
      ),
      const SizedBox(height: 56),
      TextButton(
        onPressed: () {
          ref.read(tapProvider.notifier).startAsReceiver();
          context.pop();
        },
        child: const Text('Cancel', style: TextStyle(color: AppTheme.textSecondary)),
      ),
    ],
  );

  Widget _buildSuccess(String senderName) => Column(
    mainAxisAlignment: MainAxisAlignment.center,
    children: [
      Container(
        width:  100,
        height: 100,
        decoration: const BoxDecoration(
          shape: BoxShape.circle,
          color: Color(0x224CAF50),
        ),
        child: const Icon(Icons.check_circle_outline, color: Color(0xFF4CAF50), size: 60),
      ),
      const SizedBox(height: 24),
      const Text(
        'Payment Received!',
        style: TextStyle(color: Colors.white, fontSize: 26, fontWeight: FontWeight.w700),
      ),
      const SizedBox(height: 12),
      if (_receivedAmount != null)
        Text(
          '₹${_receivedAmount!.toStringAsFixed(2)}',
          style: const TextStyle(color: Color(0xFF4CAF50), fontSize: 42, fontWeight: FontWeight.w300),
        ),
      const SizedBox(height: 8),
      Text(
        'from $senderName',
        style: const TextStyle(color: AppTheme.textSecondary, fontSize: 16),
      ),
      const SizedBox(height: 24),
      const Text(
        'Returning home…',
        style: TextStyle(color: AppTheme.textSecondary, fontSize: 13),
      ),
    ],
  );
}
