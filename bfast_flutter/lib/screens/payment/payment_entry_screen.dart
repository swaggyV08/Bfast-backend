import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants/app_constants.dart';
import '../../core/theme/app_theme.dart';
import '../../providers/tap_provider.dart';

class PaymentEntryScreen extends ConsumerStatefulWidget {
  const PaymentEntryScreen({super.key});

  @override
  ConsumerState<PaymentEntryScreen> createState() => _PaymentEntryScreenState();
}

class _PaymentEntryScreenState extends ConsumerState<PaymentEntryScreen> {
  final _amountCtrl = TextEditingController();
  final _noteCtrl   = TextEditingController();
  bool  _submitting = false;

  @override
  void dispose() {
    _amountCtrl.dispose();
    _noteCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final tapState = ref.watch(tapProvider);
    final receiver = tapState.selectedReceiver;

    ref.listen<TapState>(tapProvider, (_, next) {
      if (!context.mounted) return;
      if (next.phase == TapPhase.paymentCompleted) {
        Navigator.pushNamedAndRemoveUntil(context, '/payment/result', (r) => r.isFirst);
      } else if (next.phase == TapPhase.error) {
        if (!mounted) return;
        setState(() => _submitting = false);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(next.errorMessage ?? 'Payment failed'),
            backgroundColor: AppTheme.error,
          ),
        );
      }
    });

    final isProcessing = _submitting;

    return Scaffold(
      backgroundColor: AppTheme.background,
      appBar: AppBar(
        title: const Text('Send Payment'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: isProcessing ? null : () => Navigator.pop(context),
        ),
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            children: [
              // Receiver info — always show (name or placeholder)
              ...[
                const SizedBox(height: 16),
                CircleAvatar(
                  radius: 36,
                  backgroundColor: AppTheme.primary.withValues(alpha: 0.2),
                  child: Text(
                    (receiver?.displayName.isNotEmpty == true)
                        ? receiver!.displayName[0].toUpperCase() : '?',
                    style: const TextStyle(
                      color:      AppTheme.primary,
                      fontSize:   28,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
                const SizedBox(height: 12),
                Text(
                  receiver?.displayName ?? 'Unknown',
                  style: const TextStyle(
                    color:      Colors.white,
                    fontSize:   20,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  receiver != null ? 'Verified tap payment' : 'Tap the phones together again',
                  style: TextStyle(
                    color:    receiver != null ? AppTheme.success : AppTheme.error,
                    fontSize: 13,
                  ),
                ),
              ],

              const SizedBox(height: 40),

              // Amount input
              const Text(
                'Enter Amount',
                style: TextStyle(color: AppTheme.textSecondary, fontSize: 14),
              ),
              const SizedBox(height: 12),
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                crossAxisAlignment: CrossAxisAlignment.baseline,
                textBaseline: TextBaseline.alphabetic,
                children: [
                  const Text(
                    '₹',
                    style: TextStyle(
                      color:      Colors.white,
                      fontSize:   36,
                      fontWeight: FontWeight.w300,
                    ),
                  ),
                  const SizedBox(width: 4),
                  IntrinsicWidth(
                    child: TextField(
                      controller:   _amountCtrl,
                      keyboardType: const TextInputType.numberWithOptions(decimal: true),
                      textAlign:    TextAlign.center,
                      autofocus:    true,
                      style: const TextStyle(
                        color:      Colors.white,
                        fontSize:   52,
                        fontWeight: FontWeight.w200,
                      ),
                      decoration: const InputDecoration(
                        border:    InputBorder.none,
                        hintText:  '0',
                        hintStyle: TextStyle(color: AppTheme.textSecondary, fontSize: 52),
                        fillColor: Colors.transparent,
                      ),
                      onChanged: (val) {
                        // Remove leading zeros (e.g. "023" → "23")
                        if (val.length > 1 && val.startsWith('0') && !val.startsWith('0.')) {
                          final trimmed = val.replaceFirst(RegExp(r'^0+'), '');
                          _amountCtrl.value = TextEditingValue(
                            text:      trimmed,
                            selection: TextSelection.collapsed(offset: trimmed.length),
                          );
                        }
                      },
                    ),
                  ),
                ],
              ),

              const SizedBox(height: 32),

              // Pay button
              ElevatedButton(
                onPressed: isProcessing ? null : _onPay,
                style: ElevatedButton.styleFrom(
                  backgroundColor: AppTheme.primary,
                  minimumSize:     const Size(double.infinity, 56),
                ),
                child: isProcessing
                    ? const SizedBox(
                        width: 24, height: 24,
                        child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2),
                      )
                    : const Text(
                        'Pay Now',
                        style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600),
                      ),
              ),
              const SizedBox(height: 12),
              OutlinedButton(
                onPressed: isProcessing ? null : () {
                  ref.read(tapProvider.notifier).reset();
                  Navigator.pushNamedAndRemoveUntil(context, '/home', (_) => false);
                },
                child: const Text('Cancel'),
              ),
              const SizedBox(height: 16),
            ],
          ),
          ),
        ),
      ),
    );
  }

  void _onPay() {
    final amountStr = _amountCtrl.text.trim().replaceAll(',', '');
    final amount    = double.tryParse(amountStr);
    if (amount == null || !amount.isFinite || amount <= 0) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Please enter a valid amount'),
          backgroundColor: AppTheme.error,
        ),
      );
      return;
    }
    if (amount > AppConstants.maxPaymentInr) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            'Maximum payment amount is ₹${AppConstants.maxPaymentInr.toStringAsFixed(0)}'),
          backgroundColor: AppTheme.error,
        ),
      );
      return;
    }
    setState(() => _submitting = true);
    ref.read(tapProvider.notifier).submitPayment(amount);
  }
}
