import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import '../../core/theme/app_theme.dart';
import '../../providers/auth_provider.dart';
import '../../services/api_service.dart';

// ── Provider ──────────────────────────────────────────────────────────────

final _historyProvider =
    FutureProvider.autoDispose<List<Map<String, dynamic>>>((ref) async {
  final api = ref.read(apiServiceProvider);
  final res = await api.getTransactionHistory(page: 1, limit: 50);
  if (res['success'] == true) {
    final data = res['data'] as Map<String, dynamic>? ?? {};
    final list = data['transactions'] as List? ??
                 data['entries']      as List? ??
                 data['items']        as List? ?? [];
    return list.cast<Map<String, dynamic>>();
  }
  return [];
});

// ── Screen ────────────────────────────────────────────────────────────────

class TransactionHistoryScreen extends ConsumerWidget {
  const TransactionHistoryScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final historyAsync = ref.watch(_historyProvider);
    final authState    = ref.watch(authProvider);
    final myUserId     = authState is AuthAuthenticated
        ? authState.user.id : '';

    return Scaffold(
      backgroundColor: AppTheme.background,
      appBar: AppBar(
        title: const Text('Transaction History',
            style: TextStyle(fontWeight: FontWeight.w600)),
        actions: [
          IconButton(
            icon:    const Icon(Icons.refresh),
            onPressed: () => ref.invalidate(_historyProvider),
          ),
        ],
      ),
      body: historyAsync.when(
        data: (txns) {
          if (txns.isEmpty) {
            return const Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.receipt_long_outlined,
                      size: 64, color: AppTheme.textSecondary),
                  SizedBox(height: 16),
                  Text('No transactions yet',
                      style: TextStyle(
                          color: AppTheme.textSecondary, fontSize: 16)),
                ],
              ),
            );
          }
          return RefreshIndicator(
            onRefresh: () async => ref.invalidate(_historyProvider),
            color: AppTheme.primary,
            child: ListView.separated(
              padding: const EdgeInsets.all(16),
              itemCount: txns.length,
              separatorBuilder: (_, __) => const SizedBox(height: 8),
              itemBuilder: (_, i) =>
                  _TxnCard(txn: txns[i], myUserId: myUserId),
            ),
          );
        },
        loading: () => const Center(
          child: CircularProgressIndicator(color: AppTheme.primary),
        ),
        error: (e, _) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.wifi_off_rounded,
                  size: 48, color: AppTheme.textSecondary),
              const SizedBox(height: 12),
              Text('Could not load history\n${e.toString()}',
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                      color: AppTheme.textSecondary, fontSize: 14)),
              const SizedBox(height: 16),
              ElevatedButton(
                onPressed: () => ref.invalidate(_historyProvider),
                child: const Text('Retry'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ── Transaction card ──────────────────────────────────────────────────────

class _TxnCard extends StatelessWidget {
  final Map<String, dynamic> txn;
  final String               myUserId;

  const _TxnCard({required this.txn, required this.myUserId});

  @override
  Widget build(BuildContext context) {
    final amountPaise   = (txn['amountPaise'] as num?)?.toInt() ?? 0;
    final amountInr     = amountPaise / 100.0;
    final status        = txn['status']         as String? ?? 'unknown';
    final senderUserId  = txn['senderUserId']   as String? ?? '';
    final isSend        = senderUserId == myUserId;
    final rawTs         = txn['initiatedAt']    as String? ?? '';
    final txRef         = txn['txRef']          as String? ?? '';
    final note          = txn['note']           as String?;

    DateTime? ts;
    try { ts = DateTime.parse(rawTs).toLocal(); } catch (_) {}

    final amountStr = '₹${amountInr.toStringAsFixed(2)}';
    final sign      = isSend ? '-' : '+';
    final color     = status == 'completed'
        ? (isSend ? Colors.red.shade400 : const Color(0xFF4CAF50))
        : Colors.grey;
    final icon      = isSend ? Icons.arrow_upward : Icons.arrow_downward;

    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color:        AppTheme.surface,
        borderRadius: BorderRadius.circular(14),
      ),
      child: Row(
        children: [
          // Direction icon
          Container(
            width: 40, height: 40,
            decoration: BoxDecoration(
              color:  color.withValues(alpha: 0.15),
              shape: BoxShape.circle,
            ),
            child: Icon(icon, color: color, size: 18),
          ),
          const SizedBox(width: 12),
          // Details
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  isSend ? 'Sent' : 'Received',
                  style: const TextStyle(
                      color:      AppTheme.textPrimary,
                      fontSize:   14,
                      fontWeight: FontWeight.w600),
                ),
                if (txRef.isNotEmpty)
                  Text(
                    txRef.length > 12
                        ? '${txRef.substring(0, 12)}…'
                        : txRef,
                    style: const TextStyle(
                        color: AppTheme.textSecondary, fontSize: 11),
                  ),
                if (note != null && note.isNotEmpty)
                  Text(note,
                      style: const TextStyle(
                          color: AppTheme.textSecondary, fontSize: 12)),
              ],
            ),
          ),
          // Amount + date
          Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Text(
                '$sign$amountStr',
                style: TextStyle(
                    color:      color,
                    fontSize:   16,
                    fontWeight: FontWeight.w700),
              ),
              const SizedBox(height: 2),
              Text(
                ts != null
                    ? DateFormat('dd MMM, hh:mm a').format(ts)
                    : '—',
                style: const TextStyle(
                    color: AppTheme.textSecondary, fontSize: 11),
              ),
              const SizedBox(height: 2),
              _StatusChip(status: status),
            ],
          ),
        ],
      ),
    );
  }
}

class _StatusChip extends StatelessWidget {
  final String status;
  const _StatusChip({required this.status});

  @override
  Widget build(BuildContext context) {
    Color color;
    switch (status) {
      case 'completed': color = const Color(0xFF4CAF50); break;
      case 'pending':   color = Colors.orange;            break;
      case 'failed':    color = Colors.red;               break;
      default:          color = Colors.grey;
    }
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
      decoration: BoxDecoration(
        color:        color.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(
        status,
        style: TextStyle(color: color, fontSize: 10, fontWeight: FontWeight.w600),
      ),
    );
  }
}
