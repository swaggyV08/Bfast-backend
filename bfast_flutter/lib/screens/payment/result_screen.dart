import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/theme/app_theme.dart';
import '../../providers/tap_provider.dart';

class ResultScreen extends ConsumerWidget {
  const ResultScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tapState = ref.watch(tapProvider);
    final result   = tapState.paymentResult;
    final success  = result?.success ?? false;

    return Scaffold(
      backgroundColor: AppTheme.background,
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              // Icon
              Container(
                width: 100, height: 100,
                decoration: BoxDecoration(
                  color:  success
                      ? AppTheme.success.withValues(alpha: 0.15)
                      : AppTheme.error.withValues(alpha: 0.15),
                  shape: BoxShape.circle,
                ),
                child: Icon(
                  success ? Icons.check_rounded : Icons.close_rounded,
                  color: success ? AppTheme.success : AppTheme.error,
                  size: 60,
                ),
              ),
              const SizedBox(height: 32),

              Text(
                success ? 'Payment Sent!' : 'Payment Failed',
                style: TextStyle(
                  color:      success ? Colors.white : AppTheme.error,
                  fontSize:   28,
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: 12),

              if (success && result != null) ...[
                Text(
                  '₹${result.amount.toStringAsFixed(2)}',
                  style: const TextStyle(
                    color:      AppTheme.primary,
                    fontSize:   44,
                    fontWeight: FontWeight.w200,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  'to ${result.receiverName}',
                  style: const TextStyle(color: AppTheme.textSecondary, fontSize: 16),
                ),
                const SizedBox(height: 32),
                _InfoRow(label: 'Transaction ID', value: result.transactionId.isNotEmpty
                    ? result.transactionId.substring(0, 8).toUpperCase() : 'N/A'),
                const SizedBox(height: 8),
                _InfoRow(
                  label: 'Time',
                  value: '${result.timestamp.hour.toString().padLeft(2, '0')}:'
                      '${result.timestamp.minute.toString().padLeft(2, '0')}',
                ),
              ],

              if (!success) ...[
                const SizedBox(height: 16),
                Text(
                  tapState.errorMessage ?? 'The payment could not be processed.',
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: AppTheme.textSecondary, fontSize: 14),
                ),
              ],

              const SizedBox(height: 48),

              ElevatedButton(
                onPressed: () {
                  ref.read(tapProvider.notifier).reset();
                  context.go('/home');
                },
                child: const Text('Back to Home'),
              ),

              if (!success) ...[
                const SizedBox(height: 12),
                OutlinedButton(
                  onPressed: () {
                    ref.read(tapProvider.notifier).reset();
                    context.go('/payment/sender');
                  },
                  child: const Text('Try Again'),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _InfoRow extends StatelessWidget {
  final String label;
  final String value;
  const _InfoRow({required this.label, required this.value});

  @override
  Widget build(BuildContext context) => Row(
    mainAxisAlignment: MainAxisAlignment.spaceBetween,
    children: [
      Text(label, style: const TextStyle(color: AppTheme.textSecondary, fontSize: 14)),
      Text(value,  style: const TextStyle(color: Colors.white, fontSize: 14, fontWeight: FontWeight.w500)),
    ],
  );
}
